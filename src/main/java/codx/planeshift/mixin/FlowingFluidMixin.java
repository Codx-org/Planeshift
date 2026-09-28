package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.interact.PlaneFluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Makes a plane a wall to a fluid on one side and a doorway on the other.
 *
 * <p>Every way a fluid moves — falling, spreading sideways, levelling out — ends in this one
 * call, which is why it is the only place this needs to be. By the time it is reached
 * vanilla has already decided that the fluid spreads and how strong it arrives; the step
 * either happens here or, if it would cross a plane, over there.
 *
 * <p>Deliberately not the fluid's tick. Working alongside vanilla rather than in place of it
 * left the fluid doing both: flowing through to the far side and carrying on past the plane
 * in its own world, as though the doorway were a line painted on the floor.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Shadow
	protected abstract int getDropOff(LevelReader level);

	/**
	 * Lets a plane feed this block from the other side.
	 *
	 * <p>This is what a flowing block asks every tick to find out how deep it should be, and
	 * a block that nothing answers for deletes itself. Fluid that arrived through a plane
	 * has no neighbour in the level it landed in, so without this it drains on the tick
	 * after it arrives and nothing is ever seen to come through.
	 */
	@ModifyReturnValue(method = "getNewLiquid", at = @At("RETURN"))
	private FluidState planeshift$fedThroughPlanes(FluidState original, ServerLevel level,
			BlockPos pos, BlockState state) {
		return PlaneFluids.fedThroughPlane((FlowingFluid) (Object) this, level, pos, original,
				getDropOff(level));
	}

	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void planeshift$spreadThroughPlanes(LevelAccessor level, BlockPos pos, BlockState state,
			Direction direction, FluidState fluid, CallbackInfo info) {
		if (level instanceof ServerLevel server
				&& PlaneFluids.carry(server, pos.relative(direction.getOpposite()), pos, fluid)) {
			info.cancel();
		}
	}
}
