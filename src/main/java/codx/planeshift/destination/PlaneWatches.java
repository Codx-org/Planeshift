package codx.planeshift.destination;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import codx.planeshift.Planeshift;
import codx.planeshift.network.WatchNet;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.Planes;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Who is being shown the far side of which plane. Server thread only.
 *
 * <p>A client asks by naming a plane; this decides whether it may, and what that means.
 * Everything a client could lie about is checked here — the plane has to be one the
 * server knows, in that player's own dimension, leading somewhere that exists, close
 * enough to be worth showing. A watch also ends by itself when the player walks away
 * or the plane stops existing, so a client that forgets to ask cannot leave one open.
 */
public final class PlaneWatches {
	/**
	 * The shortest a watch's reach is ever allowed to be, in blocks.
	 *
	 * <p>A floor under the server's view distance, so that a server set to send very few
	 * chunks still lets somebody see through a doorway they are standing at.
	 */
	private static final double LEAST_RANGE = 64.0;

	/** How much further a player may wander before a watch is dropped. Stops it flickering. */
	private static final double KEEP_FACTOR = 1.5;

	/**
	 * How close a player must be to a plane to open a watch on it.
	 *
	 * <p>The server's own view distance, because that is what it is already willing to send
	 * this player. A number of its own here meant the far side of a doorway went out while
	 * the doorway itself was still being drawn — the client asked, and this said no, whatever
	 * the client had been configured to want.
	 */
	private static double openRange(ServerPlayer player) {
		return Math.max(LEAST_RANGE,
				player.level().getServer().getPlayerList().getViewDistance() * 16.0);
	}

	/** How far they may then wander before it is dropped. Wider, so it does not flicker. */
	private static double keepRange(ServerPlayer player) {
		return openRange(player) * KEEP_FACTOR;
	}

	/** How many far sides one player may have open. The server's own say; see the rules. */
	private static int maxPerPlayer() {
		int allowed = codx.planeshift.PlaneshiftRules.maxFarSidesPerPlayer();
		return allowed < 0 ? Integer.MAX_VALUE : allowed;
	}

	/** Chunks either side of the centre that the destination view will cover. */
	private static final int RADIUS = 8;

	private static final Map<UUID, Map<Integer, Watch>> WATCHES = new HashMap<>();

	private PlaneWatches() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(PlaneWatches::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
				WATCHES.remove(listener.player.getUUID()));
	}

	public static void open(ServerPlayer player, WatchNet.Watch request) {
		Map<Integer, Watch> mine = WATCHES.computeIfAbsent(player.getUUID(), key -> new HashMap<>());

		if (mine.containsKey(request.handle())) {
			refuse(player, request.handle(), "that handle is already open");
			return;
		}

		if (mine.size() >= maxPerPlayer()) {
			refuse(player, request.handle(), "too many watches");
			return;
		}

		ServerLevel here = player.level();
		Plane plane = Planes.store(here).byId(request.planeId());

		if (plane == null) {
			// Either a stale id or an invented one; the answer is the same either way.
			refuse(player, request.handle(), "no such plane here");
			return;
		}

		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null) {
			refuse(player, request.handle(), "that plane leads nowhere");
			return;
		}

		ServerLevel destination = here.getServer().getLevel(transform.target());

		if (destination == null) {
			refuse(player, request.handle(), "that dimension is not loaded");
			return;
		}

		if (plane.distanceTo(player.getEyePosition()) > openRange(player)) {
			refuse(player, request.handle(), "too far from that plane");
			return;
		}

		WatchNet.Opened opened = opened(player, request.handle(), plane, transform, destination);
		mine.put(request.handle(), new Watch(request.handle(), request.planeId(), destination,
				new ChunkPos(opened.centerX(), opened.centerZ()), opened.radius()));
		ServerPlayNetworking.send(player, opened);
		Planeshift.LOGGER.info("Opened watch {} for {} on plane #{} into {}",
				request.handle(), player.getName().getString(), request.planeId(),
				destination.dimension().identifier());
	}

	public static void close(ServerPlayer player, int handle, String reason) {
		Map<Integer, Watch> mine = WATCHES.get(player.getUUID());
		Watch closing = mine == null ? null : mine.remove(handle);

		if (closing == null) {
			return;
		}

		Planeshift.LOGGER.info("Closing watch {} for {} ({}): {}", handle, player.getName().getString(),
				progress(closing), reason);
		closing.entities.clear(player, handle);

		ServerPlayNetworking.send(player, new WatchNet.Closed(handle, reason));
	}

	/** How far a watch got, so a crossing that fell back can be told from one that never streamed. */
	private static String progress(Watch watch) {
		return watch.columnsSent + " columns, " + (watch.bytesSent / 1024) + " KiB sent of "
				+ ((2 * watch.radius + 1) * (2 * watch.radius + 1)) + " wanted";
	}

	/**
	 * The view is centred where this player would arrive, not on the plane. A finite
	 * plane's centre would do, but an infinite one has none — and for both, what the
	 * player wants to see is what is beyond the part of the plane they are looking at.
	 */
	private static WatchNet.Opened opened(ServerPlayer player, int handle, Plane plane,
			PlaneTransform transform, ServerLevel destination) {
		Vec3 arrival = transform.position(player.getEyePosition(), plane.anchor());
		WatchNet.Settings settings = new WatchNet.Settings(
				destination.isFlat(),
				destination.isDebug(),
				BiomeManager.obfuscateSeed(destination.getSeed()),
				destination.getSeaLevel());

		return new WatchNet.Opened(handle, destination.dimension(), destination.dimensionTypeRegistration(),
				settings, SectionPos.blockToSectionCoord(arrival.x), SectionPos.blockToSectionCoord(arrival.z),
				RADIUS);
	}

	/**
	 * A block changed somewhere: tell every watch that is showing that level.
	 *
	 * <p>Called for every block change on the server, in every level, so it does as little
	 * as possible before finding there is nothing to do. Watches are rare and usually
	 * none.
	 */
	public static void blockChanged(ServerLevel level, BlockPos pos, BlockState state) {
		if (WATCHES.isEmpty()) {
			return;
		}

		for (Map<Integer, Watch> mine : WATCHES.values()) {
			for (Watch watch : mine.values()) {
				if (watch.destination == level) {
					watch.blockChanged(pos, state);
				}
			}
		}
	}

	/** The open watches for one player, for the streamer to feed. */
	public static Collection<Watch> of(ServerPlayer player) {
		Map<Integer, Watch> mine = WATCHES.get(player.getUUID());
		return mine == null ? List.of() : mine.values();
	}

	private static void tick(net.minecraft.server.MinecraftServer server) {
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			Map<Integer, Watch> mine = WATCHES.get(player.getUUID());

			if (mine == null || mine.isEmpty()) {
				continue;
			}

			for (Watch watch : List.copyOf(mine.values())) {
				String gone = stale(player, watch);

				if (gone != null) {
					close(player, watch.handle, gone);
				}
			}
		}
	}

	private static @Nullable String stale(ServerPlayer player, Watch watch) {
		Plane plane = Planes.store(player.level()).byId(watch.planeId);

		if (plane == null) {
			return "that plane is gone";
		}

		if (plane.distanceTo(player.getEyePosition()) > keepRange(player)) {
			return "walked away";
		}

		return null;
	}

	private static void refuse(ServerPlayer player, int handle, String reason) {
		Planeshift.LOGGER.info("Refused watch {} for {}: {}", handle, player.getName().getString(), reason);
		ServerPlayNetworking.send(player, new WatchNet.Closed(handle, reason));
	}
}
