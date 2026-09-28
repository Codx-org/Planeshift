package codx.planeshift.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codx.planeshift.crossing.Passable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Tells anything that works out where it can walk that a portal's opening is open.
 *
 * <p>Collision already lets everything through those blocks, but a mob decides where to go
 * before it moves, and it decides from the blocks rather than from their shapes. Left alone
 * it will not step into a doorway at all — and, worse, one that arrives through a doorway
 * stands inside the far wall with nowhere its own reasoning will let it go, until vanilla
 * shoves it out through the back. That is the creeper stuck in the portal.
 *
 * <p>Saying open here rather than in each of the callers puts it at the bottom of the whole
 * pathfinding stack: one answer, and everything above it — ground, danger, doors — follows
 * from it.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class PortalOpeningPathMixin {
	@Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
	private static void planeshift$doorwaysAreOpen(BlockGetter level, BlockPos pos,
			CallbackInfoReturnable<PathType> info) {
		if (Passable.at(level, pos)) {
			info.setReturnValue(PathType.OPEN);
		}
	}
}
