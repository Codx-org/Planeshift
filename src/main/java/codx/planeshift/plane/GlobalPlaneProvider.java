package codx.planeshift.plane;

import java.util.function.Consumer;

import net.minecraft.server.level.ServerLevel;

/**
 * Supplies planes that are not worth indexing by chunk — either because they have no
 * bounded extent, or because there are too few for an index to earn its keep. They are
 * tested on every crossing query, so keep the count small.
 *
 * <p>This is the OneDimension shape: one or two planes per dimension, known as soon as
 * the level exists, unchanged for the life of the world. It takes a {@link ServerLevel}
 * rather than being a plain constant because the interesting values — the world floor,
 * the build limit — are properties of the loaded dimension.
 *
 * <p>Results are cached per level until
 * {@link codx.planeshift.registry.Planes#invalidateGlobal} says otherwise.
 */
@FunctionalInterface
public interface GlobalPlaneProvider {
	void collect(ServerLevel level, Consumer<Plane> out);
}
