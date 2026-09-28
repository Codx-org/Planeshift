package codx.planeshift.client.interact;

import org.jspecify.annotations.Nullable;

import codx.planeshift.client.ClientPlanes;
import codx.planeshift.client.DestinationInbox;
import codx.planeshift.client.DestinationWorld;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.IdentifiedPlane;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Reaching through a plane to the world on the other side.
 *
 * <p>A plane is a hole, so a reach that passes through one should carry on rather than
 * stop at it. The ray is cut where it meets the surface, carried across by the same
 * transform a crossing uses, and continued in the far side for whatever reach is left.
 *
 * <p>Distance is measured along the whole path — up to the opening here, then onward
 * there. A block a metre past the plane is a metre away, however far apart the two worlds
 * are in their own coordinates.
 */
public final class PlaneReach {
	private PlaneReach() {
	}

	/**
	 * What the player is pointing at on the far side, if anything.
	 *
	 * @param stopAt how far the ray already travelled before hitting something in this
	 *               world, so a wall in front of the plane still wins
	 */
	public static @Nullable FarHit pick(Minecraft client, Vec3 from, Vec3 direction,
			double reach, double stopAt) {
		if (client.level == null || client.player == null) {
			return null;
		}

		IdentifiedPlane crossed = null;
		double atPlane = stopAt;

		for (IdentifiedPlane identified : ClientPlanes.all()) {
			Plane plane = identified.plane();

			if (!plane.isPortal()) {
				continue;
			}

			double distance = meets(plane, from, direction, atPlane);

			if (distance >= 0.0) {
				crossed = identified;
				atPlane = distance;
			}
		}

		if (crossed == null) {
			return null;
		}

		Plane plane = crossed.plane();
		PlaneTransform transform = plane.transform().orElseThrow();
		DestinationWorld world = DestinationInbox.forPlane(crossed.id(), transform.target());

		if (world == null) {
			return null;
		}

		// Just past the surface, so the ray starts on the far side of it rather than in
		// the plane itself.
		Vec3 opening = from.add(direction.scale(atPlane + 1.0E-4));
		Vec3 start = transform.position(opening, plane.anchor());
		Vec3 heading = transform.velocity(direction);
		Vec3 end = start.add(heading.scale(reach - atPlane));

		BlockHitResult hit = world.level().clip(new ClipContext(start, end,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));

		if (hit.getType() == HitResult.Type.MISS) {
			return null;
		}

		return new FarHit(crossed.id(), plane, world, hit, atPlane + hit.getLocation().distanceTo(start));
	}

	/**
	 * How far along the ray it meets this plane's surface, or -1 if it does not do so
	 * within {@code limit} and inside the opening.
	 */
	private static double meets(Plane plane, Vec3 from, Vec3 direction, double limit) {
		Direction.Axis axis = plane.facing().getAxis();
		double along = direction.get(axis);

		if (Math.abs(along) < 1.0E-9) {
			return -1.0;
		}

		double distance = (plane.surface() - from.get(axis)) / along;

		if (distance < 0.0 || distance >= limit) {
			return -1.0;
		}

		return plane.contains(from.add(direction.scale(distance))) ? distance : -1.0;
	}
}
