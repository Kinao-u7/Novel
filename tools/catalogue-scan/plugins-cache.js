import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export const REPO_URL = 'https://github.com/LNReader/lnreader-plugins';
export const PINNED_BRANCH = 'plugins/v3.0.0';
export const PINNED_COMMIT = '45daf1d08b46dca6efdf6d38f250da95771967d2';

export const REPO_DIR = path.join(__dirname, '.cache/lnreader-plugins-45daf1d');
export const COMPILED_PLUGINS_DIR = path.join(REPO_DIR, '.js/src/plugins');
export const MANIFEST_PATH = path.join(REPO_DIR, '.dist/plugins.min.json');

export function ensurePlugins() {
  if (!fs.existsSync(COMPILED_PLUGINS_DIR) || !fs.existsSync(MANIFEST_PATH)) {
    console.log(`Ensuring pinned commit ${PINNED_COMMIT} from ${REPO_URL}...`);
    fs.mkdirSync(path.dirname(REPO_DIR), { recursive: true });
    if (fs.existsSync('/tmp/lnreader-plugins/.js/src/plugins') && fs.existsSync('/tmp/lnreader-plugins/.dist/plugins.min.json')) {
      fs.cpSync('/tmp/lnreader-plugins', REPO_DIR, { recursive: true });
    } else {
      execSync(`git clone --depth 50 --branch ${PINNED_BRANCH} ${REPO_URL} "${REPO_DIR}"`, { stdio: 'inherit' });
      try {
        execSync(`git -C "${REPO_DIR}" checkout ${PINNED_COMMIT}`, { stdio: 'inherit' });
      } catch (_) {
        execSync(`git -C "${REPO_DIR}" fetch origin ${PINNED_COMMIT} --depth 1`, { stdio: 'inherit' });
        execSync(`git -C "${REPO_DIR}" checkout ${PINNED_COMMIT}`, { stdio: 'inherit' });
      }
    }
  }
  return { COMPILED_PLUGINS_DIR, MANIFEST_PATH, REPO_DIR };
}

export function getAllFiles(dir, exts = ['.js']) {
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
