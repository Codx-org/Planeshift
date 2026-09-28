package codx.planeshift.crossing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import codx.planeshift.plane.Plane;
import codx.planeshift.registry.PlaneStore;
import codx.planeshift.registry.Planes;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Notices when a player's movement takes them through a plane.
 *
 * <p>Compares each player's position against where they were last tick, so the unit of
 * detection is one tick of movement rather than one call to {@code Entity.move}. That
 * is enough precision to decide <em>whether</em> a plane was crossed and <em>which</em>
 * one; sub-tick accuracy would need a mixin on {@code Entity.move}, and is only worth
 * it once entities other than players need to cross.
 *
 * <p>It is the <em>eye</em> that crosses, not the feet. The client decides its own
 * crossings by where it is looking from, and the two have to agree or they fire at
 * different moments: for a plane lying flat they are a metre and a half apart, so the
 * feet would cross first and the server would carry the player off before the client
 * could claim it.
 */
public final class CrossingDetector {
	/**
	 * Movement longer than this is treated as a teleport and ignored. A teleport is
	 * just a large {@code from} to {@code to} jump to this code, so without the check
	 * a {@code /tp} across the world would "cross" every plane on the line between.
	 */
	private static final double MAX_STEP = 16.0;

	private static final Map<UUID, LastSeen> LAST = new HashMap<>();

	private CrossingDetector() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(CrossingDetector::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
				LAST.remove(listener.player.getUUID()));
	}

	/**
	 * Restarts a player's movement history at where they are now. Called after a
	 * transfer, so the next tick does not measure a segment they never travelled.
	 */
	public static void resync(ServerPlayer player) {
		LAST.put(player.getUUID(), new LastSeen(player.level().dimension(), player.getEyePosition()));
	}

	private static void tick(net.minecraft.server.MinecraftServer server) {
		// A copy: a listener may teleport the player, and iterating the live list while
		// that happens is asking for trouble.
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			if (!player.isAlive()) {
				// Where a dead player lies is not a journey; see the client's own guard.
				LAST.remove(player.getUUID());
				continue;
			}

			ServerLevel level = player.level();
			Vec3 to = player.getEyePosition();
			LastSeen last = LAST.put(player.getUUID(), new LastSeen(level.dimension(), to));

			if (last == null || !last.level().equals(level.dimension())) {
				// First tick seen, or they changed dimension: there is no movement
				// within one level to test.
				continue;
			}

			if (last.position().distanceToSqr(to) > MAX_STEP * MAX_STEP) {
				continue;
			}

			Crossing crossing = nearest(Planes.store(level), last.position(), to);

			if (crossing != null) {
				PlaneCrossingCallback.EVENT.invoker().onCross(player, crossing);
			}
		}
	}

	/**
	 * The first plane crossed by a movement, or null if none was. Nearest rather than
	 * first found, so which plane wins does not depend on provider iteration order.
	 */
	public static @Nullable Crossing nearest(PlaneStore store, Vec3 from, Vec3 to) {
		Crossing[] best = new Crossing[1];

		store.forEachCandidate(from, to, plane -> {
			Crossing crossing = test(plane, from, to);

			if (crossing != null && (best[0] == null || crossing.fraction() < best[0].fraction())) {
				best[0] = crossing;
			}
		});

		return best[0];
	}

	/**
	 * Tests one plane against one movement.
	 *
	 * <p>A point's side of the plane is {@code sign * (coordinate - surface)}, and a
	 * crossing is that sign changing. Planes are two-way, so both directions count.
	 *
	 * <p>Ending exactly on the surface counts as crossed; starting there does not. That
	 * asymmetry is deliberate and applies to both directions: it stops an entity left
	 * sitting on a plane from re-triggering every tick.
	 */
	public static @Nullable Crossing test(Plane plane, Vec3 from, Vec3 to) {
		Direction facing = plane.facing();
		Direction.Axis axis = facing.getAxis();
		int sign = facing.getAxisDirection().getStep();
		double surface = plane.surface();

		double before = sign * (from.get(axis) - surface);
		double after = sign * (to.get(axis) - surface);

		boolean fromFacingSide = before > 0 && after <= 0;
		boolean fromBehind = before < 0 && after >= 0;

		if (!fromFacingSide && !fromBehind) {
			return null;
		}

		double fraction = before / (before - after);
		Vec3 point = from.lerp(to, fraction);
		return plane.contains(point) ? new Crossing(plane, point, fraction, fromFacingSide) : null;
	}

	private record LastSeen(ResourceKey<Level> level, Vec3 position) {
	}
}
