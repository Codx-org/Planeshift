package codx.planeshift.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import codx.planeshift.plane.Plane;
import codx.planeshift.plane.ChunkPlaneProvider;
import codx.planeshift.plane.GlobalPlaneProvider;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * One level's view of its planes: what that dimension's providers have
 * produced, indexed so crossing detection can ask "what might this movement have
 * crossed?" without scanning everything.
 *
 * <p>Runtime state only, never saved. Every plane is derived from world state and
 * regenerates on load; a persisted plane would outlive the portal somebody broke while
 * the server was down.
 *
 * <p>Server thread only.
 */
public final class PlaneStore {
	private final ServerLevel level;
	private final List<GlobalPlaneProvider> globalProviders;
	private final List<ChunkPlaneProvider> chunkProviders;

	private @Nullable List<Plane> global;
	private final Long2ObjectMap<List<Plane>> byChunk = new Long2ObjectOpenHashMap<>();

	/**
	 * Ids handed out so far. Keyed by the plane's value, so a provider re-deriving the
	 * same plane after a chunk reload gets the same id back and a client's watch
	 * survives. Entries outlive the planes they name; resolving checks the live set.
	 */
	private final Map<Plane, Integer> ids = new HashMap<>();
	private int nextId = 1;

	/**
	 * Bumped whenever what this store knows changes.
	 *
	 * <p>Planes found per chunk are found lazily — nothing knows a portal exists until
	 * something asks about the chunk it is in. So there is no moment to tell the client
	 * "here is a new plane" at; there is only this, which says the answer is not what it
	 * was, and lets whoever cares ask again.
	 */
	private int generation;

	/** How many times what this store knows has changed. */
	/** The level these planes are in. */
	public ServerLevel level() {
		return level;
	}

	public int generation() {
		return generation;
	}

	PlaneStore(ServerLevel level, List<GlobalPlaneProvider> globalProviders,
			List<ChunkPlaneProvider> chunkProviders) {
		this.level = level;
		this.globalProviders = globalProviders;
		this.chunkProviders = chunkProviders;
	}

	/**
	 * Visits every plane a movement from {@code from} to {@code to} could have crossed.
	 * Candidates, not hits — the caller still tests each one.
	 *
	 * <p>Takes a consumer rather than returning a collection because this runs per
	 * entity per tick and almost always visits nothing.
	 */
	public void forEachCandidate(Vec3 from, Vec3 to, Consumer<Plane> out) {
		global().forEach(out);

		int minX = SectionPos.blockToSectionCoord(Math.min(from.x, to.x));
		int maxX = SectionPos.blockToSectionCoord(Math.max(from.x, to.x));
		int minZ = SectionPos.blockToSectionCoord(Math.min(from.z, to.z));
		int maxZ = SectionPos.blockToSectionCoord(Math.max(from.z, to.z));

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				cachedChunk(x, z).forEach(out);
			}
		}
	}

	/**
	 * Whether there is certainly nothing here to cross.
	 *
	 * <p>For skipping a level's entities outright. Only ever says yes when it is sure: a
	 * chunk provider might produce a plane in a chunk nobody has asked about yet, so any
	 * chunk provider at all means the question has to be asked properly.
	 */
	public boolean empty() {
		return global().isEmpty() && chunkProviders.isEmpty();
	}

	/** Every plane currently known, with its id, for syncing a joining player. */
	public void forEachKnown(Consumer<IdentifiedPlane> out) {
		forEachLive(plane -> out.accept(new IdentifiedPlane(idOf(plane), plane)));
	}

	/**
	 * The id a client names this plane by, assigned on first use.
	 *
	 * <p>Ids are not reused once handed out, even if the plane goes away. A client
	 * naming a stale one is told no, which is cheaper than the confusion of an id
	 * quietly coming to mean something else.
	 */
	public int idOf(Plane plane) {
		Integer existing = ids.get(plane);

		if (existing != null) {
			return existing;
		}

		int id = nextId++;
		ids.put(plane, id);
		return id;
	}

	/**
	 * The live plane with this id, or null if there is none any more.
	 *
	 * <p>Scans rather than keeping a reverse index: a plane can stop existing without
	 * telling anybody, so the live set is the only honest source, and watches are rare
	 * enough that walking it costs nothing.
	 */
	public @Nullable Plane byId(int id) {
		Plane[] found = new Plane[1];

		forEachLive(plane -> {
			if (found[0] == null && ids.getOrDefault(plane, -1) == id) {
				found[0] = plane;
			}
		});

		return found[0];
	}

	private void forEachLive(Consumer<Plane> out) {
		global().forEach(out);
		byChunk.values().forEach(planes -> planes.forEach(out));
	}

	/** Drops a chunk's cached planes so its providers are asked again. */
	public void invalidate(ChunkPos chunk) {
		if (byChunk.remove(chunk.pack()) != null) {
			generation++;
		}
	}

	/** Drops the cached global planes so the providers are asked again. */
	public void invalidateGlobal() {
		if (global != null) {
			global = null;
			generation++;
		}
	}

	/**
	 * Asks the providers about every chunk within {@code radius} of {@code centre}.
	 *
	 * <p>Without this a plane is only ever found in a chunk somebody has walked through,
	 * because that is the only thing that asks: crossing detection queries the chunks a
	 * movement spans and nothing queries the rest. A portal ten blocks away in the next
	 * chunk would go unmentioned until you happened to stand in it — by which point you
	 * have already walked into a doorway that was never drawn.
	 *
	 * <p>Cheap to repeat. An answered chunk stays answered until something invalidates it,
	 * so all this usually does is look up numbers it already has.
	 */
	public void discoverAround(ChunkPos centre, int radius) {
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				cachedChunk(centre.x() + x, centre.z() + z);
			}
		}
	}

	private List<Plane> global() {
		if (global == null) {
			List<Plane> collected = new ArrayList<>();

			for (GlobalPlaneProvider provider : globalProviders) {
				provider.collect(level, collected::add);
			}

			global = List.copyOf(collected);
		}

		return global;
	}

	private List<Plane> cachedChunk(int x, int z) {
		long key = ChunkPos.pack(x, z);
		List<Plane> cached = byChunk.get(key);

		if (cached == null) {
			// Only a miss needs a real ChunkPos; the lookup itself stays allocation-free.
			ChunkPos chunk = new ChunkPos(x, z);
			List<Plane> collected = new ArrayList<>();

			for (ChunkPlaneProvider provider : chunkProviders) {
				provider.collect(level, chunk, collected::add);
			}

			cached = List.copyOf(collected);
			byChunk.put(key, cached);

			if (!cached.isEmpty()) {
				generation++;
			}
		}

		return cached;
	}
}
