package codx.planeshift.client.debug;

import codx.planeshift.debug.TraceLog;
import codx.planeshift.network.TracePayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Where the client thinks the player is, every tick, sent to the server's log.
 *
 * <p>The other half of {@code codx.planeshift.debug.CrossingTrace}. Both lines carry the
 * same game time, so the two accounts of one tick sit together: a step the server undoes
 * reads as the client naming one dimension and the server the other, and then the client
 * agreeing again a tick later.
 *
 * <p>Debug scaffolding: it goes when crossings are reliable.
 */
public final class CrossingTrace {
	private static boolean watching;

	/** Wall clock at the previous tick, so a client that stalls shows it. */
	private static long last;

	private CrossingTrace() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(TracePayload.TYPE,
				(payload, context) -> {
					watching = payload.on();

					if (watching) {
						TraceLog.open(context.client().gameDirectory.toPath()
								.resolve("planeshift-trace-client.log"));
					} else {
						TraceLog.close();
					}
				});
	}

	public static void tick(Minecraft client) {
		if (!watching || client.player == null || client.level == null) {
			return;
		}

		Vec3 at = client.player.position();
		Vec3 moving = client.player.getDeltaMovement();
		long now = System.currentTimeMillis();
		long since = last == 0L ? 50L : now - last;
		last = now;
		// The camera the last frame drew entities against. It is the frame's, not the tick's,
		// but a camera that has stopped following the player shows up either way — and an
		// entity drawn against the wrong one is the whole of "everything moves with me".
		Vec3 drawnFrom = client.gameRenderer.gameRenderState().levelRenderState
				.cameraRenderState.pos;
		TraceLog.line(String.format("t=%d client %s feet %.3f %.3f %.3f vel %.3f onGround %b moves-sent %d"
						+ " cam %.2f %.2f %.2f since-last %dms%s",
				client.level.getGameTime(), client.level.dimension().identifier(),
				at.x, at.y, at.z, moving.y, client.player.onGround(),
				codx.planeshift.client.DoorwayCrossing.sentSinceLastAsked(),
				drawnFrom.x, drawnFrom.y, drawnFrom.z,
				since, since > 100 ? "  <<< STALL" : ""));
	}

	public static void forget() {
		watching = false;
		last = 0L;
		TraceLog.close();
	}
}
