package codx.planeshift.destination;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import codx.planeshift.network.DestBlockPayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One player being shown the far side of one plane: what the server agreed to, plus how
 * much of it has actually gone out.
 *
 * <p>Mutable and server-thread-only. The fields the client agreed to are fixed at open
 * time — re-centring a live watch would leave the client's level built around the wrong
 * place.
 */
public final class Watch {
	public final int handle;
	public final int planeId;
	public final ServerLevel destination;
	public final ChunkPos center;
	public final int radius;

	/** Columns already sent, so a watch that lives for minutes does not resend them. */
	final LongSet sent = new LongOpenHashSet();

	/** What is moving about on the far side, and what the client has been told of it. */
	final WatchEntities entities = new WatchEntities();

	/**
	 * Blocks that have changed since the last flush, by packed position.
	 *
	 * <p>A map, not a list: a block that changes five times in a tick — a falling column of
	 * sand, a fluid settling — is worth sending once, in the state it ended in.
	 */
	private final Long2IntMap changed = new Long2IntOpenHashMap();

	/**
	 * How many changes are worth relaying before resending the columns instead.
	 *
	 * <p>Past this the updates cost more than the terrain they are patching, and there is
	 * no reason to believe the next tick will be quieter. Forgetting the columns lets the
	 * streamer send them again whole, which it paces against its own budget.
	 */
	private static final int TOO_MANY = 2048;

	/** How many columns and bytes have gone out, for seeing where a watch got to. */
	int columnsSent;
	long bytesSent;

	private boolean ticketed;

	Watch(int handle, int planeId, ServerLevel destination, ChunkPos center, int radius) {
		this.handle = handle;
		this.planeId = planeId;
		this.destination = destination;
		this.center = center;
		this.radius = radius;
	}

	/**
	 * Asks the chunk system to keep this area loaded. One ring wider than the view,
	 * because a column is only ready to send once its neighbours are loaded too.
	 *
	 * <p>Re-added every tick: the ticket expires on its own, so a watch that ends —
	 * however it ends — stops holding the area without having to remember to say so.
	 */
	void refreshTicket() {
		destination.getChunkSource().addTicketWithRadius(TicketType.PORTAL, center, radius + 1);
		ticketed = true;
	}

	boolean ticketed() {
		return ticketed;
	}

	/**
	 * A block changed in this watch's level.
	 *
	 * <p>Only inside a column the client has: elsewhere there is nothing to patch, and the
	 * column will carry the new state when it goes out.
	 */
	void blockChanged(BlockPos pos, BlockState state) {
		if (!sent.contains(ChunkPos.pack(pos))) {
			return;
		}

		if (changed.size() >= TOO_MANY) {
			changed.clear();
			sent.clear();
			return;
		}

		changed.put(pos.asLong(), Block.getId(state));
	}

	/** Sends what changed, if anything did. */
	void flushBlocks(ServerPlayer player) {
		if (changed.isEmpty()) {
			return;
		}

		List<Long> positions = new ArrayList<>(changed.size());
		List<Integer> states = new ArrayList<>(changed.size());

		changed.forEach((pos, state) -> {
			positions.add(pos);
			states.add(state);
		});

		changed.clear();
		ServerPlayNetworking.send(player, new DestBlockPayload(handle, positions, states));
	}
}
