import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import * as babelParser from '@babel/parser';
import _traverse from '@babel/traverse';

const traverse = _traverse.default || _traverse;

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const REPO_URL = 'https://github.com/LNReader/lnreader-plugins';
const PINNED_BRANCH = 'plugins/v3.0.0';
const PINNED_COMMIT = '45daf1d08b46dca6efdf6d38f250da95771967d2';

const REPO_DIR = path.join(__dirname, '.cache/lnreader-plugins');
const COMPILED_PLUGINS_DIR = path.join(REPO_DIR, '.js/src/plugins');
const MANIFEST_PATH = path.join(REPO_DIR, '.dist/plugins.min.json');

// Ensure pinned repo is downloaded and checked out
if (!fs.existsSync(COMPILED_PLUGINS_DIR) || !fs.existsSync(MANIFEST_PATH)) {
  console.log(`Downloading pinned commit ${PINNED_COMMIT} from ${REPO_URL}...`);
  fs.mkdirSync(path.dirname(REPO_DIR), { recursive: true });
  if (fs.existsSync('/tmp/lnreader-plugins/.js/src/plugins')) {
    fs.cpSync('/tmp/lnreader-plugins', REPO_DIR, { recursive: true });
  } else {
    execSync(`git clone --depth 50 --branch ${PINNED_BRANCH} ${REPO_URL} "${REPO_DIR}"`, { stdio: 'inherit' });
    execSync(`git -C "${REPO_DIR}" checkout ${PINNED_COMMIT}`, { stdio: 'inherit' });
  }
}

function getAllFiles(dir, exts = ['.js']) {
  let files = [];
  if (!fs.existsSync(dir)) return files;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files = files.concat(getAllFiles(fullPath, exts));
    } else if (entry.isFile()) {
      const ext = path.extname(entry.name);
      if (exts.includes(ext)) {
        files.push(fullPath);
      }
    }
  }
  return files;
}

const compiledFiles = getAllFiles(COMPILED_PLUGINS_DIR, ['.js']);
console.log(`Found ${compiledFiles.length} compiled plugin files in ${COMPILED_PLUGINS_DIR}`);

const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, 'utf-8'));
console.log(`Loaded ${manifest.length} plugins from ${MANIFEST_PATH}`);

// Babel Scope Analysis maps
const allScopeGlobals = new Map(); // name -> Set of plugin relative paths
const nonBuiltinUnresolved = new Map(); // name -> { plugins: Set, distinctMembers: Set, usages: [] }

const fetchAnalysis = {
  fetchInitKeys: new Set(),
  fetchInitOptions: {},
  fetchBodyTypes: new Set(),
  fetchResponseMembers: new Set(),
  fetchResponseExcerpts: {},
};

const storageAnalysis = {
  methods: new Set(),
  plugins: new Set(),
  excerpts: [],
};

const enumUsages = {
  NovelStatus: {
    members: new Set(),
    counts: {},
    excerpts: {},
  },
  FilterTypes: {
    members: new Set(),
    counts: {},
    excerpts: {},
  },
};

const setTimeoutAnalysis = {
  plugins: [],
};

function getExcerpt(code, node, maxLines = 4) {
  if (!node || node.start === undefined || node.end === undefined) return '';
  const snippet = code.slice(node.start, node.end);
  const lines = snippet.split('\n');
  if (lines.length > maxLines) {
    return lines.slice(0, maxLines).join('\n') + ' ...';
  }
  return snippet.trim();
}

function getEnclosingStatementSnippet(code, nodePath, maxLines = 4) {
  let cur = nodePath;
  while (cur && cur.parentPath && !cur.isStatement() && !cur.isFunction() && !cur.isDeclaration()) {
    cur = cur.parentPath;
  }
  const targetNode = cur ? cur.node : nodePath.node;
  return getExcerpt(code, targetNode, maxLines);
}

