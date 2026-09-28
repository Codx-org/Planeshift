package codx.planeshift.crossing;

import java.util.Set;

import codx.planeshift.Planeshift;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Relative;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Sends a player through to the far side of a plane they crossed.
 *
 * <p>Still the plain version: vanilla's teleport respawns the player client-side, so
 * there is a loading screen. Removing it is the point of the seamless level swap.
 * Position, velocity and rotation all go through the transform, so momentum survives
 * even at this stage.
 */
public final class PlaneTransfer {
	/**
	 * What a crossing says about the player's movement: leave it alone, and turn it with
	 * them. Momentum through a doorway is the whole promise of the mod, and it is kept by
	 * not touching it.
	 */
	private static final Set<Relative> KEEP_SPEED =
			Relative.union(Relative.DELTA, Set.of(Relative.ROTATE_DELTA));

	private PlaneTransfer() {
	}

	public static void init() {
		PlaneCrossingCallback.EVENT.register(PlaneTransfer::transfer);
	}

	/**
	 * Follows a crossing the client has already made: it says where it put itself, and
	 * those values are used rather than recomputed, so the two sides agree exactly.
	 * Everything here has been checked by {@link CrossingClaims}.
	 */
	public static void carry(ServerPlayer player, Plane plane, Vec3 to, Vec3 velocity, float yRot, float xRot) {
		MinecraftServer server = player.level().getServer();
		PlaneTransform transform = plane.transform().orElseThrow();
		ServerLevel target = server.getLevel(transform.target());

		if (target == null) {
			return;
		}

		// The claim is in eye coordinates, because that is what decides a crossing, but
		// a teleport takes the entity's position -- its feet. Dropping the eye height
		// afterwards is exact here: the transform only ever turns things about the
		// vertical, so vertical offsets survive it unchanged.
		Vec3 anchor = plane.anchor();
		Vec3 eye = transform.position(to, anchor);
		Vec3 feet = eye.subtract(0.0, player.getEyeHeight(), 0.0);

		ServerPlayer moved = player.teleport(new TeleportTransition(target,
				feet, transform.velocity(velocity),
				transform.yaw(yRot), xRot, TeleportTransition.DO_NOTHING));

		if (moved != null) {
			CrossingDetector.resync(moved);
			// The client crossed a round trip ago and has kept moving since; the position
			// it reports next is that far from here, and vanilla would call it illegal.
			CrossingGrace.grant(moved);
		}
	}

	private static void transfer(ServerPlayer player, Crossing crossing) {
		if (CrossingGrace.granted(player)) {
			// The client is already driving this one. It claimed the crossing and carry()
			// moved them; this is the server noticing the same step for itself a moment
			// later. Carried twice, they are put through the doorway and then put through it
			// again from the far side, which is a player bouncing between two dimensions
			// several times a second and arriving inside the floor each time.
			return;
		}

		MinecraftServer server = player.level().getServer();
		Plane plane = crossing.plane();
		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null) {
			// A plane without a transform is a surface, not a portal. Crossings are
			// still detected and reported; nothing is carried anywhere.
			return;
		}

		ServerLevel target = server.getLevel(transform.target());

		if (target == null) {
			Planeshift.LOGGER.warn("Plane leads to {}, which this server does not have", transform.target());
			return;
		}

		// The player's position, not the crossing point. The crossing point lies exactly
		// on the surface, and arriving exactly on the destination surface invites a
		// re-crossing on the next tick from nothing worse than rounding. Their actual
		// position is already past the plane, and because the transform is rigid the
		// distance past it is preserved: a third of a block through on this side comes
		// out a third of a block through on the other, still travelling.
		Vec3 anchor = plane.anchor();
		Vec3 position = transform.position(player.position(), anchor);
		float yaw = transform.yaw(player.getYRot());

		// A plane whose transform keeps the coordinates drops you wherever those
		// coordinates happen to be on the other side, which in the Nether is usually
		// inside rock. Nothing about the crossing is wrong when that happens, but it
		// looks exactly like a crossing that failed: the view arrives, the player
		// cannot move.
		if (!target.noCollision(player.getBoundingBox().move(position.subtract(player.position())))) {
			Planeshift.LOGGER.warn("{} arrives inside the world at {} {} {} in {}; they will be stuck",
					player.getName().getString(), (int) position.x, (int) position.y, (int) position.z,
					transform.target().identifier());
		}

		// Pitch is untouched: a yaw rotation does not tilt anything.
		//
		// The speed is the client's, not the server's. A player is moved by their own client
		// and the server only checks the result, so the velocity it holds for them is very
		// nearly zero — handing that back as an absolute value is what turned a two-hundred
		// block fall through a doorway into a fresh one out the other side, over and over.
		// Asking for the delta relatively adds nothing to what the client already has, and
		// ROTATE_DELTA turns it by however much the doorway turns the player, which is the
		// same rotation the transform would have applied.
		ServerPlayer moved = player.teleport(new TeleportTransition(
				target, position, Vec3.ZERO, yaw, player.getXRot(), KEEP_SPEED,
				TeleportTransition.DO_NOTHING));

		if (moved != null) {
			// Otherwise the next tick measures movement from where they were before the
			// transfer, which is a segment they never travelled.
			CrossingDetector.resync(moved);
		}
	}
}
