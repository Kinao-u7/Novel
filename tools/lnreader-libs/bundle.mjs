import * as esbuild from 'esbuild';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const outDir = path.resolve(__dirname, '../../app/src/main/assets/js');
if (!fs.existsSync(outDir)) {
  fs.mkdirSync(outDir, { recursive: true });
}

const outFile = path.join(outDir, 'libs.js');

console.log(`Building libs.js to ${outFile}...`);

await esbuild.build({
  entryPoints: [path.join(__dirname, 'entry.mjs')],
  bundle: true,
  minify: true,
  format: 'iife',
  platform: 'browser',
  mainFields: ['module', 'main'],
  target: ['es2020'],
  outfile: outFile,
});

console.log(`Successfully built libs.js (${fs.statSync(outFile).size} bytes)`);
