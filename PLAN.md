# Waygate — plan

Client-side Fabric mod for Minecraft 26.3 (id `waygate`, package `dev.waygate`, command `/waygate`).
Shows where a nether portal will lead before you step through it, warns about mislinks, and tells you
where to build the partner portal. Shared rules: `deepseek-harness/default-workspace/mod-prompts/COMMON.md`.

Name check (2026-11-12): Modrinth slug `waygate` free (API 404), `github.com/skaffa/waygate` free.
One unrelated Modrinth mod uses "Waygate" as an in-game word in its description; no project title clash.

---

## 1. Vanilla 26.3 rules, read from decompiled code

Source: Vire's Loom cache `minecraft-common-1b0498e74c-26.3-sources.jar`. Classes: `NetherPortalBlock`,
`PortalForcer`, `PoiManager`, `WorldBorder`, `DimensionType`, `PortalShape`, `Entity`, `BlockPos`,
`ServerLevel`, `PoiTypes`.

### 1.1 Which dimension
`NetherPortalBlock.getPortalDestination`: from `Level.NETHER` go to `Level.OVERWORLD`, from anything else go
to `Level.NETHER`. The level keys are hard-coded. A portal in a custom dimension leads to the nether. A
datapack can change the *dimension types* of the overworld and nether (scale, height), not the pairing.

### 1.2 Target point (`approximateExitPos`)
```
scale = DimensionType.getTeleportationScale(fromType, toType) = from.coordinateScale / to.coordinateScale
exit  = toLevel.worldBorder.clampToBounds(entity.x * scale, entity.y, entity.z * scale)
      = BlockPos.containing(clamp(x*scale, border.minX, border.maxX - 1e-5),
                            y,
                            clamp(z*scale, border.minZ, border.maxZ - 1e-5))
```
- Vanilla scales: overworld 1, nether 8. So overworld to nether ×1/8, nether to overworld ×8. Datapack
  `coordinate_scale` range is 1e-5 to 3e7; the mod uses the real values, not 8.
- It uses the **entity's exact position**, not the portal block. `containing` floors. So a 2-wide nether
  portal maps to a target range up to 16 blocks wide in the overworld. The panel shows the target for the
  player's current spot and the range over the whole portal.
- **Y is not scaled and not clamped** for the search. The prompt said "y clamped to the other
  dimension's limits": the code does not do that. Y only gets clamped when a new portal is created (1.5).
  The panel shows the unclamped search Y.
- The border is the **target dimension's own border** (`ServerLevel.getWorldBorder()` reads per-level
  data storage), clamped before the search.

### 1.3 Search for an existing portal (`PortalForcer.findClosestPortalPosition`)
- Radius: `toNether ? 16 : 128`. Confirmed: 16 in the nether, 128 in the overworld (any non-nether target).
- Shape: `PoiManager.getInSquare` = horizontal square `|dx| <= r && |dz| <= r` (so 33×33 or 257×257
  columns), **all Y** of the level. Before it, `ensureLoadedAndValid` loads the POI sections of chunks in
  `floorDiv(r,16)` chunk radius.
- Candidates: every `nether_portal` POI. Each portal **block** is its own POI (`PoiTypes`: all states of
  `Blocks.NETHER_PORTAL`), so a big portal is many candidates.
- Filters: inside the target border (`isWithinBounds`: `x >= minX && x < maxX`, same for z), and the
  block there still has `HORIZONTAL_AXIS` (still a portal block).
- Choice: `min` by `distSqr(exit)` (full 3D squared distance, block positions), then by lower Y. Equal
  distance and Y: first in stream order (chunk order, then section, then POI section order). Waygate
  reports such a tie as "ambiguous" instead of guessing.
- Then the exit is `BlockUtil.getLargestRectangleAround(found, axis, 21, Y, 21, same state)`: the
  biggest rectangle of identical portal blocks around the chosen block, max 21×21.

### 1.4 Landing spot (for "you will arrive at")
`getDimensionTransitionFromExit` + `createDimensionTransition`: relative position in the entry portal
(`PortalShape.getRelativePosition`, 0..1 across the width and up the height, using the entity's
dimensions) is mapped onto the exit rectangle. Rotation +90° if the axes differ. Then
`PortalShape.findCollisionFreePosition`. Waygate shows the exit rectangle and an approximate landing
point; it does not promise the exact collision-free point.

