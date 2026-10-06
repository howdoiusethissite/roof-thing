# Roof Thing

A Fabric mod for Minecraft 26.3 that roofs your house for you. Mark the top of your walls with a
wand, hold some stairs (and matching slabs), and it builds the whole roof in one go.

## Using it

Craft the **Roofing Wand** (stick, copper ingot and a stick on a diagonal), or find it in the
creative Tools tab.

```
. . C        C = copper ingot
. S .        S = stick
S . .
```

1. **Right-click the top block of a wall corner**, then the **opposite corner**. Green particles
   outline the section. The first corner you click decides the height, so click both on the top
   wall block.
2. For a wing or an annex, mark another pair of corners. Every section can have its own height and
   its own style.
3. **Right-click the air** to build. The roof uses the stairs in your off-hand first, otherwise the
   first stairs it finds in your inventory, plus the slab of the same material for ridges.

| Action | What it does |
| --- | --- |
| Right-click a block | Set a corner (every second click finishes a section) |
| Right-click air | Build the roof |
| Sneak + right-click air | Cycle the roof style for the next section |
| Sneak + right-click a block | Remove the pending corner, or the last section |

Commands: `/roof build`, `/roof undo` (last 5 roofs, materials returned), `/roof clear`,
`/roof style <gable|gable_rotated|hip>`, `/roof overhang <0-3>`, `/roof info`.

## How it handles different rooms

Each section gets its own roof, and the roofs are merged by taking whichever is highest at every
spot. A lower wing therefore runs into the main roof and meets it in a valley with proper inner
corner stairs, and a wide wing can rise above a narrow main block. A gable wing built against
another section keeps its ridge going until it reaches the other roof.

Styles: `gable` (ridge along the long side), `gable_rotated` (ridge along the short side) and `hip`
(all four sides slope). Odd widths get a slab ridge, even widths meet in a stair peak.

## Notes

* Survival players need all the stairs and slabs up front. If anything is missing nothing is placed
  and you are told exactly how many more you need. Creative players use no items.
* Existing solid blocks are left alone, as is anything protected from you.
* The triangular gable walls are not filled in. Build those yourself, or use a hip roof.
* The selection is kept in memory only, and is gone when the server restarts.

## Building

Needs JDK 25 (Minecraft 26.x requires it).

```
./gradlew build      # jar ends up in build/libs
./gradlew test       # geometry tests, including a check against vanilla's stair shapes
./gradlew runServer  # dev server
```
