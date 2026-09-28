package codx.planeshift.crossing;

import codx.planeshift.plane.Plane;

import net.minecraft.world.phys.Vec3;

/**
 * A plane an entity passed through during one tick's movement.
 *
 * @param plane          the plane that was crossed
 * @param point          where the movement met the plane's surface
 * @param fraction       how far along the movement that was, 0 at the start and 1 at
 *                       the end; the smallest fraction is the plane crossed first
 * @param fromFacingSide whether they entered from the side the facing points at. The
 *                       transform is the same either way, but the transfer needs to
 *                       know which way an entity was going to avoid bouncing it
 *                       straight back through the plane it just came out of
 */
public record Crossing(Plane plane, Vec3 point, double fraction, boolean fromFacingSide) {
}
