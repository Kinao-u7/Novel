import fs from 'fs';
import path from 'path';
import vm from 'vm';
import { fileURLToPath } from 'url';
import { ensurePlugins, getAllFiles, COMPILED_PLUGINS_DIR } from '../catalogue-scan/plugins-cache.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '../..');

const polyfillsCode = fs.readFileSync(path.join(root, 'app/src/main/assets/js/polyfills.js'), 'utf-8');
const libsCode = fs.readFileSync(path.join(root, 'app/src/main/assets/js/libs.js'), 'utf-8');
const harnessCode = fs.readFileSync(path.join(root, 'app/src/main/assets/js/harness.js'), 'utf-8');

ensurePlugins();
const pluginFiles = getAllFiles(COMPILED_PLUGINS_DIR, ['.js']);
console.log(`Evaluating ${pluginFiles.length} plugins in isolated VM harness with default filters...`);

function withTimeout(promise, ms = 5000) {
  let timer;
  const timeoutPromise = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`Timeout after ${ms}ms`)), ms); });
  return Promise.race([promise, timeoutPromise]).finally(() => clearTimeout(timer));
}

function classifyError(err) {
  const line = (err?.stack || err?.message || String(err)).split('\n')[0].trim();
  const name = err?.name || '';
  const isHarnessGap = name === 'ReferenceError' || name === 'UnsupportedPluginFeature' ||
    line.includes('is not defined') || line.includes('UnsupportedPluginFeature') ||
    line.includes('Unsupported plugin feature') || line.includes('is not a function') || line.includes('Cannot find module');
  return { status: line.includes('Timeout after') ? 'TIMEOUT' : isHarnessGap ? 'HARNESS_GAP' : 'EMPTY_BODY_ERROR', error: line };
}

const report = {}, errorMap = new Map(), sortGenresErrors = [];
const counts = { loadOk: 0, constructOk: 0, popOk: 0, popEmpty: 0, popGap: 0, popTimeout: 0, searchOk: 0, searchEmpty: 0, searchGap: 0, searchTimeout: 0 };

function trackError(errLine, relPath) {
  if (!errLine) return;
  if (!errorMap.has(errLine)) errorMap.set(errLine, new Set());
  errorMap.get(errLine).add(relPath);
}

for (const filePath of pluginFiles) {
  const relPath = path.relative(COMPILED_PLUGINS_DIR, filePath);
  const pluginId = path.basename(filePath, '.js').toLowerCase().replace(/[^a-z0-9_]/g, '_');
  const sourceCode = fs.readFileSync(filePath, 'utf-8');

  const sandbox = {
    __host_fetch: async () => JSON.stringify({ status: 200, ok: true, statusText: 'OK', url: 'https://example.com', headers: [], body: '' }),
    __host_sleep: async () => true,
    console: { log: () => {}, error: () => {}, warn: () => {} }
  };
  const ctx = vm.createContext(sandbox);

  let pluginMeta;
  try {
    vm.runInContext(polyfillsCode, ctx);
    vm.runInContext(libsCode, ctx);
    vm.runInContext(harnessCode, ctx);
    pluginMeta = ctx.__loadPlugin(pluginId, sourceCode);
  } catch (e) {
    const c = classifyError(e);
    trackError(c.error, relPath);
    report[relPath] = { load: 'LOAD_ERROR', construct: 'SKIPPED', popularNovels: 'SKIPPED', searchNovels: 'SKIPPED', error: c.error };
    continue;
  }

  counts.loadOk++;
  counts.constructOk++;

  const plugin = ctx.__loadedPlugins.get(pluginId);
  const defaultFilters = {};
  if (plugin && plugin.filters && typeof plugin.filters === 'object') {
    for (const [key, filter] of Object.entries(plugin.filters)) {
      if (filter && typeof filter === 'object' && 'value' in filter) {
        defaultFilters[key] = { ...filter, value: filter.value };
      } else {
        defaultFilters[key] = { value: filter };
      }
    }
  }
  const defaultOptions = { filters: defaultFilters, showLatestNovels: false };

  let popRes;
  try {
    await withTimeout(ctx.__callPlugin(pluginId, 'popularNovels', JSON.stringify([1, defaultOptions])), 5000);
    popRes = { status: 'OK' };
    counts.popOk++;
  } catch (e) {
    popRes = classifyError(e);
    if (popRes.status === 'EMPTY_BODY_ERROR') counts.popEmpty++;
    else if (popRes.status === 'HARNESS_GAP') counts.popGap++;
    else counts.popTimeout++;
    trackError(popRes.error, relPath);
    if (popRes.error && /reading '(sort|genres|genre)'/.test(popRes.error)) {
      sortGenresErrors.push({ plugin: relPath, method: 'popularNovels', error: popRes.error });
    }
  }

  let searchRes;
  try {
    await withTimeout(ctx.__callPlugin(pluginId, 'searchNovels', JSON.stringify(['test', 1, defaultOptions])), 5000);
    searchRes = { status: 'OK' };
    counts.searchOk++;
  } catch (e) {
    searchRes = classifyError(e);
    if (searchRes.status === 'EMPTY_BODY_ERROR') counts.searchEmpty++;
    else if (searchRes.status === 'HARNESS_GAP') counts.searchGap++;
    else counts.searchTimeout++;
    trackError(searchRes.error, relPath);
    if (searchRes.error && /reading '(sort|genres|genre)'/.test(searchRes.error)) {
      sortGenresErrors.push({ plugin: relPath, method: 'searchNovels', error: searchRes.error });
    }
  }

  report[relPath] = { id: pluginMeta?.id || pluginId, load: 'LOAD_OK', construct: 'CONSTRUCT_OK', popularNovels: popRes.status, popError: popRes.error || null, searchNovels: searchRes.status, searchError: searchRes.error || null };
}

const outPath = path.join(__dirname, 'report.json');
fs.writeFileSync(outPath, JSON.stringify(report, null, 2), 'utf-8');

console.log('\n=== CATALOGUE HARNESS SUMMARY ===');
console.log(`Total Plugins Evaluated : ${pluginFiles.length}`);
console.log(`LOAD_OK                 : ${counts.loadOk}/${pluginFiles.length}`);
console.log(`CONSTRUCT_OK            : ${counts.constructOk}/${pluginFiles.length}`);
console.log(`popularNovels           : OK=${counts.popOk}, EMPTY_BODY_ERROR=${counts.popEmpty}, HARNESS_GAP=${counts.popGap}, TIMEOUT=${counts.popTimeout}`);
console.log(`searchNovels            : OK=${counts.searchOk}, EMPTY_BODY_ERROR=${counts.searchEmpty}, HARNESS_GAP=${counts.searchGap}, TIMEOUT=${counts.searchTimeout}`);

const sortedErrors = Array.from(errorMap.entries())
  .map(([msg, plugins]) => ({ msg, count: plugins.size }))
  .sort((a, b) => b.count - a.count)
  .slice(0, 10);

console.log('\n=== TOP 10 GROUPED ERRORS (BY AFFECTED PLUGINS) ===');
sortedErrors.forEach((e, idx) => {
  console.log(`${String(idx + 1).padStart(2, ' ')}. [${String(e.count).padStart(3, ' ')} plugins] ${e.msg}`);
});

console.log('\n=== REMAINING "reading sort/genres" ERRORS ===');
if (sortGenresErrors.length === 0) {
  console.log('None! All sort/genres TypeErrors resolved by default filters.');
} else {
  sortGenresErrors.forEach(e => console.log(`- ${e.plugin} (${e.method}): ${e.error}`));
}
console.log(`\nDetailed per-plugin report written to ${outPath}\n`);
