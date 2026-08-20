# Capturing real scenes and sprite compositions

Raw 4bpp tile previews are useful for finding graphics, but they are not game
screens. Mega Drive games compose them at runtime using VDP name tables, tile
attributes, palettes, scrolling and the sprite attribute table. MortalSDK ships
a BizHawk helper to capture that runtime evidence without guessing tile order.

## Requirements and use

- BizHawk 2.11 or newer, using the Genesis Plus GX core.
- A legally obtained Mega Drive ROM.

```bat
BizHawk-Capture.cmd C:\path\to\BizHawk "C:\path\to\game.bin"
```

Play normally and press **F8**. Every capture in `captures/` contains the full
`screen.png`; isolated `plane_a.png`, `plane_b.png`, `window.png` and
`sprites.png`; complete `vram.bin`, `cram.bin` and `vsram.bin` dumps; the
matching `capture.State`; and `metadata.json`.

All PNG files restore one savestate and advance the same single frame, so
animation cannot move between layers. The images identify visible composition;
the dumps retain exact tile indices, flips and palette lines for matching those
structures back to ROM resources.

Captures are ignored by Git. Do not commit ROM data, savestates or copyrighted
screenshots.
