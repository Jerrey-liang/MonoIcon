#!/usr/bin/env node
// Requires sharp (no Android runtime dependency). Example:
// node tools/render_lawnicons_masks.cjs --sharp /path/to/node_modules/sharp
// --source-dir reuses downloaded SVGs; --verify checks bundled PNG hashes only.
'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
const bundle = path.join(root, 'app/src/main/assets/lawnicons');
const manifestPath = path.join(bundle, 'raster-sources.json');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const args = process.argv.slice(2);
const option = name => args.includes(name) ? args[args.indexOf(name) + 1] : null;

async function main() {
  const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'));
  const names = (await fs.readdir(path.join(bundle, 'masks')))
    .filter(name => name.endsWith('.png')).sort();
  const expected = Object.keys(manifest.assets).map(name => `${name}.png`).sort();
  if (JSON.stringify(names) !== JSON.stringify(expected)) {
    throw new Error('Bundled assets differ from the pinned source manifest');
  }
  const verify = args.includes('--verify');
  const sharp = verify ? null : require(option('--sharp') || 'sharp');
  const sourceDir = option('--source-dir');
  for (const filename of names) {
    const name = filename.slice(0, -4);
    const record = manifest.assets[name];
    const destination = path.join(bundle, 'masks', filename);
    if (verify) {
      const png = await fs.readFile(destination);
      if (png.readUInt32BE(16) !== manifest.size || png.readUInt32BE(20) !== manifest.size ||
          hash(png) !== record.pngSha256) {
        throw new Error(`Bundled mask hash or dimensions differ: ${name}`);
      }
      continue;
    }
    let svg;
    if (sourceDir) {
      svg = await fs.readFile(path.join(sourceDir, `${name}.svg`));
    } else {
      const response = await fetch(`${manifest.sourceBase}/${name}.svg`);
      if (!response.ok) throw new Error(`SVG download failed: ${name} (${response.status})`);
      svg = Buffer.from(await response.arrayBuffer());
    }
    if (hash(svg) !== record.svgSha256) throw new Error(`Pinned SVG hash differs: ${name}`);
    // Rasterize the original vector at high density before the final downsample.
    // Never enlarge the previous 215px mask: that cannot recover source detail.
    const png = await sharp(svg, { density: manifest.density })
      .resize(manifest.size, manifest.size, { fit: 'contain', background: '#00000000' })
      .png().toBuffer();
    if (hash(png) !== record.pngSha256) {
      throw new Error(`Raster differs for ${name}; use sharp ${manifest.renderer.sharp}, ` +
        `librsvg ${manifest.renderer.rsvg} and review differences before updating the manifest`);
    }
    await fs.writeFile(destination, png);
  }
  console.log(`${verify ? 'Verified' : 'Rendered'} ${names.length} pinned Lawnicons masks at ${manifest.size}px`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
