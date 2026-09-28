# Using Planeshift

A guide for mod developers. Planeshift joins two places with a **portal plane** — a surface
you can see through and walk through — and leaves everything about *what a doorway is* to
you. You say where the planes are; it does the rest.

- [The shortest possible mod](#the-shortest-possible-mod)
- [Setting up](#setting-up)
- [What a plane is](#what-a-plane-is)
- [Joining two of them](#joining-two-of-them)
- [Where planes come from](#where-planes-come-from)
- [Telling the library your planes changed](#telling-the-library-your-planes-changed)
- [Geometry that will bite you](#geometry-that-will-bite-you)
- [What you get without asking](#what-you-get-without-asking)
- [What a doorway looks like](#what-a-doorway-looks-like)
- [Limits to design around](#limits-to-design-around)
- [Configuration](#configuration)
- [Finding out what went wrong](#finding-out-what-went-wrong)
- [Three worked examples](#three-worked-examples)

---

## The shortest possible mod

A doorway from one fixed spot in the Overworld to one fixed spot in the Nether, in its
entirety:

```java
public class MyMod implements ModInitializer {
    @Override
    public void onInitialize() {
        Planes.registerGlobal((level, out) -> {
            PlaneShape here = new PlaneShape.Rectangle(
                    new BlockPos(10, 64, 0), new BlockPos(10, 65, 0));
            PlaneShape there = new PlaneShape.Rectangle(
                    new BlockPos(30, 70, 0), new BlockPos(30, 71, 0));

            PlaneBoundary doorway = PlaneBoundary.linking(
                    Level.OVERWORLD, here,  Direction.WEST,
                    Level.NETHER,    there, Direction.EAST);

            if (level.dimension() == Level.OVERWORLD) {
                out.accept(doorway.a());
            } else if (level.dimension() == Level.NETHER) {
                out.accept(doorway.b());
            }
        });
    }
}
```

That is a working portal: see-through from both sides, walkable in both directions, with the
blocks it covers letting you through while it is there. Everything below is about doing it
properly.

---

## Setting up

Planeshift is a normal Fabric mod dependency. In `fabric.mod.json`:

```json
"depends": {
  "fabricloader": ">=0.19.5",
  "minecraft": "~26.3",
  "fabric-api": "*",
  "planeshift": "*"
}
```

That is a *check*, not a delivery: Fabric Loader refuses to start without Planeshift, but
nothing fetches it. Declare it as a required dependency on your Modrinth or CurseForge page
so launchers install it with your mod.

In `build.gradle`:

```groovy
dependencies {
    modImplementation "codx:planeshift:1.0.0"
}
```

If your project uses Loom's `splitEnvironmentSourceSets()` **and** you want the client-side
half of the API (the appearance registry), you need the library's client classes on your
client source set too. Ask for the jar rather than the classes folder, which is what a
split project publishes:

```groovy
dependencies {
    implementation "codx:planeshift:1.0.0"
    clientImplementation("codx:planeshift:1.0.0") {
        attributes {
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements, LibraryElements.JAR))
        }
    }
}
```

You never call an init method. Register your providers from your mod initialiser and
Planeshift asks them when it needs to.

---

## What a plane is

```java
public record Plane(PlaneShape shape, Direction facing,
                    Optional<PlaneTransform> transform,
                    Optional<Identifier> appearance)
```

**`shape`** — where the surface is and how far it goes.

```java
// A finite rectangle covering the block volume between two corners.
new PlaneShape.Rectangle(cornerA, cornerB);
new PlaneShape.Rectangle(cornerA, cornerB, inset);   // see "Geometry that will bite you"

// Edgeless, filling the level along both axes tangent to the facing.
PlaneShape.Infinite.atSurface(-64, Direction.UP);
```

**`facing`** — the plane's normal, and so *which face of its blocks the surface lies on*. A
rectangle covering block `(10, 64, 0)` with facing `WEST` has its surface on that block's
west face; with `EAST`, on its east face. Facing does not restrict travel: a plane is crossed
from either side and the same transform applies both ways.

**`transform`** — where a crossing lands, or empty.

```java
public record PlaneTransform(ResourceKey<Level> target, Vec3 offset, Rotation rotation)
```

A plane *with* a transform is a portal. One *without* is a plain surface: crossings are still
detected and reported, but nothing is carried anywhere, nothing is drawn through it, and its
blocks stay solid. That is a useful thing in itself — a portal your mod has placed but not
yet paired is exactly this, and shows as a shut face rather than as nothing at all.

**`appearance`** — an optional name, and nothing more. See
[What a doorway looks like](#what-a-doorway-looks-like).

You will rarely build a `Plane` by hand. Build the pair instead.

---

## Joining two of them

A doorway you can come back through is two planes whose transforms are exact inverses. A
block of disagreement and you walk into the Nether, walk back, and arrive somewhere you have
never been — which reads as a physics bug and is really a data one. So derive both ends from
one call:

```java
// You give the turn.
PlaneBoundary.between(levelA, shapeA, facingA, levelB, shapeB, facingB, Rotation.CLOCKWISE_90);

// The turn is worked out: walking into one comes out of the other facing away from it.
PlaneBoundary.linking(levelA, shapeA, facingA, levelB, shapeB, facingB);

// The same coordinates in another dimension, no turn.
PlaneBoundary.sameSpot(levelA, levelB, shape, facing);
```

`linking` is what you want for anything that behaves like a portal: entering a plane means
travelling against its facing, leaving the other means travelling along that one's facing,
and the rotation is whichever quarter turn maps one to the other.

```java
PlaneBoundary doorway = PlaneBoundary.linking(…);
doorway.a();   // the plane in the first dimension
doorway.b();   // the one in the second, leading back
```

Emit each end **in its own dimension**. Your provider is asked about one level at a time.

---

## Where planes come from

Planeshift takes planes from a **provider**, and there are three shapes a provider can have.
Pick by how your planes are found, not by how many there are.

### Found in the world — `registerInChunks`

For planes made of blocks, which appear and vanish and live at known coordinates. Asked one
chunk at a time and cached per chunk.

```java
Planes.registerInChunks((level, chunk, out) -> {
    // look through this chunk for whatever your doorways are made of
});

Planes.registerInChunks(Level.NETHER, (level, chunk, out) -> …);   // one dimension only
```

*Actual Portals* does this: it walks the portal blocks in a chunk and turns each lit sheet
into a plane.

### Declared once — `registerGlobal`

For planes that follow from a rule rather than from anything in the world, and for planes
you simply remember. Asked once per dimension and cached until you say otherwise.

```java
Planes.registerGlobal((level, out) -> …);                 // every dimension, including modded
Planes.registerGlobal(Level.END, (level, out) -> …);      // one dimension only
```

Note what the first form does *not* take: a dimension. It is registered for all of them,
including ones that do not exist at registration time, and the provider is handed the level
and decides what — if anything — that world gets. A mod that stacks whatever dimensions the
server turns out to have cannot name them up front, and does not have to.

*OneDimension* uses it for planes derived from each world's limits. *The Portal Gun* uses it
for planes held in a map, because nothing in the world records where somebody shot.

### Which one am I?

| Your planes are… | Provider | Invalidate with |
| --- | --- | --- |
| built out of blocks | `registerInChunks` | `Planes.invalidate(level, chunk)` |
| a rule about the world | `registerGlobal` | `Planes.invalidateGlobal()` |
| remembered in a field | `registerGlobal` | `Planes.invalidateGlobal()` |

---

## Telling the library your planes changed

Providers are cached. A chunk provider's answers are kept per chunk; a global provider's per
dimension. When the answer changes, say so — this is the only bookkeeping the API asks of
you, and forgetting it is the most common reason a doorway does not appear or goes on
existing where it used to be.

```java
Planes.invalidate(level, chunk);        // one chunk's worth
Planes.invalidateGlobal(level);         // one dimension's global planes
Planes.invalidateGlobal();              // every dimension's
```

Two habits worth having:

- **Coalesce.** A player breaking a frame changes many blocks in one tick. Collect the
  chunks and invalidate once at the end of the tick rather than once per block.
- **Invalidate the far side too.** A doorway whose partner has gone must stop being a
  doorway on *both* sides at the same moment, and the far side is usually in another
  dimension and another chunk.

Chunk unloads invalidate themselves; you do not have to.

---

## Geometry that will bite you

**A rectangle must be flat along the axis it faces.** Both corners must share their
coordinate on the facing's axis, or the `Plane` constructor throws. An upright plane is
therefore one block thick in the horizontal axis it faces; a floor or ceiling plane is one
block thick in Y, which means a *single* layer of blocks.

**`inset` decides where in those blocks the surface sits**, measured inwards from the face
the facing points out of:

```java
new PlaneShape.Rectangle(a, b, 0.0);    // on the face itself
new PlaneShape.Rectangle(a, b, 0.5);    // down the middle of a one-block-thick sheet
new PlaneShape.Rectangle(a, b, -0.01);  // a hair proud of the face
```

Which you want depends on what the blocks are:

- **The blocks are the doorway** — a lit nether portal, where the surface belongs where the
  purple sheet was. Use `0.5`.
- **The blocks are the wall you painted a doorway on.** Use a small *negative* inset. Flush
  with the wall, the opening and the wall are the same distance from the eye, and the wall
  wins the tie: your doorway becomes a picture of bricks. A hundredth of a block settles it
  and is invisible.
- **The blocks are the air in a frame.** `0.0` is right.

**`Infinite.atSurface` takes the height you mean, not the block.** A surface lies on a block
*face*, so a floor you fall through at y=-64 is the top face of the block below it. Saying
the height and letting the shape work the block out keeps that off-by-one out of your code.

---

## What you get without asking

Once a plane exists, all of this applies to it:

- **Seeing through.** The far side is rendered live from the carried-through eye and
  composited into the opening, fading out towards the end of its range.
- **Crossing.** A player's crossing is decided by their **eye** passing the surface, because
  that is what their client decides by and the two have to agree. Everything else is decided
  by its **position**, which for something falling into a flat plane is its feet — the part
  that arrives first.
- **Momentum.** Position, velocity and yaw all go through the transform. A fall that enters
  at speed leaves at speed.
- **Openings you walk through.** The blocks a **finite** portal's opening covers stop
  colliding, stop suffocating, and are treated as open air by mob pathfinding, for as long as
  the plane is there. You do not need to dig, and you do not need to put anything back.
  Through a wall the opening is one block deep; through a floor or ceiling it is ten, because
  an eye falling at speed must not land before the crossing carries it.
- **Entities and fluids**, if the server's config allows: mobs, items and projectiles cross;
  water and lava flow through rather than stopping at the surface.
- **Reaching through.** Breaking, placing, using and attacking work on the far side, with
  reach measured through the opening.
- **Straddlers.** Anything standing halfway through is drawn on both sides, and a player
  sees their own body through a doorway into their own world.

None of it needs a call. It follows from the plane existing.

---

## What a doorway looks like

Planeshift draws the far side *through* an opening and nothing else: no frame, no glow, no
sheet. What the opening looks like from outside is yours, because a nether portal and a
portal gun's doorway are the same thing to the library and should not be to a player.

Name the plane:

```java
public static final Identifier BLUE = Identifier.fromNamespaceAndPath("mymod", "blue");

plane.looking(BLUE);                       // one plane
boundary.looking(BLUE, ORANGE);            // both ends of a doorway, named separately
```

And say what the name means, from a **client** initialiser:

```java
PlaneAppearances.register(BLUE, (plane, client, shown) -> {
    // called once per client tick for every plane carrying this name
});
```

The third argument is whether the far side is being drawn through this opening right now.
False means it is showing nothing — too far, too many nearer ones, or nothing streamed behind
it yet — which is usually worth looking different rather than pretending otherwise.

Two things to know:

- **An unregistered name draws nothing** rather than failing, so a server may run a mod its
  clients do not and the doorway still works. It is simply plain.
- **Draw *outside* the opening.** The far side is composited over everything inside it after
  the world is drawn, so anything you paint within the frame is painted over. A band a few
  hundredths of a block out sits on the wall around the hole and survives.

Emitting gizmos (`net.minecraft.gizmos.Gizmos`) from that callback is the cheapest way to
draw one: the collector is drained each tick, so whatever you stop emitting disappears
without any tidying up.

---

## Limits to design around

Better to know these before you build something that needs them.

- **A transform turns about the vertical only.** Any two upright openings can be joined, and
  a floor to a ceiling — falling into one and out of the other is the same direction of
  travel. A floor to a *wall* cannot: you would arrive travelling downwards out of a sideways
  opening, which is to say inside whatever the wall stands on. Refuse the pairing, or close
  the other end, but do not make it.
- **One level deep.** A portal seen through another portal shows a flat colour rather than
  going on forever.
- **Edgeless planes are never made passable.** A whole layer of the world that nothing
  collides with is a different feature with a different name, so if your infinite plane sits
  under bedrock, something has to remove the bedrock.
- **A far side whose camera lands outside its world** — under the floor of the world above,
  most often — has no sky of its own, and borrows the fog of the world you are standing in.
  Its terrain draws; its sky does not.
- **Planes are server-owned.** The client holds a synced mirror and cannot invent one.

---

## Configuration

`config/planeshift.json` holds both halves, so a player has one file to answer "how do
doorways behave here".

Server:

| Key | Does |
| --- | --- |
| `entities_cross_planes` | whether mobs, items and projectiles are carried through |
| `fluids_flow_through_planes` | whether water and lava flow through rather than stopping |
| `portal_planes_breakable` | whether the blocks a portal is made of can be broken through it |
| `max_far_sides_per_player` | how many far sides the server will stream to one player |

Client:

| Key | Does |
| --- | --- |
| `planes_seen_through` | how many may show their far side at once; `-1` for all in range |
| `plane_visible_distance` | how far away one is worth preparing; `-1` ties it to the render distance |
| `plane_fade_distance` | how far away its view has faded to nothing |
| `plane_detail_at_range` | how much smaller a distant far side is drawn |
| `plane_color`, `plane_rainbow`, `show_plane_outlines` | the debug sheet, for planes with no appearance |

Your own mod's settings can live in the same file — `codx.planeshift.ConfigFile` reads and
writes it by merging, so several mods can share it without overwriting each other.

---

## Finding out what went wrong

```
/planeshift list                 every plane the server knows in this dimension
/planeshift debug trace          toggles a per-tick trace of the player, both sides
```

The trace goes to `planeshift-trace-client.log` and `planeshift-trace-server.log` in the run
directory and is the first thing to read when a crossing does not happen or a far side does
not draw. It says, per tick, where both sides think the player is, whether moves are being
held back, and why a plane went undrawn — "out of range", "far side streamed nothing yet",
"no far side world yet", "off screen".

Two habits that save a lot of time:

- **A plane that does not appear at all** is nearly always a missing invalidation, or a
  provider emitting the far end in the near end's dimension.
- **A doorway that shows your own sky** means the far side refused to draw. The trace says
  which reason.

`/planeshift debug stack` is a development toy that creates its own planes and clears
existing ones. Do not run it in a world you care about.

---

## Three worked examples

The mods in this repository are one of each provider shape, and each README explains the API
from that angle. Read whichever is closest to what you are building:

- **[Actual Portals](actualportals/README.md)** — planes *found* per chunk, from blocks that
  come and go. Invalidation, coalescing, and keeping two ends in step.
- **[OneDimension](onedimension/README.md)** — planes *derived* from each world's limits.
  Infinite shapes, and joining worlds that only exist at runtime.
- **[The Portal Gun](theportalgun/README.md)** — planes *remembered*, because nothing in the
  world records them. Appearances, non-destructive openings, and refusing a pairing the
  transform cannot express.
