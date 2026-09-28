package codx.planeshift.plane;

import java.util.function.Consumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Supplies the planes whose surface lies within a chunk.
 *
 * <p>This is the Actual Portals shape: an unbounded number of planes that appear and
 * vanish with the blocks they are built from, and which cannot be enumerated at all —
 * only found near a position. Called the first time a query touches a chunk, then
 * cached until the chunk unloads or
 * {@link codx.planeshift.registry.Planes#invalidate} is called for it.
 *
 * <p>A plane spanning several chunks may be yielded from each of them. The store does
 * not deduplicate, so a crossing check can see it more than once.
 *
 * <p>Yield only planes whose transform is fully resolved — the client needs a
 * destination to render towards. Vanilla does not know where a nether portal leads
 * until someone walks into it, so a provider wrapping vanilla linking must resolve the
 * link before yielding, or withhold the plane until it is ready to. Withholding is the
 * supported answer; a half-resolved plane is not.
 */
@FunctionalInterface
public interface ChunkPlaneProvider {
	void collect(ServerLevel level, ChunkPos chunk, Consumer<Plane> out);
}
