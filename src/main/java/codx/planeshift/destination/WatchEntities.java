package codx.planeshift.destination;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import codx.planeshift.network.DestEntityBatch;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.UpdateInterval;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.AABB;

/**
 * The entities on the far side of one watch, kept in step with the client showing it.
 *
 * <p>Vanilla's own tracker does this job for a player standing in a level, and this uses
 * the same {@link ServerEntity} to do it for a player only looking into one. Reusing it
 * rather than writing the packets by hand is the whole point: interpolation, rotation
 * quantisation, passengers, equipment and the forced periodic resync are all decisions
 * vanilla has already made, and a portal has no business making them differently.
 *
 * <p>What is not reused is the pairing. {@code addPairing} would enter this player into
 * the entity's real tracking set, and the server would then treat them as present in a
 * level they are not standing in. Only the packets are wanted, so only the packets are
 * taken.
 */
final class WatchEntities {
	/** How far past the streamed area to follow entities, in blocks. */
	private static final double MARGIN = 16.0;

	/**
	 * Ticks between sweeps for entities that have come or gone.
	 *
	 * <p>Keeping the ones already known in step is cheap and has to happen every tick, or
	 * they move in steps. Asking the level afresh which entities are in a seventeen-chunk
	 * box is not cheap, and nothing about the answer changes meaningfully in a tenth of a
	 * second — an entity that wanders into view a few ticks late is invisible next to a
	 * server tick that runs long, which stops every player's movement being processed at
	 * all.
	 */
	private static final int SWEEP_INTERVAL = 4;

	private int sinceSweep;

	private final Map<Integer, ServerEntity> tracked = new HashMap<>();
	private final List<Packet<? super ClientGamePacketListener>> pending = new ArrayList<>();
	private final ServerEntity.Synchronizer synchronizer = new Collector();

	/** Every packet a {@link ServerEntity} would have sent to the players tracking it. */
	private final class Collector implements ServerEntity.Synchronizer {
		@Override
		public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
			pending.add(packet);
		}

		@Override
		public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
			pending.add(packet);
		}

		@Override
		public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet,
				java.util.function.Predicate<ServerPlayer> filter) {
			pending.add(packet);
		}
	}

	/** Brings the client's copy of this watch's entities up to date. */
	void tick(ServerPlayer player, Watch watch) {
		if (--sinceSweep > 0) {
			// Between sweeps: keep what is already known moving, and nothing more.
			for (ServerEntity server : tracked.values()) {
				server.sendChanges();
			}

			flush(player, watch.handle);
			return;
		}

		sinceSweep = SWEEP_INTERVAL;
		sweep(player, watch);
	}

	/** Looks afresh at what is in the watched area, and tells the client what changed. */
	private void sweep(ServerPlayer player, Watch watch) {
		// The streamed area, full height. Bounded by the level rather than by a huge
		// number: an unbounded box is asked of the entity index every tick, per watch.
		double reach = watch.radius * 16.0 + MARGIN;
		double middleX = watch.center.getMiddleBlockX();
		double middleZ = watch.center.getMiddleBlockZ();
		AABB area = new AABB(
				middleX - reach, watch.destination.getMinY(), middleZ - reach,
				middleX + reach, watch.destination.getMaxY() + 1, middleZ + reach);
		List<Entity> present = watch.destination.getEntities((Entity) null, area,
				entity -> !entity.isRemoved());
		IntList gone = new IntArrayList();

		for (Entity entity : present) {
			if (!trackable(entity) || entity == player) {
				// Not the watcher. A player's own body is drawn into the far side by their
				// client, from the same position the view itself is drawn from; relaying it
				// as well puts a second, older copy of them in the doorway.
				continue;
			}

			ServerEntity server = tracked.get(entity.getId());

			if (server == null) {
				EntityType<?> type = entity.getType();
				server = new ServerEntity(watch.destination, entity,
						type.hasUpdateInterval() ? UpdateInterval.periodic(type.updateInterval())
								: UpdateInterval.NEVER,
						true, synchronizer);
				tracked.put(entity.getId(), server);
				// Everything the client needs to build it: the spawn, its data, what it
				// is wearing, what it is riding.
				server.sendPairingData(player, pending::add);
			} else {
				server.sendChanges();
			}
		}

		for (Iterator<Map.Entry<Integer, ServerEntity>> each = tracked.entrySet().iterator();
				each.hasNext();) {
			Map.Entry<Integer, ServerEntity> entry = each.next();
			Entity entity = watch.destination.getEntity(entry.getKey());

			if (entity == null || entity.isRemoved() || !area.intersects(entity.getBoundingBox())) {
				gone.add((int) entry.getKey());
				each.remove();
			}
		}

		if (!gone.isEmpty()) {
			pending.add(new ClientboundRemoveEntitiesPacket(gone));
		}

		flush(player, watch.handle);
	}

	/**
	 * Whether this is an entity the client is told about on its own.
	 *
	 * <p>The same two tests {@code ChunkMap} makes before it starts tracking one. A
	 * dragon's parts are sent as part of the dragon and refuse to build a spawn packet at
	 * all — asking one for it throws, and on the server thread that is the whole server.
	 * A type with no client tracking range is never sent to anybody.
	 */
	private static boolean trackable(Entity entity) {
		return !(entity instanceof EnderDragonPart) && entity.getType().clientTrackingRange() > 0;
	}

	/** Sends what has piled up, wrapped in the markers that say which level it is for. */
	private void flush(ServerPlayer player, int handle) {
		if (pending.isEmpty()) {
			return;
		}

		ServerPlayNetworking.send(player, new DestEntityBatch(handle, true));

		for (Packet<? super ClientGamePacketListener> packet : pending) {
			player.connection.send(packet);
		}

		ServerPlayNetworking.send(player, new DestEntityBatch(handle, false));
		pending.clear();
	}

	/** Tells the client to drop them all, when the watch ends. */
	void clear(ServerPlayer player, int handle) {
		if (tracked.isEmpty()) {
			return;
		}

		IntList all = new IntArrayList(tracked.keySet());
		tracked.clear();
		pending.clear();
		pending.add(new ClientboundRemoveEntitiesPacket(all));
		flush(player, handle);
	}
}
