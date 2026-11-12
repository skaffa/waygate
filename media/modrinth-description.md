**Know where a nether portal leads before you step in.** Waygate shows the target in the other dimension, which portal you will come out of, and whether the way back brings you to the same portal. It warns you when a portal is about to link to the wrong one, and tells you exactly where to build a partner so the pair links both ways.

Everything happens on your own computer: Waygate needs nothing on the server and sends no packets of its own.

## The portal panel

Stand in a portal or look at one, and a small panel shows:

- **Target**: the spot in the other dimension the game searches around.
- **Links to**: the portal you will arrive in and how far it is from the target, or "new portal near x y z" when none is in range.
- **Way back**: "returns here", or the portal the trip back would take you to instead.
- **Warnings**:
  - **Mislink**: a portal that leads back here exists, but another one is closer to the target, so you end up there.
  - **Part of this portal leads elsewhere**: wide nether portals cover many overworld blocks, and the two ends can reach different portals.
  - **Tie**: two portals at exactly the same distance; the game picks one by save order.
  - **Target moved inside the world border.**

Press **U** to switch the panel between *auto* (at a portal), *always* (also where your own spot would lead) and *off*.

## Markers and pairs

Portals you have seen get an outline and a label in the world: **green** when the link works both ways, **red** when it is one way, **purple** when it has no partner yet. Give them names with `/waygate name Home`.

`/waygate pairs` lists every remembered portal with where it leads and whether that works both ways.

## Planning a portal

`/waygate plan` tells you where to build the partner of the portal you look at: the exact block, the direction, and whether the pair would link both ways. It also names any portal that would get in the way. `/waygate plan x y z` does the same for a portal you have not built yet.

## Singleplayer and multiplayer

- **Singleplayer and LAN host**: exact. Waygate asks the built-in server, so it also knows portals you never saw, and where a new portal would be built.
- **Multiplayer**: your game only knows portals near you, so Waygate remembers every portal you have seen on each server. The panel says when a prediction is based on remembered portals only: a portal you never saw can still win.

## Accurate to the game

Waygate follows Minecraft 26.3's own rules, read from the game's code: the 16-block (to the nether) and 128-block (to the overworld) search squares at any height, the closest portal block in 3D with the lower one winning ties, each dimension's own world border, and datapack dimension scales. An in-game test walks through real portals and checks every prediction.

## Commands

- `/waygate plan [x y z [x|z]]` — where to build the partner
- `/waygate name <name>`, `/waygate unname`
- `/waygate list`, `/waygate pairs`
- `/waygate forget here|all`
- `/waygate config` — settings (also in Mod Menu)
- `/waygate help`

Needs Fabric API. Mod Menu is optional.
