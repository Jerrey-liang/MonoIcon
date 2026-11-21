#!/usr/bin/env node
'use strict';

// Keep package visibility limited to the artwork shipped in this build.
// Run after editing index.tsv/aliases.tsv; --check verifies without writing.
const fs = require('node:fs/promises');
const path = require('node:path');
const root = path.resolve(__dirname, '..');

async function syncLawniconsQueries({ checkOnly = false } = {}) {
  const bundle = path.join(root, 'app/src/main/assets/lawnicons');
  const assets = new Set((await fs.readdir(path.join(bundle, 'masks')))
    .filter(name => name.endsWith('.png')).map(name => name.slice(0, -4)));
  const packages = new Set();
  for (const name of ['index.tsv', 'aliases.tsv']) {
    const table = await fs.readFile(path.join(bundle, name), 'utf8');
    for (const raw of table.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith('#')) continue;
      const tab = line.indexOf('\t');
      if (tab < 1 || !assets.has(line.slice(tab + 1).trim())) continue;
      const pkg = line.slice(0, tab).trim().split('/')[0];
      if (!/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*$/.test(pkg)) {
        throw new Error(`Invalid Lawnicons package: ${pkg}`);
      }
      packages.add(pkg);
    }
  }
  const file = path.join(root, 'app/src/main/AndroidManifest.xml');
  const original = await fs.readFile(file, 'utf8');
  const newline = original.includes('\r\n') ? '\r\n' : '\n';
  const block = [
    '        <!-- lawnicons-queries:start -->',
    ...[...packages].sort().map(pkg => `        <package android:name="${pkg}" />`),
    '        <!-- lawnicons-queries:end -->',
  ].join(newline);
  const marker = /        <!-- lawnicons-queries:start -->[\s\S]*?        <!-- lawnicons-queries:end -->/;
  if (!marker.test(original)) throw new Error('Lawnicons query markers are missing from AndroidManifest.xml');
  const updated = original.replace(marker, block);
  if (checkOnly && updated !== original) {
    throw new Error('Lawnicons package visibility is out of date; run node tools/sync_lawnicons_queries.cjs');
  }
  if (!checkOnly && updated !== original) await fs.writeFile(file, updated);
  return packages.size;
}

module.exports = { syncLawniconsQueries };
if (require.main === module) {
  syncLawniconsQueries({ checkOnly: process.argv.includes('--check') })
    .then(count => console.log(`Verified Lawnicons visibility for ${count} bundled packages`))
    .catch(error => { console.error(error.message); process.exitCode = 1; });
}
