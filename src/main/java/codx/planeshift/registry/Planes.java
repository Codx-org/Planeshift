package codx.planeshift.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import codx.planeshift.crossing.Passable;
import codx.planeshift.plane.ChunkPlaneProvider;
import codx.planeshift.plane.GlobalPlaneProvider;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Planeshift's public entry point: where other mods say which planes exist.
 *
 * <p>Registration is static and belongs in mod initialisation. The planes themselves are
 * not registered here, because a provider usually needs world state to name one at all:
 * OneDimension cannot know its world floor until the dimension is loaded, and Actual
 * Portals cannot know its portals until the chunks are.
 */
public final class Planes {
	private static final Map<ResourceKey<Level>, List<GlobalPlaneProvider>> GLOBAL = new HashMap<>();
	private static final Map<ResourceKey<Level>, List<ChunkPlaneProvider>> BY_CHUNK = new HashMap<>();
	private static final List<GlobalPlaneProvider> GLOBAL_ANYWHERE = new ArrayList<>();
	private static final List<ChunkPlaneProvider> BY_CHUNK_ANYWHERE = new ArrayList<>();
	private static final Map<ResourceKey<Level>, PlaneStore> STORES = new HashMap<>();

	private Planes() {
	}

	/**
	 * Registers planes that need no chunk index, in <em>every</em> dimension, including
	 * modded ones that do not exist yet at registration time. The provider is handed the
	 * level and decides what — if anything — that dimension gets.
	 *
	 * <p>This is the OneDimension case: it stacks whatever dimensions the server turns
	 * out to have, so it cannot name them up front. Providers are first asked once a
	 * level is queried, by which point {@code MinecraftServer.levelKeys()} is complete.
	 */
	public static void registerGlobal(GlobalPlaneProvider provider) {
		GLOBAL_ANYWHERE.add(provider);
	}

	/** As {@link #registerGlobal(GlobalPlaneProvider)}, for planes found per chunk. */
	public static void registerInChunks(ChunkPlaneProvider provider) {
		BY_CHUNK_ANYWHERE.add(provider);
	}

	/**
	 * Registers planes that need no chunk index, in one dimension. Call during mod
	 * initialisation; this is not safe once a server is running.
	 */
	public static void registerGlobal(ResourceKey<Level> dimension, GlobalPlaneProvider provider) {
		GLOBAL.computeIfAbsent(dimension, key -> new ArrayList<>()).add(provider);
	}

	/**
	 * Registers planes discovered per chunk. Call during mod initialisation; this is not
	 * safe once a server is running.
	 */
	public static void registerInChunks(ResourceKey<Level> dimension, ChunkPlaneProvider provider) {
		BY_CHUNK.computeIfAbsent(dimension, key -> new ArrayList<>()).add(provider);
	}

	/** The store for a level, created on first use. Server thread only. */
	public static PlaneStore store(ServerLevel level) {
		return STORES.computeIfAbsent(level.dimension(), key -> new PlaneStore(
				level,
				both(GLOBAL_ANYWHERE, GLOBAL.get(key)),
				both(BY_CHUNK_ANYWHERE, BY_CHUNK.get(key))));
	}

	private static <T> List<T> both(List<T> anywhere, @Nullable List<T> forDimension) {
		if (forDimension == null || forDimension.isEmpty()) {
			return List.copyOf(anywhere);
		}

		List<T> all = new ArrayList<>(anywhere);
		all.addAll(forDimension);
		return List.copyOf(all);
	}

	/**
	 * Tells Planeshift a chunk's planes may have changed — a portal lit or broken, say.
	 * Its providers are asked again on the next query.
	 */
	public static void invalidate(ServerLevel level, ChunkPos chunk) {
		PlaneStore store = STORES.get(level.dimension());

		if (store != null) {
			store.invalidate(chunk);
			Passable.refresh(store);
		}
	}

	/** As {@link #invalidate}, for planes registered without a chunk index. */
	public static void invalidateGlobal(ServerLevel level) {
		PlaneStore store = STORES.get(level.dimension());

		if (store != null) {
			store.invalidateGlobal();
			Passable.refresh(store);
		}
	}

	/**
	 * As {@link #invalidateGlobal(ServerLevel)} across every level at once, for when
	 * something changed that no single dimension owns — an admin reordering a stack,
	 * or a config reload.
	 */
	public static void invalidateGlobal() {
		for (PlaneStore store : STORES.values()) {
			store.invalidateGlobal();
			Passable.refresh(store);
		}
	}

	/** Wires up cache lifetime. Called once from the mod's main entry point. */
	public static void init() {
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> invalidate(level, chunk.getPos()));
		// A store holds its ServerLevel, so keeping one past shutdown would pin the
		// whole world in memory. A fresh server rebuilds from the providers anyway.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			STORES.clear();
			// Keyed by dimension and by a generation that starts again from zero, so a
			// second world in one session would inherit the first one's openings.
			codx.planeshift.crossing.Passable.forget();
		});
	}
}