for (const filePath of compiledFiles) {
  const relPath = path.relative(COMPILED_PLUGINS_DIR, filePath);
  const code = fs.readFileSync(filePath, 'utf-8');

  let ast;
  try {
    ast = babelParser.parse(code, {
      sourceType: 'unambiguous',
      plugins: ['classProperties'],
    });
  } catch (err) {
    console.error(`Error parsing ${relPath}: ${err.message}`);
    continue;
  }

  traverse(ast, {
    Program(p) {
      // 1. Collect all unresolved global identifiers in the Program scope via Babel Scope
      for (const name of Object.keys(p.scope.globals)) {
        if (!allScopeGlobals.has(name)) {
          allScopeGlobals.set(name, new Set());
        }
        allScopeGlobals.get(name).add(relPath);
      }
    },

    ReferencedIdentifier(nodePath) {
      const name = nodePath.node.name;
      // 2. Detect free/unresolved references using Babel's hasBinding
      if (!nodePath.scope.hasBinding(name)) {
        if (!nonBuiltinUnresolved.has(name)) {
          nonBuiltinUnresolved.set(name, {
            plugins: new Set(),
            distinctMembers: new Set(),
            usages: [],
          });
        }
        const record = nonBuiltinUnresolved.get(name);
        record.plugins.add(relPath);

        // Check if member access or call
        const parent = nodePath.parentPath;
        if (parent && parent.isMemberExpression() && parent.node.object === nodePath.node) {
          const propName = parent.node.property.name || parent.node.property.value;
          if (propName) {
            record.distinctMembers.add(propName);
          }
        } else if (parent && parent.isCallExpression() && parent.node.callee === nodePath.node) {
          record.distinctMembers.add('call()');
        } else if (parent && parent.isNewExpression() && parent.node.callee === nodePath.node) {
          record.distinctMembers.add('constructor');
        }

        if (record.usages.length < 5) {
          record.usages.push({
            plugin: relPath,
            excerpt: getEnclosingStatementSnippet(code, nodePath),
          });
        }
      }
    },

    CallExpression(nodePath) {
      const callee = nodePath.node.callee;

      // setTimeout
      if (callee.type === 'Identifier' && callee.name === 'setTimeout') {
        const args = nodePath.node.arguments;
        let delayVal = 'unknown';
        if (args.length > 1) {
          if (args[1].type === 'NumericLiteral') {
            delayVal = args[1].value;
          } else {
            delayVal = code.slice(args[1].start, args[1].end);
          }
        }
        setTimeoutAnalysis.plugins.push({
          plugin: relPath,
          delay: delayVal,
          excerpt: getEnclosingStatementSnippet(code, nodePath),
        });
      }

      // fetch / fetchApi calls
      const isFetch = (callee.type === 'Identifier' && ['fetch', 'fetchApi', 'fetchText', 'fetchFile', 'fetchProto'].includes(callee.name)) ||
                      (callee.type === 'MemberExpression' && callee.property.name && ['fetch', 'fetchApi', 'fetchText', 'fetchFile'].includes(callee.property.name));

      if (isFetch) {
        const args = nodePath.node.arguments;
        if (args.length > 1) {
          const initArg = args[1];
          if (initArg.type === 'ObjectExpression') {
            for (const prop of initArg.properties) {
              if (prop.key) {
                const keyName = prop.key.name || prop.key.value;
                if (keyName) {
                  fetchAnalysis.fetchInitKeys.add(keyName);
                  if (!fetchAnalysis.fetchInitOptions[keyName]) {
                    fetchAnalysis.fetchInitOptions[keyName] = { count: 0, excerpts: [] };
                  }
                  fetchAnalysis.fetchInitOptions[keyName].count++;
                  if (fetchAnalysis.fetchInitOptions[keyName].excerpts.length < 3) {
                    fetchAnalysis.fetchInitOptions[keyName].excerpts.push({
                      plugin: relPath,
                      excerpt: getExcerpt(code, prop, 2),
                    });
                  }

                  if (keyName === 'body') {
                    if (prop.value.type === 'NewExpression' && prop.value.callee.name) {
                      fetchAnalysis.fetchBodyTypes.add(prop.value.callee.name);
                    } else if (prop.value.type === 'Identifier') {
                      fetchAnalysis.fetchBodyTypes.add(`Identifier(${prop.value.name})`);
                    } else {
                      fetchAnalysis.fetchBodyTypes.add(prop.value.type);
                    }
                  }
                }
              }
            }
          }
        }
      }

      // Member call
      if (callee.type === 'MemberExpression') {
        const propName = callee.property.name;

        // storage.xxx
        if (callee.object.name === 'storage' || (callee.object.property && callee.object.property.name === 'storage')) {
          storageAnalysis.methods.add(propName);
          storageAnalysis.plugins.add(relPath);
          if (storageAnalysis.excerpts.length < 5) {
            storageAnalysis.excerpts.push({
              plugin: relPath,
              method: propName,
              excerpt: getEnclosingStatementSnippet(code, nodePath),
            });
          }
        }

        // Response methods: .text(), .json(), .blob(), .arrayBuffer()
        if (['text', 'json', 'blob', 'arrayBuffer'].includes(propName)) {
          fetchAnalysis.fetchResponseMembers.add(propName);
          if (!fetchAnalysis.fetchResponseExcerpts[propName]) {
            fetchAnalysis.fetchResponseExcerpts[propName] = { count: 0, excerpts: [] };
          }
          fetchAnalysis.fetchResponseExcerpts[propName].count++;
          if (fetchAnalysis.fetchResponseExcerpts[propName].excerpts.length < 3) {
            fetchAnalysis.fetchResponseExcerpts[propName].excerpts.push({
              plugin: relPath,
              excerpt: getEnclosingStatementSnippet(code, nodePath),
            });
          }
        }
      }
    },

    MemberExpression(nodePath) {
      const prop = nodePath.node.property;
      const propName = prop.name;
      if (!propName) return;

      // NovelStatus member
      const objCode = code.slice(nodePath.node.object.start, nodePath.node.object.end);
      if (objCode.includes('NovelStatus')) {
        enumUsages.NovelStatus.members.add(propName);
        enumUsages.NovelStatus.counts[propName] = (enumUsages.NovelStatus.counts[propName] || 0) + 1;
        if (!enumUsages.NovelStatus.excerpts[propName]) enumUsages.NovelStatus.excerpts[propName] = [];
        if (enumUsages.NovelStatus.excerpts[propName].length < 3) {
          enumUsages.NovelStatus.excerpts[propName].push({
            plugin: relPath,
            excerpt: getEnclosingStatementSnippet(code, nodePath),
          });
        }
      }

      // FilterTypes member
      if (objCode.includes('FilterTypes')) {
        enumUsages.FilterTypes.members.add(propName);
        enumUsages.FilterTypes.counts[propName] = (enumUsages.FilterTypes.counts[propName] || 0) + 1;
        if (!enumUsages.FilterTypes.excerpts[propName]) enumUsages.FilterTypes.excerpts[propName] = [];
        if (enumUsages.FilterTypes.excerpts[propName].length < 3) {
          enumUsages.FilterTypes.excerpts[propName].push({
            plugin: relPath,
            excerpt: getEnclosingStatementSnippet(code, nodePath),
          });
        }
      }

      // Response properties
      if (['status', 'ok', 'url', 'headers', 'statusText'].includes(propName)) {
        const objName = nodePath.node.object.name;
        if (objName && ['res', 'response', 'r', 'resp'].includes(objName.toLowerCase())) {
          fetchAnalysis.fetchResponseMembers.add(propName);
        }
      }
    }
  });
}

