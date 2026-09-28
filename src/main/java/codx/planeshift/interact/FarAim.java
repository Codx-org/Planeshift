package codx.planeshift.interact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import codx.planeshift.crossing.CrossingDetector;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where a player is looking from and towards, while they are looking through a plane.
 *
 * <p>Some things an item does are not told what they are acting on: a bucket casts its own
 * ray from the player's eye, in the player's own level, and places at whatever it finds.
 * Through a plane both of those are wrong — the eye is in one world and what it is aimed at
 * is in another — and there is nothing in the call to correct, only the player to ask.
 *
 * <p>So this is the answer to that question, set for the length of one interaction and
 * asked by anything that would otherwise work it out from the player directly.
 *
 * <p>Kept here rather than on either side, because both sides need it and need the same
 * answer: the client runs the item once to predict what will happen, and the server runs it
 * again to decide what does. If the two aim differently the client shows a block appearing
 * and the server never agrees, which is a fluid that vanishes a moment after you place it.
 *
 * @param level     where the ray should be cast
 * @param eye       where it starts, which is where the player's eye maps to over there
 * @param view      which way it goes, turned by however much the plane turns things
 * @param reach     how far the player can reach
 * @param here      the level the player is standing in
 * @param plane     the plane being reached through, in {@code here}'s terms
 * @param transform how {@code here} maps onto {@code level}
 */
public record FarAim(Level level, Vec3 eye, Vec3 view, double reach, Level here, Plane plane,
		PlaneTransform transform) {
	private static final Map<UUID, FarAim> aiming = new HashMap<>();

	/** Where this player is aiming, if they are aiming through a plane right now. */
	public static @Nullable FarAim of(UUID player) {
		return aiming.get(player);
	}

	/** Set for the length of one interaction. Returns whatever it displaced, for {@link #leave}. */
	public static @Nullable FarAim enter(UUID player, FarAim aim) {
		return aiming.put(player, aim);
	}

	/** Undoes an {@link #enter}, restoring whatever it displaced. */
	public static void leave(UUID player, @Nullable FarAim before) {
		if (before == null) {
			aiming.remove(player);
		} else {
			aiming.put(player, before);
		}
	}

	public static void forget(UUID player) {
		aiming.remove(player);
	}

	/**
	 * Where something placed at {@code target} really belongs, when that is back on this
	 * side of the plane.
	 *
	 * <p>What an item puts down goes in the cell beside the one it was used on, and that
	 * cell can be on either side of a plane. Use a bucket on the face of a far block that
	 * points back at you and the fluid belongs in your own world: the space between that
	 * block and the doorway is on this side of it.
	 *
	 * @param clicked the block that was used on, in the far level's terms
	 * @param target  the cell the item would fill, in the far level's terms
	 * @return the same cell in this level's terms, or null if it really is on the far side
	 */
	public @Nullable BlockPos comingBack(BlockPos clicked, BlockPos target) {
		PlaneTransform back = transform.inverse(here.dimension(), plane.anchor());
		Vec3 anchor = transform.offset();
		Vec3 from = back.position(Vec3.atCenterOf(clicked), anchor);
		Vec3 into = back.position(Vec3.atCenterOf(target), anchor);

		// Said in this level's coordinates and tested against this level's plane. The
		// transform is rigid, so a step that crosses the doorway over there crosses it here
		// too, and here is where the plane we can ask about lives.
		return CrossingDetector.test(plane, from, into) == null ? null : BlockPos.containing(into);
	}
}
