package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import codx.planeshift.interact.FarAim;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Empties a bucket into the world the fluid actually belongs in.
 *
 * <p>A bucket puts its contents in the cell beside the block it was used on, and beside a
 * block seen through a plane is not always beyond the plane. Used on the face that points
 * back at you, the cell is on your own side of the doorway — and pouring into the far level
 * there fills a space the view is clipped away from, so the fluid goes somewhere real and
 * entirely invisible.
 *
 * <p>The same rule a block placement follows, applied at the one call that decides where a
 * bucket's contents land.
 */
@Mixin(BucketItem.class)
public class BucketSideMixin {
	@WrapMethod(method = "emptyContents")
	private boolean planeshift$emptyOnTheRightSide(LivingEntity entity, Level level, BlockPos pos,
			BlockHitResult hit, Operation<Boolean> original) {
		FarAim aim = FarAim.of(entity.getUUID());

		if (aim == null || level != aim.level()) {
			return original.call(entity, level, pos, hit);
		}

		BlockPos back = aim.comingBack(hit.getBlockPos(), pos);
		return back == null
				? original.call(entity, level, pos, hit)
				: original.call(entity, aim.here(), back, hit);
	}
}
