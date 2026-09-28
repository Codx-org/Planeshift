package codx.planeshift.client.interact;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the crosshair is on, when what it is on is in another dimension.
 *
 * <p>Held for a tick at a time, refreshed by the same pick that fills
 * {@link Minecraft#hitResult}. When a far side wins, the near result is replaced with a
 * miss: everything vanilla does with a hit — the outline, mining, using, attacking — would
 * otherwise be aimed at whatever happens to sit behind the opening in this world.
 *
 * <p>So a miss here does not mean "nothing"; it means "nothing on this side". Anything
 * that acts on a hit has to ask this too.
 */
public final class PlaneTargeting {
	private static @Nullable FarHit current;

	private PlaneTargeting() {
	}

	/** What the player is pointing at on the far side of a plane, if anything. */
	public static @Nullable FarHit current() {
		return current;
	}

	/**
	 * Called once the pick's near-side answer is in.
	 *
	 * <p>The near answer is the cutoff: a wall in front of the plane keeps its hit, and
	 * only a ray that reaches the opening first is carried across.
	 */
	public static void afterPick(Minecraft client, float partial) {
		current = null;
		LocalPlayer player = client.player;

		if (player == null || client.level == null) {
			return;
		}

		Vec3 from = player.getEyePosition(partial);
		Vec3 direction = player.getViewVector(partial);
		double reach = player.blockInteractionRange();
		HitResult near = client.hitResult;
		double stopAt = near == null || near.getType() == HitResult.Type.MISS
				? reach
				: near.getLocation().distanceTo(from);

		FarHit far = PlaneReach.pick(client, from, direction, reach, stopAt);

		if (far == null) {
			return;
		}

		current = far;
		// Where the ray left this world, so anything reading the near result sees a clean
		// miss rather than a stale hit at a position that is no longer being pointed at.
		Vec3 end = from.add(direction.scale(reach));
		client.hitResult = BlockHitResult.miss(end, Direction.getApproximateNearest(direction), BlockPos.containing(end));
		client.crosshairPickEntity = null;
	}

	/** Dropped on leaving a world, so a stale far side is never acted on. */
	public static void forget() {
		current = null;
	}
}
