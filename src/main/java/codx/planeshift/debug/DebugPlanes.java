package codx.planeshift.debug;

import codx.planeshift.network.PlanesPayload;
import codx.planeshift.plane.Plane;
import codx.planeshift.registry.Planes;

import net.minecraft.server.level.ServerLevel;

/**
 * Planes placed by hand through {@code /planeshift}, and Planeshift's own first
 * consumer of its public API.
 *
 * <p>There is deliberately no back door for adding a plane to a {@link Planes} store
 * directly: this registers an ordinary wildcard provider and hands out the list it
 * owns, which is exactly the path Actual Portals and OneDimension will take. If that
 * path is awkward here, it will be awkward for them.
 *
 * <p>Server thread only.
 */
public final class DebugPlanes {
	private DebugPlanes() {
	}

	/** Called once from the mod's main entry point, before any store exists. */
	public static void init() {
		Planes.registerGlobal((level, out) -> data(level).planes().forEach(out));
	}

	public static void add(ServerLevel level, Plane plane) {
		data(level).add(plane);
		// The store cached the provider's answer; tell it the answer changed.
		Planes.invalidateGlobal(level);
		PlanesPayload.sendToEveryoneIn(level);
	}

	/** Removes this level's hand-placed planes, returning how many there were. */
	public static int clear(ServerLevel level) {
		int removed = data(level).clear();
		Planes.invalidateGlobal(level);
		PlanesPayload.sendToEveryoneIn(level);
		return removed;
	}

	private static DebugPlaneData data(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(DebugPlaneData.TYPE);
	}
}
