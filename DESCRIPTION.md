# Planeshift

**Planeshift is a library.** On its own it adds nothing you can see — no blocks, no items, no
portals of its own. What it gives other mods is a way to join two places with a **portal
plane**: a surface you can look through and walk through as if the two worlds were one space.

## What a portal plane does

- **You see through it.** The far side is drawn live through the opening, with its own
  terrain, sky, lighting and entities. Not a skybox, not a still image. It fades out with
  distance rather than switching off at a line.
- **You walk through it.** No loading screen, no camera snap, no lost momentum. Walk, fall or
  sprint across and just keep going — a long fall stays a long fall.
- **You do not have to dig for it.** The blocks a doorway is drawn across let everything
  through while it is there, and are whole again the moment it moves, so a mod can put a
  portal on a wall without taking the wall apart.
- **Things cross with you.** Mobs, items and anything else that wanders in. Water and lava
  flow through rather than stopping at it.
- **You can reach through it.** Break, place and use blocks on the far side as if they were
  in front of you.

A plane can be a finite rectangle — a doorway in a wall, a floor, a ceiling — or infinite,
joining two worlds along an entire horizontal layer.

## Mods built on it

Three, deliberately unalike, because a library that fits one case is not a library:

- **Actual Portals** — nether portals you can see through and step through, with no black
  screen. Planes found in the world, made of blocks that come and go.
- **OneDimension** — every world stacked into one column, joined floor to sky and looping
  back to the top. Infinite planes, derived from where the worlds end.
- **The Portal Gun** — shoot two linked doorways onto any surface and walk between them.
  Planes made and unmade at will, at whatever angle you shot them.

## For mod developers

Say where a plane is; Planeshift draws the far side, streams it, and carries things across.

```java
Planes.registerInChunks((level, chunk, out) ->
    out.accept(PlaneBoundary.linking(
        Level.OVERWORLD, doorHere,  Direction.NORTH,
        Level.NETHER,    doorThere, Direction.SOUTH).a()));
```

Planes can be supplied per chunk (for ones built out of blocks, which come and go), per
dimension, or globally for ones you simply remember. What a doorway *looks* like is yours
too: give a plane a name and register something on the client that draws it.

The full guide is **[USING-PLANESHIFT.md](USING-PLANESHIFT.md)**.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.19.5 or newer
- Fabric API

## Configuration

`config/planeshift.json` holds both halves. The server's: whether entities and fluids cross,
whether a portal's blocks can be broken through it, and how many far sides it will stream to
one player. The client's: how many planes may show their far side at once, how far away they
stay visible, how quickly that view fades, and how much detail a distant one is drawn with.

## Status

Under active development. Seeing through, crossing, interacting and carrying entities all
work. Expect rough edges around lighting at the seam, and a portal seen through another
portal shows a flat colour rather than going on forever.
