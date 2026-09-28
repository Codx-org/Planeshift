package codx.planeshift.client.interact;

import codx.planeshift.client.DestinationWorld;
import codx.planeshift.plane.Plane;

import net.minecraft.world.phys.BlockHitResult;

/**
 * Something on the other side of a plane that the player is pointing at.
 *
 * <p>Held for the tick it was picked in. Everything that wants to act on it — the
 * outline, a break, a use — needs the same three things: which plane the reach passed
 * through, where it landed over there, and how far away it really is, measured along the
 * path the ray took rather than as the crow flies.
 *
 * <p>The plane's id is kept beside the plane itself. Telling the server about this means
 * naming the plane, and the list the plane came from is replaced wholesale whenever the
 * server re-sends it — so looking the id up again later can quietly fail, and an action
 * that quietly fails looks like a mod that sometimes does not work.
 *
 * @param planeId  the plane, as the server names it
 * @param plane    the plane the reach passed through
 * @param world    the far side, as this client holds it
 * @param hit      where the ray landed, in the far side's coordinates
 * @param distance how far along the ray, so reach can be judged as if the plane were glass
 */
public record FarHit(int planeId, Plane plane, DestinationWorld world, BlockHitResult hit, double distance) {
}
