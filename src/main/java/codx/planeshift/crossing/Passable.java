package codx.planeshift.crossing;

import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneShape;
import codx.planeshift.registry.IdentifiedPlane;
import codx.planeshift.registry.PlaneStore;
import codx.planeshift.registry.Planes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/**
 * The blocks a portal opening covers, which nothing collides with.
 *
 * <p>A plane is crossed when an eye passes its surface, and a wall stops you a third of a
 * block short of its own face. So a doorway painted flush on solid rock could be looked
 * through and never walked through, and the only way out of that used to be for the mod
 * that made the portal to dig the wall away — which leaves holes in somebody's build,
 * needs the blocks remembering and putting back, and makes a portal something you carve
 * rather than something you place.
 *
 * <p>It is a portal. Nothing has to be taken away for you to go through it: the blocks its
 * opening covers simply stop stopping you, and the far side is drawn over them, so the wall
 * is whole again the moment the portal moves on.
 *
 * <p>Only finite portals. An edgeless plane covers a whole layer of the world, and a layer
 * of the world that nothing collides with is a different feature with a different name.
 *
 * <p>How deep depends on which way the opening faces. Through a wall an eye travels
 * sideways and reaches the surface as soon as the body does, so the covered blocks are
 * enough. Through a floor it has to fall past the surface, which takes the height of a
 * player, so those go two deep — the same distance the digging used to.
 */
public final class Passable {
	/**
	 * How far behind a floor or ceiling opening to let things through, in blocks.
	 *
	 * <p>Deep enough that a body falling as fast as a body can fall cannot reach solid
	 * ground before the crossing carries it away. A player's crossing is decided by their
	 * eye, a metre and a half behind their feet; the eye may pass the surface at any point
	 * within a tick, and the carrying happens on the next one. At terminal velocity that is
	 * four blocks twice over, and nine and a half blocks in all.
	 *
	 * <p>Land inside that window and the landing takes the speed away before the doorway
	 * can carry it across, which turns a long fall through a pair of doorways into a fresh
	 * fall out of the far side, over and over — the thing the far side is supposed to make
	 * impossible.
	 *
	 * <p>It costs nothing when there is nothing there: in open air, or above a cave, these
	 * are blocks that were never in the way.
	 */
	private static final int DEEP = 10;

	/** What a server level's openings were, and the generation of planes they came from. */
	private record Known(int generation, LongSet open) {
	}

	private static final Map<ResourceKey<Level>, Known> BY_LEVEL = new HashMap<>();

	private static LongSet here = LongSets.emptySet();

	/**
	 * Whether anything anywhere has an opening.
	 *
	 * <p>Read on every collision query in the game, so it is one boolean rather than a map
	 * lookup: a server with no finite portals in it pays nothing at all for this.
	 */
	private static boolean any;

	private Passable() {
	}

	/** Whether this block is inside a portal opening, and so not there as far as moving goes. */
	public static boolean at(BlockGetter getter, BlockPos pos) {
		if (!any) {
			return false;
		}

		if (getter instanceof ServerLevel level) {
			Known known = BY_LEVEL.get(level.dimension());
			return known != null && known.open().contains(pos.asLong());
		}

		// A client collides only in the level its player is in, and that is the one it was
		// last sent planes for.
		return getter instanceof Level && here.contains(pos.asLong());
	}

	/** Tells the client which openings are in the level it is in. */
	public static void hereIs(LongSet open) {
		here = open;
		any = any || !open.isEmpty();
		codx.planeshift.Planeshift.LOGGER.info("Openings here: {}", open.size());
	}

	/** Drops everything, for leaving a server or shutting one down. */
	public static void forget() {
		BY_LEVEL.clear();
		here = LongSets.emptySet();
		any = false;
	}

	/**
	 * Works a level's openings out again, if its planes have changed since last time.
	 *
	 * <p>Pushed in rather than worked out when asked. Asking happens on every collision
	 * query in the game and is guarded by one boolean, and a guard that is only lifted by
	 * the work it is guarding never lifts at all — the first server this ran on had a
	 * doorway nothing could walk through for exactly that reason.
	 */
	public static void refresh(PlaneStore store) {
		int generation = store.generation();
		Known known = BY_LEVEL.get(store.level().dimension());

		if (known != null && known.generation() == generation) {
			return;
		}

		LongSet open = new LongOpenHashSet();
		store.forEachKnown(identified -> add(open, identified.plane()));
		BY_LEVEL.put(store.level().dimension(), new Known(generation, open));
		any = any || !open.isEmpty();
	}

	/** The blocks the given planes' openings cover. */
	public static LongSet openingsOf(Iterable<IdentifiedPlane> planes) {
		LongSet open = new LongOpenHashSet();

		for (IdentifiedPlane identified : planes) {
			add(open, identified.plane());
		}

		return open;
	}

	private static void add(LongSet open, Plane plane) {
		if (!plane.isPortal() || !(plane.shape() instanceof PlaneShape.Rectangle rect)) {
			return;
		}

		Direction into = plane.facing().getOpposite();
		int depth = plane.facing().getAxis().isVertical() ? DEEP : 1;

		for (BlockPos covered : BlockPos.betweenClosed(rect.cornerA(), rect.cornerB())) {
			for (int step = 0; step < depth; step++) {
				open.add(covered.relative(into, step).asLong());
			}
		}
	}
}
