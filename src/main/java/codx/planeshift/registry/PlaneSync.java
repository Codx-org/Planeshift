package codx.planeshift.registry;

import java.util.HashMap;
import java.util.Map;

import codx.planeshift.network.PlanesPayload;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Keeps each client's idea of where the planes are up to date.
 *
 * <p>A plane registered by hand exists the moment it is made, and the client can be told
 * then and there. A plane <em>found</em> — built out of blocks, like a lit portal — does
 * not: nothing knows it is there until something asks about the chunk it is in, and by
 * default the only thing that ever asks is an entity moving through that exact chunk.
 *
 * <p>So this does the asking, around each player, and then notices when the answer has
 * changed and says so. Without it a consumer mod's planes reach the client only if a
 * player happens to walk through the chunk before looking at it, which for a portal you
 * approach from across a room means never.
 */
public final class PlaneSync {
	/**
	 * How far around each player to go looking, in chunks.
	 *
	 * <p>Far enough that a plane is known about well before it is close enough to be worth
	 * drawing, so it never has to appear while being looked at — and no further, because
	 * this is a square and squares grow quickly: going from four to six is not half as much
	 * again, it is more than twice.
	 */
	private static final int RADIUS = 4;

	/** How often to look. Planes made of blocks do not come and go in a hurry. */
	private static final int EVERY = 20;

	/** The generation each level's planes were last sent at. */
	private static final Map<ResourceKey<Level>, Integer> sent = new HashMap<>();

	private static int ticks;

	private PlaneSync() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(PlaneSync::tick);
	}

	private static void tick(MinecraftServer server) {
		if (++ticks < EVERY) {
			return;
		}

		ticks = 0;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Planes.store(player.level()).discoverAround(chunkOf(player), RADIUS);
		}

		// After the looking, not during it: one player's search can turn up a plane that
		// everyone in that level should hear about.
		for (ServerLevel level : server.getAllLevels()) {
			int generation = Planes.store(level).generation();

			if (sent.getOrDefault(level.dimension(), -1) != generation) {
				sent.put(level.dimension(), generation);
				// Discovery bumps the generation without going through Planes, so this is
				// where a plane that was found rather than declared becomes walkable.
				codx.planeshift.crossing.Passable.refresh(Planes.store(level));
				PlanesPayload.sendToEveryoneIn(level);
			}
		}
	}

	private static ChunkPos chunkOf(ServerPlayer player) {
		return new ChunkPos(SectionPos.blockToSectionCoord(player.getBlockX()),
				SectionPos.blockToSectionCoord(player.getBlockZ()));
	}

	/** Forgets what has been sent, so a fresh server does not inherit the last one's count. */
	public static void forget() {
		sent.clear();
		ticks = 0;
	}
}
