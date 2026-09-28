package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import codx.planeshift.interact.FarHands;
import codx.planeshift.interact.FarSide;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Puts an item's use in the world being reached into, not the one the player stands in.
 *
 * <p>{@link net.minecraft.server.level.ServerPlayerGameMode#useItemOn} takes the level it
 * acts on as an argument, which is what lets it be pointed through a plane — but the
 * context it hands to the item does not. It asks the player, and the player is still on
 * this side. Left alone, placing a block through a plane places it in the player's own
 * dimension at the far side's coordinates: somewhere they cannot see and did not mean.
 *
 * <p>Only the one constructor that guesses. The one that is told stays as it is.
 */
@Mixin(UseOnContext.class)
public class UseOnContextMixin {
	@WrapOperation(
			method = "<init>(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;"
					+ "Lnet/minecraft/world/phys/BlockHitResult;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/player/Player;level()Lnet/minecraft/world/level/Level;"))
	private static Level planeshift$levelBeingReachedInto(Player player, Operation<Level> original) {
		FarSide far = FarHands.acting(player.getUUID());
		return far == null ? original.call(player) : far.level();
	}
}
