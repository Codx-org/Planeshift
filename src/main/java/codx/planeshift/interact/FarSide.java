package codx.planeshift.interact;

import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Where a player's hand is, when they are reaching through a plane.
 *
 * @param plane the plane being reached through
 * @param level the level on the other side of it
 * @param eye   where the player's eye maps to over there, which is what reach is measured
 *              from once the ray has crossed
 */
public record FarSide(Plane plane, PlaneTransform transform, ServerLevel level, Vec3 eye) {
}
