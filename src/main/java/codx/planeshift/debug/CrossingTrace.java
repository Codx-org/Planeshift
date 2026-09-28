package codx.planeshift.debug;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import codx.planeshift.network.TracePayload;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Where the server thinks a player is, every tick.
 *
 * <p>Half of a pair: the client sends its own account of the same tick through
 * {@code ClientNote}, so both land in this one log, stamped with the same game time. A
 * crossing that comes undone shows up as the two disagreeing about which dimension the
 * player is in, and the line after says who won.
 *
 * <p>Debug scaffolding: it goes when crossings are reliable.
 */
public final class CrossingTrace {
	private static final Set<UUID> WATCHING = new HashSet<>();

	/** Wall clock at the previous tick, so a server that stalls shows it too. */
	private static long last;

	private CrossingTrace() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(CrossingTrace::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
				WATCHING.remove(listener.player.getUUID()));
	}

	/** Starts or stops tracing this player, on both sides. Returns the new state. */
	public static boolean toggle(ServerPlayer player) {
		boolean on = !WATCHING.remove(player.getUUID());

		if (on) {
			WATCHING.add(player.getUUID());
		}

		if (on) {
			TraceLog.open(player.level().getServer().getServerDirectory()
					.resolve("planeshift-trace-server.log"));
		} else {
			TraceLog.close();
		}

		ServerPlayNetworking.send(player, new TracePayload(on));
		return on;
	}

	private static void tick(MinecraftServer server) {
		if (WATCHING.isEmpty()) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!WATCHING.contains(player.getUUID())) {
				continue;
			}

			Vec3 at = player.position();
			Vec3 moving = player.getDeltaMovement();
			// A position the server is holding out for: until the client acknowledges it,
			// every move it sends is thrown away.
			Vec3 holding = ((codx.planeshift.mixin.ServerGamePacketListenerAccessor) player.connection)
					.planeshift$awaitingPositionFromClient();
			long now = System.currentTimeMillis();
			long since = last == 0L ? 50L : now - last;
			last = now;
			TraceLog.line(String.format("t=%d server %s feet %.3f %.3f %.3f vel %.3f onGround %b%s"
							+ " since-last %dms%s",
					player.level().getGameTime(), player.level().dimension().identifier(),
					at.x, at.y, at.z, moving.y, player.onGround(),
					holding == null ? "" : " AWAITING-ACK " + holding + " id "
							+ ((codx.planeshift.mixin.ServerGamePacketListenerAccessor) player.connection)
									.planeshift$awaitingTeleport(),
					since, since > 100 ? "  <<< STALL" : ""));
		}
	}

}
