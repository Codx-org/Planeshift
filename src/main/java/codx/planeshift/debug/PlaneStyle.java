package codx.planeshift.debug;

import codx.planeshift.network.PlaneStylePayload;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The colour every client draws planes in. One setting for the whole server: it is a
 * debug view, and a per-player setting would need somewhere to live across reconnects
 * for no real gain.
 */
public final class PlaneStyle {
	private static final int DEFAULT_COLOR = 0x00FFFF;

	private static boolean rainbow;
	private static int color = DEFAULT_COLOR;

	private PlaneStyle() {
	}

	public static void setRainbow(MinecraftServer server) {
		rainbow = true;
		broadcast(server);
	}

	public static void setColor(MinecraftServer server, int rgb) {
		rainbow = false;
		// Alpha is the renderer's call — the outline and the fill are drawn at
		// different transparencies, so a sender-supplied one could only be wrong.
		color = rgb & 0xFFFFFF;
		broadcast(server);
	}

	public static PlaneStylePayload payload() {
		return new PlaneStylePayload(rainbow, color);
	}

	private static void broadcast(MinecraftServer server) {
		PlaneStylePayload payload = payload();

		for (ServerPlayer player : PlayerLookup.all(server)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
