# Planeshift

A Fabric library for Minecraft 26.3 that joins two places with a **portal plane**: a
surface you can look through and walk through as if the two worlds were one space. No
loading screen, no camera snap, momentum preserved.

On its own it adds nothing you can see. Other mods say where the planes are; Planeshift
draws the far side through them, streams it, and carries things across.

The full developer guide is **[USING-PLANESHIFT.md](USING-PLANESHIFT.md)**; what follows is
the shape of it.

## Supplying planes

A plane has a position, a facing, a shape (a finite rectangle or an infinite surface), a
target dimension, and a transform. Planes come from a **provider**, and which kind you
register says how your planes are found:

```java
// Found in the world, chunk by chunk — planes made of blocks, which come and go.
Planes.registerInChunks((level, chunk, out) -> …);

// Answered from memory or from a rule — planes no chunk index could hold.
Planes.registerGlobal((level, out) -> …);
```

A chunk provider's answers are cached per chunk; call `Planes.invalidate(level, chunk)`
when the blocks change. A global provider's answer is cached per dimension; call
`Planes.invalidateGlobal()` when it changes.

Build both ends of a doorway from one call, so their transforms are exact inverses:

```java
PlaneBoundary.between(levelA, shapeA, facingA, levelB, shapeB, facingB, rotation);
PlaneBoundary.linking(levelA, shapeA, facingA, levelB, shapeB, facingB);  // derives the turn
```

## What a plane looks like

Planeshift draws the far side *through* an opening and nothing else. What the opening
itself looks like belongs to the mod that made it: give the plane a name, and register
something under that name on the client.

```java
// Server: the name rides along with the plane.
boundary.looking(BLUE, ORANGE);          // the two ends of one doorway
plane.looking(Identifier.of("mymod", "shimmer"));

// Client: what the name means.
PlaneAppearances.register(BLUE, (plane, client, shown) -> …);
```

A name nothing is registered under draws nothing rather than failing, so a server may run
a mod its clients do not and the doorway still works — it is simply plain.

## Walking through what is still there

A plane is crossed when an eye passes its surface, and a wall stops you a third of a block
short of its own face. So Planeshift lets everything through the blocks a **finite portal's
opening covers** — players, mobs, items, arrows — which is why a mod that puts a doorway on
a wall does not have to dig the wall away and put it back afterwards. Openings on floors and
ceilings are opened two blocks deep, because an eye falling through needs the height of a
player to reach the surface.

Edgeless planes are left alone: a whole layer of the world that nothing collides with is a
different feature with a different name.

## The three consumer mods

They are in this repository, and they exist to prove the library is general rather than
shaped around its first case. Each is one of the three ways planes can come to be, and
each README explains the API from that angle:

- **[Actual Portals](actualportals/README.md)** — see-through nether portals. Planes
  **found** per chunk, from blocks that appear and vanish.
- **[OneDimension](onedimension/README.md)** — the worlds stacked into one column. Planes
  **derived** from each world's limits, infinite and edgeless.
- **[The Portal Gun](theportalgun/README.md)** — a gun that shoots two linked doorways.
  Planes **remembered**, because nothing in the world records them.

Read whichever is closest in shape to what you are building.

## Configuration

`config/planeshift.json` sets how many planes may be seen through at once, how far away
they stay visible, and whether entities and fluids cross them.

## When a doorway shows nothing

A doorway that draws nothing looks exactly like a doorway that draws nothing, whatever the
cause — out of range, nothing streamed yet, the server turning the request down, one too
many for `planes_seen_through`, or the pass that draws it failing. All of that is decided
on your own machine and none of it is visible.

**`/planeshiftwhy`** says which it is, in chat, for every plane near you:

```
planeshift: 2 plane(s) known here, 2 of them portals; 2 shown at once; visible to 256 blocks, faded by 256; render distance 16
  #2 opening east at -29.0 139.0 -73.5, 25.5 blocks off: off screen, for 7s; watch open, 545 column(s) streamed
  #3 opening north at -3.5 139.0 -34.0, 39.5 blocks off: drawn.
```

Anybody may run it — it changes nothing and reads only your own game — and what it says
also goes to the server's log. A doorway that has shown nothing for five seconds says so in
`latest.log` on its own, without being asked.

## Status

Under active development. Seeing through, crossing and interacting all work; expect rough
edges, particularly around lighting and the seam where a fluid crosses.

## License

CC0.
