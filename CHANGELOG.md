# Changelog

## 0.1.0+26.3

First release.

- **Portal panel**: where a portal leads, which portal it links to (or where a new one will be made),
  whether the way back returns here, and warnings for mislinks, one-way links, wide portals that reach
  different exits, ties and the world border. Key **U**: auto / always / off.
- **Markers** on remembered portals: green both ways, red one way, purple no partner, with names.
- `/waygate plan`: where to build a partner so the pair links both ways, and which portals would get in
  the way. Also for portals you have not built yet (`/waygate plan x y z [x|z]`).
- `/waygate name`, `list`, `pairs` (screen with every link), `forget`, `config` (also in Mod Menu).
- Singleplayer: exact, from the built-in server, including portals you never saw and the exact spot of a
  new portal. Multiplayer: from the portals you have seen, remembered per server and dimension.
- Follows the 26.3 rules: 16/128-block search squares at any height, closest block in 3D then lowest,
  per-dimension world border, datapack dimension scales.