### 1.5 New portal when none found (`PortalForcer.createPortal`)
Only if no candidate; spectators get no teleport at all. Axis = entry portal block's axis (default X).
- `maxPlaceableY = min(level.maxY, level.minY + logicalHeight - 1)` (nether: 127).
- Spiral over columns within **radius 16** of the exit (`BlockPos.spiralAround`, EAST then SOUTH),
  starting from `min(maxPlaceableY, MOTION_BLOCKING heightmap)` and scanning down. Column must be in the
  border, also one block along the portal direction.
- A spot needs an air/replaceable, fluid-free gap; floor at `y` with `y + 4 <= maxPlaceableY`; the gap
  above the floor run is 0 or ≥ 3; `canHostFrame` = solid blocks under the 4-wide frame, replaceable
  4×4 above. "Full" spot = also the two side rows (3-deep platform). Closest full spot by 3D distSqr to
  the exit wins (strict `>`, so the first in spiral order wins ties); if no full spot, closest partial.
- Nothing fits: forced portal at `(x - stepX, clamp(y, max(minY+1, 70), maxPlaceableY - 9), z - stepZ)`,
  clamped to the border, with a 3×2 obsidian platform and air pocket. Nether: Y 70..118. If
  `maxPlaceableY - 9 < max(minY+1,70)` (a very short custom dimension) no portal is made and the
  teleport fails.
- Frame: 4 wide × 5 high obsidian, 2×3 portal inside.

### 1.6 When the teleport happens
`Entity.setAsInsidePortal` records the portal block the entity touched (entry pos);
`PortalProcessor` counts ticks (`players_nether_portal_default_delay` / `_creative_delay` gamerules), then
`getPortalDestination` runs with the entity's position at that tick. Passengers cannot use portals.

---

## 2. What the client can know

| Fact | Singleplayer / LAN host | Multiplayer |
|---|---|---|
| Portals in the other dimension | exact: read POIs from the integrated server | only portals the player has seen (memory) |
| Coordinate scale of each dimension | exact: `server.getLevel(key).dimensionType()` | learned when the player is in that dimension (`ClientLevel.dimensionType()`), default 1 / 8 until seen |
| Other dimension's border | exact | learned when visited, else "border unknown" |
| Terrain for a new portal | loaded chunks of the server level | not known: "a new portal will be created near x y z" |
| Ambiguous ties | detected | detected among known portals |

- **Memory** (Gauge style): `config/waygate/portals/<world-id>/<dimension>.json`. World id = save folder
  name in SP, server address in MP. Each portal stored as its rectangle (min corner, axis, width,
  height) plus optional name, first/last seen. Portal blocks found by scanning loaded chunk sections with
  `LevelChunkSection.maybeHas(nether_portal)`, on chunk load and on block updates; a remembered portal
  whose blocks are seen gone is removed.
- From memory, all candidate blocks of a rectangle are known, so the 3D closest-block choice is exact
  for known portals.
- Every prediction from memory says **"based on N known portals — unknown portals may exist"**. In
  multiplayer the panel says **"Multiplayer: remembered portals only"**.