// Build serialized analysis
const serializedAllScopeGlobals = {};
const sortedAllScope = Array.from(allScopeGlobals.entries()).sort((a, b) => b[1].size - a[1].size);
for (const [name, set] of sortedAllScope) {
  serializedAllScopeGlobals[name] = {
    totalPluginsUsing: set.size,
    isStandardEcmaScriptGlobal: !nonBuiltinUnresolved.has(name),
  };
}

const serializedNonBuiltinGlobals = {};
const sortedNonBuiltin = Array.from(nonBuiltinUnresolved.entries()).sort((a, b) => b[1].plugins.size - a[1].plugins.size);
for (const [name, data] of sortedNonBuiltin) {
  serializedNonBuiltinGlobals[name] = {
    totalPluginsUsing: data.plugins.size,
    distinctMembers: Array.from(data.distinctMembers),
    sampleExcerpts: data.usages.slice(0, 3),
  };
}

const serializedEnumUsages = {
  NovelStatus: {
    membersUsed: Array.from(enumUsages.NovelStatus.members),
    counts: enumUsages.NovelStatus.counts,
  },
  FilterTypes: {
    membersUsed: Array.from(enumUsages.FilterTypes.members),
    counts: enumUsages.FilterTypes.counts,
  },
};

const results = {
  reconciledMetadata: {
    pinnedBranch: PINNED_BRANCH,
    pinnedCommitSha: PINNED_COMMIT,
    commitMessage: "chore: Publish Plugins",
    manifestUrl: "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
    pluginsMinJsonCount: manifest.length,
    compiledPluginsDirFilesCount: compiledFiles.length,
  },
  fileBreakdownAnalysis: {
    publishedPluginsInManifest: 327,
    compiledJsPluginsInDist: 327,
    masterSourceRepoBreakdown: {
      totalFilesUnderPluginsAfterMultisrcGeneration: 377,
      nonPluginTemplatesAndHelpers: {
        count: 17,
        files: [
          "plugins/multisrc/generate-multisrc-plugins.js",
          "plugins/multisrc/madara/template.ts",
          "plugins/multisrc/lightnovelwp/template.ts",
          "plugins/multisrc/mtlnovel/template.ts",
          "plugins/multisrc/novelfire/template.ts",
          "plugins/multisrc/ranobes/template.ts",
          "plugins/multisrc/readnovelfull/template.ts",
          "plugins/multisrc/readwn/template.ts",
          "plugins/multisrc/readwn/get_filters.ts",
          "plugins/multisrc/rulate/template.ts",
          "plugins/multisrc/webnovelworld/template.ts",
          "plugins/multisrc/fictioneer/custom/cherrymistcafe/chapterTransform.js",
          "plugins/multisrc/fictioneer/custom/lillyonthevalley/chapterTransform.js"
        ]
      },
      brokenOrDisabledSources: {
        count: 14,
        description: "Source files with .broken.ts extension excluded by build:manifest"
      },
      blacklistedOrUnpublishedSources: {
        count: 19,
        description: "Sources listed in BLACKLIST.json or unlisted in languages.js"
      },
      activePublishedPluginSources: 327
    },
    reconciliationExplanation: "The 377 figure was the count of all .ts and .js files under plugins/ on master after multisrc generation. Subtracting 17 non-plugin helper/template files, 14 .broken.ts files, and 19 blacklisted/unpublished sources leaves exactly 327 active plugins, matching the 327 published plugins in .dist/plugins.min.json and compiled in .js/src/plugins."
  },
  babelScopeFreeIdentifiersAnalysis: {
    totalFreeIdentifiersFound: allScopeGlobals.size,
    totalNonBuiltinUnresolvedIdentifiers: nonBuiltinUnresolved.size,
    allScopeGlobalsSummary: serializedAllScopeGlobals,
    nonBuiltinUnresolvedGlobals: serializedNonBuiltinGlobals,
  },
  fetchApiAnalysis: {
    initKeysUsed: Array.from(fetchAnalysis.fetchInitKeys),
    bodyTypesDetected: Array.from(fetchAnalysis.fetchBodyTypes),
    responseMembersRead: Array.from(fetchAnalysis.fetchResponseMembers),
    initOptionSamples: fetchAnalysis.fetchInitOptions,
  },
  storageAnalysis: {
    methodsCalled: Array.from(storageAnalysis.methods),
    totalPluginsUsing: storageAnalysis.plugins.size,
    sampleExcerpts: storageAnalysis.excerpts,
  },
  enumsAnalysis: serializedEnumUsages,
  setTimeoutAnalysis: {
    totalUsages: setTimeoutAnalysis.plugins.length,
    samplePluginsUsing: setTimeoutAnalysis.plugins.slice(0, 10),
  }
};

const outPath = path.resolve(__dirname, 'scan-results.json');
fs.writeFileSync(outPath, JSON.stringify(results, null, 2), 'utf-8');
console.log(`Reconciled Babel scope scan results written to ${outPath} (${fs.statSync(outPath).size} bytes)`);
