package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import codx.planeshift.interact.FarHands;
import codx.planeshift.interact.FarSide;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Measures reach through a plane rather than to it.
 *
 * <p>Vanilla measures from the player's eye to the block, which for a block on the other
 * side of a plane is a distance between two worlds and means nothing. While an action is
 * being handled for a far side, the measurement starts from where that eye maps to over
 * there instead.
 *
 * <p>What is left of the reach is what was not spent getting to the plane, so a block a
 * metre past the opening is a metre away however far apart the two worlds sit in their own
 * coordinates. That is the same rule the client picks by, so what the crosshair says is
 * reachable is what the server agrees is.
 */
@Mixin(Player.class)
public class PlayerReachMixin {
	@WrapMethod(method = "isWithinBlockInteractionRange(Lnet/minecraft/core/BlockPos;D)Z")
	private boolean planeshift$reachThroughPlane(BlockPos pos, double slack, Operation<Boolean> original) {
		Player player = (Player) (Object) this;
		FarSide far = FarHands.acting(player.getUUID());

		if (far == null) {
			return original.call(pos, slack);
		}

		// The whole of their reach. The eye this measures from is their own, carried
		// through, so it already stands back from the far plane by however far they stand
		// from the near one — the walk to the doorway is in the measurement, and taking it
		// off again refuses blocks the crosshair says are in reach.
		double range = player.blockInteractionRange() + slack;
		return new AABB(pos).distanceToSqr(far.eye()) < range * range;
	}
}
