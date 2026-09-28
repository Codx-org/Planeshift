package codx.planeshift.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codx.planeshift.crossing.Passable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Stops a portal's own opening smothering whoever walks into it.
 *
 * <p>Suffocation is not asked of the collision shape but of the block itself, so a doorway
 * you can walk into would otherwise crush you on the way through — most of all through a
 * floor, where an eye spends a moment below the surface while the body catches up.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PortalOpeningAirMixin {
	@Inject(method = "isSuffocating", at = @At("HEAD"), cancellable = true)
	private void planeshift$doorwaysDoNotSmother(BlockGetter level, BlockPos pos,
			CallbackInfoReturnable<Boolean> info) {
		if (Passable.at(level, pos)) {
			info.setReturnValue(false);
		}
	}
}