- **Singleplayer peek** (Gauge's `ServerPeek`): on the server thread, read `PoiManager.getInChunk` for the
  square (reads POI data from disk if needed, no chunk generation). The axis check is only done for
  loaded chunks; POIs in unloaded chunks are trusted (vanilla would load them). We do **not** call
  `ensureLoadedAndValid` or `getBlockState` on unloaded chunks, so Waygate never loads or generates
  chunks. New-portal spot simulation runs only if all 33×33 columns are loaded; else "near x y z".

---

## 3. Features

- **HUD panel** (when standing in a portal or looking at one within ~32 blocks; toggle key `U`,
  rebindable; modes auto / always / off):
  - target in the other dimension: point for the player's spot, and the range for the whole portal;
  - "links to <name or x y z> (distance d)" or "new portal will be created near x y z" (+ "at x y z" when
    simulated exactly);
  - source: "exact (singleplayer)" or "from N remembered portals";
  - **warnings**: mislink ("an older portal at … is closer to the target than …"), the target range
    spans different exits (part of the portal links elsewhere), ambiguous tie, target clamped by border,
    border unknown, new portal would be forced (no space).
- **`/waygate plan`**: for the portal you look at (or your position), where to build the partner so it
  links both ways: the target point, the acceptable region (partner must be the closest candidate to
  this portal's target, and this portal must be the closest to the partner's target), and which known
  portals would interfere. `/waygate plan <x> <y> <z>` for a portal not yet built.
- **Markers**: known portals drawn with vanilla `Gizmos` (outline + name label) when near; `/waygate
  list` lists them per dimension with distance.
- **Names**: `/waygate name <name>` names the portal you look at / stand in; `/waygate unname`.
- **Pairing view**: `/waygate pairs` (chat list, and a screen with the same table): for each known
  portal, its predicted exit in the other dimension, and whether that exit links back (A→B and B→A).
  Broken ones are marked in red with the reason.
- `/waygate help`, `/waygate config` (settings screen, Mod Menu entry), `/waygate forget [here|all]`.
- Settings: HUD mode and position, marker range, marker colours, show range, look distance.

## 4. Code layout

- `dev.waygate.core` (Minecraft-free, JUnit 5): `PortalMath` (scale, border clamp, containing),
  `PortalSearch` (square filter, 3D closest + Y, tie detection), `PortalRect`, `CreatePortalSim` (spiral +
  frame rules on an abstract block-query interface), `PairingCheck`, `PortalStore` (JSON memory),
  `ConfigStore`.
- `dev.waygate.client`: `PortalScanner` (chunk scan, memory updates), `ServerPeek` (SP exact),
  `Predictor` (feeds core with known or exact data), `WaygateHud`, `Markers`, `Commands`,
  `ConfigScreen`, `ModMenuIntegration`, `dev/SelfTest`.

## 5. Milestones (stop and report after each)

1. **Skeleton + core maths.** Gradle project (copied from Gauge/Vire), `fabric.mod.json`, icon,
   CI workflow, `core` with `PortalMath`, `PortalSearch`, `PairingCheck`, `CreatePortalSim` and unit
   tests: scaling both ways and custom scales, floor of negatives, border clamp (`maxX - 1e-5`), the
   16/128 square edges, 3D closest + lower-Y tie break, ambiguous ties, big portals, both-ways pairing.
2. **Memory + scanner + SP peek.** Remember portals per world/dimension, learn scales and borders,
   ServerPeek on the integrated server. Scales, borders and heights are learned per visit (the client
   does not know which dimension type the other level uses); in singleplayer they come from the server.
   Done 2026-11-12: `tools/m2.txt` (singleplayer) and `tools/m2-mp.txt` (test server on port 25566,
   RCON 25576) checked in game with `/waygate debug`.
3. **HUD panel + warnings + markers.** Screenshots of panel, warning and markers, looked at.
4. **Commands: plan, name, list, pairs (+ screen), config screen.**
   Done 2026-11-12: `tools/m4.txt`; `plan` retries by itself while the integrated server answers.
5. **Self-test** (`-Pselftest=portals`) on the test server (copied `run-server/`): builds with commands in
   both dimensions a correct pair, a mislinked pair (older portal closer to the target), and a portal
   with no partner; records Waygate's prediction, walks the player through each portal (teleport into
   the portal block, wait for the dimension change by condition), compares the real arrival with the
   prediction, logs `SELFTEST PASS/FAIL`. Also an SP run for the exact path.
   Done 2026-11-12: `./gradlew runClient -Pselftest=portals` passes in singleplayer (new portal at the
   exactly simulated spot) and on the test server (memory only). Waits use server game time; after an
   arrival the player must be out of a portal for a while (10-tick cooldown that resets while inside),
   and on a lagging server a step-in can stall once, so the test steps out and back in (logged).
6. **Delivery.** README, CHANGELOG, `media/modrinth-description.md`, gallery (1600×900) + captions.
   Ask before creating the GitHub repo, pushing, tagging.

## 6. Limits to state in the README

- Multiplayer: unknown portals, unknown terrain, and a server with a different portal rule (plugins)
  make predictions wrong; Waygate says what it is based on.
- New-portal location is exact only in singleplayer with the target area loaded.
- Entities other than the player (and passengers) are not predicted.
- No teleporting, no building, no server component.
