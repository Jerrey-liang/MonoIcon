# Lawnicons masks

The 77 PNG masks in this directory are rasterizations of the original SVG artwork
from LawnchairLauncher/lawnicons v2.18.0, commit
`74b428b747004046686cbe5e24218e958afe2eb9`:
https://github.com/LawnchairLauncher/lawnicons/tree/74b428b747004046686cbe5e24218e958afe2eb9/svgs

Copyright remains with the Lawnicons contributors. The upstream Apache-2.0
license is included as `LICENSE.txt`.

MonoIcon renders the vectors at 768×768 instead of the former 215×215. Asset
names, component aliases, artwork and the 215/320 glyph layout scale are retained.
`raster-sources.json` records the exact source and PNG hashes and renderer versions.
Regenerate with `node tools/render_lawnicons_masks.cjs --sharp /path/to/sharp`;
verify with `node tools/render_lawnicons_masks.cjs --verify` from the source tree.
