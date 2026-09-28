package codx.planeshift.destination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.netty.buffer.Unpooled;

import codx.planeshift.network.DestChunkPayload;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sends the far side of a watched plane, a few columns at a time. Server thread only.
 *
 * <p>Two rules keep this bounded no matter what a client asks for. Columns are only
 * ever <em>read</em>: a ticket asks the chunk system to load the area, and whatever is
 * ready gets sent — never a call that would generate terrain on the spot and stall the
 * tick. And each player has a per-tick budget in columns and in bytes, shared across
 * all their watches, spent nearest-first so the view fills outwards from where they
 * will arrive rather than in some arbitrary order.
 */
public final class DestinationStreamer {
	/** Per player per tick, across all their watches. */
	private static final int COLUMNS_PER_TICK = 12;
	private static final int BYTES_PER_TICK = 256 * 1024;

	/**
	 * How much of a tick all this streaming may take, across every player, in
	 * nanoseconds.
	 *
	 * <p>A count of columns is not a budget. Encoding one is as expensive as its contents
	 * make it, and a fresh watch after a crossing wants three hundred of them at once —
	 * which took server ticks from 50ms to over 200, and once tripped the watchdog. A
	 * stalled tick is not a stalled feature: while it lasts the server processes no
	 * packets at all, so every player's movement backs up and comes out as a snap.
	 *
	 * <p>Time is the thing actually being protected, so time is what is counted.
	 */
	private static final long BUDGET_NANOS = 3_000_000L;

	/** Worst time seen so far in each phase, so the expensive one names itself. */
	private static long slowestTickets;
	private static long slowestEntities;
	private static long slowestColumns;
	private static long slowestFetch;
	private static long slowestEncode;
	private static long slowestWrite;

	/**
	 * The buffer every column is encoded through, sized for one to begin with.
	 *
	 * <p>Sixty-four kibibytes covers a full column; anything larger grows it once and it
	 * stays grown.
	 */
	private static final io.netty.buffer.ByteBuf SCRATCH = Unpooled.buffer(64 * 1024);

	/** Offsets from a watch's centre, nearest first. Computed once; watches share it. */
	private static final List<ChunkPos> SPIRAL = spiral(16);

	private DestinationStreamer() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(DestinationStreamer::tick);
	}

	private static void tick(MinecraftServer server) {
		long started = System.nanoTime();
		long deadline = started + BUDGET_NANOS;

		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			int columns = COLUMNS_PER_TICK;
			int bytes = BYTES_PER_TICK;

			for (Watch watch : PlaneWatches.of(player)) {
				// Every tick: the ticket expires by itself, so a watch that ends for any
				// reason stops holding its area without having to say so.
				long beforeTicket = System.nanoTime();
				watch.refreshTicket();
				long afterTicket = System.nanoTime();
				// Not subject to the column budget: entity updates are small, and holding
				// them back to pay for terrain is what makes the far side look frozen.
				watch.entities.tick(player, watch);
				// Nor these: a patch is a handful of bytes, and holding it back is what
				// leaves the far side showing a block that is no longer there.
				watch.flushBlocks(player);
				long afterEntities = System.nanoTime();
				slowestTickets = report("tickets", afterTicket - beforeTicket, slowestTickets);
				slowestEntities = report("entities", afterEntities - afterTicket, slowestEntities);

				if (columns <= 0 || bytes <= 0 || System.nanoTime() > deadline) {
					// Out of budget: the rest of this watch arrives next tick. A watch is
					// a view being filled in, and a moment longer to fill costs nothing
					// next to a tick that runs over.
					continue;
				}

				long spent = send(player, watch, columns, bytes, deadline);
				columns -= (int) (spent >> 32);
				bytes -= (int) spent;
			}
		}

		slowestColumns = report("columns", System.nanoTime() - started, slowestColumns);
	}

	/** Says so the first time a phase costs more than it ever has. */
	private static long report(String phase, long took, long worst) {
		if (took <= worst) {
			return worst;
		}

		codx.planeshift.debug.TraceLog.line(String.format(
				"        server %s took %.1fms (worst so far)", phase, took / 1_000_000.0));
		return took;
	}

	/** Returns columns sent in the high half and bytes in the low half. */
	private static long send(ServerPlayer player, Watch watch, int columns, int bytes, long deadline) {
		ServerLevel level = watch.destination;
		int sentColumns = 0;
		int sentBytes = 0;

		for (ChunkPos offset : SPIRAL) {
			// Checked here and not only on the way in: one call used to encode a dozen
			// columns before returning, which is how a three millisecond budget came to
			// spend twenty-seven.
			if (sentColumns >= columns || sentBytes >= bytes || System.nanoTime() > deadline) {
				break;
			}

			if (Math.abs(offset.x()) > watch.radius || Math.abs(offset.z()) > watch.radius) {
				continue;
			}

			long key = ChunkPos.pack(watch.center.x() + offset.x(), watch.center.z() + offset.z());

			if (!watch.sent.add(key)) {
				continue;
			}

			long beforeFetch = System.nanoTime();
			LevelChunk chunk = level.getChunkSource().chunkMap.getChunkToSend(key);
			long afterFetch = System.nanoTime();
			slowestFetch = report("fetch one column", afterFetch - beforeFetch, slowestFetch);

			if (chunk == null) {
				// Not ready yet. Forget it so a later tick tries again once the ticket
				// has had time to bring it in.
				watch.sent.remove(key);
				continue;
			}

			byte[] body = encode(level, chunk);
			long afterEncode = System.nanoTime();
			ServerPlayNetworking.send(player, new DestChunkPayload(watch.handle, body));
			long afterSend = System.nanoTime();
			slowestEncode = report("encode one column", afterEncode - afterFetch, slowestEncode);
			slowestWrite = report("write one column (" + body.length + " bytes)",
					afterSend - afterEncode, slowestWrite);
			sentColumns++;
			sentBytes += body.length;
			watch.columnsSent++;
			watch.bytesSent += body.length;
		}

		return ((long) sentColumns << 32) | (sentBytes & 0xFFFFFFFFL);
	}

	private static byte[] encode(ServerLevel level, LevelChunk chunk) {
		// Reused, and big enough to start with. A fresh Unpooled.buffer() begins at 256
		// bytes and reaches a column's fifty thousand by repeatedly allocating a larger
		// array and copying into it — a dozen times a tick, every tick a watch is
		// filling. That churn is what made a single column cost sixty-five milliseconds
		// and what the watchdog caught the server doing when it gave up.
		//
		// Server thread only, so one buffer is enough.
		SCRATCH.clear();
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(SCRATCH,
				level.getServer().registryAccess());
		ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf,
				new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
		byte[] body = new byte[buf.readableBytes()];
		buf.readBytes(body);
		return body;
	}

	/** Every offset within {@code radius}, ordered by distance from the centre. */
	private static List<ChunkPos> spiral(int radius) {
		List<ChunkPos> out = new ArrayList<>();

		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				out.add(new ChunkPos(x, z));
			}
		}

		out.sort(Comparator.comparingInt(pos -> pos.x() * pos.x() + pos.z() * pos.z()));
		return List.copyOf(out);
	}
}
