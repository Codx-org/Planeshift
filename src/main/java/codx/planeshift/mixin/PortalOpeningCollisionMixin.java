package codx.planeshift.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codx.planeshift.crossing.Passable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Lets everything through the blocks a portal opening covers.
 *
 * <p>Every entity's movement asks its collision context what a block's shape is, which
 * makes this the one place where "that block is not in the way" can be said once for
 * players, mobs, items and arrows alike.
 *
 * <p>Only the blocks {@link Passable} names, which are the ones a finite portal is drawn
 * across. Everything else answers exactly as it did.
 */
@Mixin(EntityCollisionContext.class)
public abstract class PortalOpeningCollisionMixin {
	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void planeshift$openTheDoorway(BlockState state, CollisionGetter level, BlockPos pos,
			CallbackInfoReturnable<VoxelShape> info) {
		if (Passable.at(level, pos)) {
			info.setReturnValue(Shapes.empty());
		}
	}
}
