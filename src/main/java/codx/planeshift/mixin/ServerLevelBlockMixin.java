package codx.planeshift.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.destination.PlaneWatches;
import codx.planeshift.interact.PlaneFluids;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tells watches of a level about blocks changing in it.
 *
 * <p>A watch sends each column once, so without this the far side of a plane is a
 * photograph taken when the view filled. This is the same point vanilla uses to tell the
 * players who are actually in the level, so anything they would see, a watcher sees.
 */
@Mixin(ServerLevel.class)
public class ServerLevelBlockMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void planeshift$relayToWatches(BlockPos pos, BlockState oldState, BlockState newState,
			int flags, CallbackInfo info) {
		PlaneWatches.blockChanged((ServerLevel) (Object) this, pos, newState);
		// A neighbour update does not cross a plane, because a plane is not a block and has
		// nothing to send one to. Without this the fluid on the far side is never told that
		// what was feeding it has gone, and goes on pouring after the source is removed.
		PlaneFluids.nudgeAcross((ServerLevel) (Object) this, pos);
	}
}
