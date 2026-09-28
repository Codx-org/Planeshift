package codx.planeshift.crossing;

import org.jspecify.annotations.Nullable;

import codx.planeshift.Planeshift;
import codx.planeshift.network.CrossNet;
import codx.planeshift.plane.Plane;
import codx.planeshift.registry.Planes;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Checks a client's claim to have crossed a plane, and follows it if it holds.
 *
 * <p>The client moves first so that a crossing costs no round trip, which means the
 * server has to assume the claim is a lie until it checks. Everything a client could
 * gain by lying is checked here: it can only name a plane the server knows, in its own
 * dimension, and only claim a movement that starts where the server already believes
 * the player is and genuinely passes through that plane. A claim that fails is refused
 * and the client puts itself back.
 */
public final class CrossingClaims {
	/** How far the claimed start may be from where the server has the player. */
	private static final double MAX_DRIFT = 2.0;

	private CrossingClaims() {
	}

	public static void claim(ServerPlayer player, CrossNet.Cross cross) {
		String refused = refusal(player, cross);

		if (refused != null) {
			Planeshift.LOGGER.info("Refused a crossing by {} through plane #{}: {}",
					player.getName().getString(), cross.planeId(), refused);
			codx.planeshift.debug.TraceLog.line("        server REFUSED plane #" + cross.planeId()
					+ ": " + refused);
			ServerPlayNetworking.send(player, new CrossNet.Refused(cross.planeId(), refused));
			return;
		}

		Plane plane = Planes.store(player.level()).byId(cross.planeId());
		PlaneTransfer.carry(player, plane, cross.to(), cross.velocity(), cross.yRot(), cross.xRot());
	}

	private static @Nullable String refusal(ServerPlayer player, CrossNet.Cross cross) {
		ServerLevel here = player.level();
		Plane plane = Planes.store(here).byId(cross.planeId());

		if (plane == null) {
			return "no such plane here";
		}

		if (plane.transform().isEmpty()) {
			return "that plane leads nowhere";
		}

		if (here.getServer().getLevel(plane.transform().orElseThrow().target()) == null) {
			return "that dimension is not loaded";
		}

		if (player.isPassenger() || player.isVehicle()) {
			return "riding";
		}

		// The claim has to start where the server already has the player, or it is a
		// teleport wearing a crossing's clothes.
		if (cross.from().distanceToSqr(player.getEyePosition()) > MAX_DRIFT * MAX_DRIFT) {
			return "claims to start at " + cross.from() + ", server has " + player.getEyePosition();
		}

		if (CrossingDetector.test(plane, cross.from(), cross.to()) == null) {
			return "that movement does not pass through the plane";
		}

		return null;
	}
}
