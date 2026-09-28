package codx.planeshift.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import codx.planeshift.client.interact.FarHand;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The client's half of the same correction the server makes.
 *
 * <p>A far action swaps {@code Minecraft.level}, which is what everything the game mode
 * does reads — but the context an item is used through asks the player instead, and the
 * player is still standing on this side. Without this, the client predicts the block
 * appearing in its own world at the far side's coordinates: a block that flashes into
 * existence beside you and then vanishes when the server disagrees.
 */
@Mixin(UseOnContext.class)
public class ClientUseOnContextMixin {
	@WrapOperation(
			method = "<init>(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;"
					+ "Lnet/minecraft/world/phys/BlockHitResult;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/player/Player;level()Lnet/minecraft/world/level/Level;"))
	private static Level planeshift$levelBeingReachedInto(Player player, Operation<Level> original) {
		Minecraft client = Minecraft.getInstance();

		if (FarHand.through() < 0 || client.level == null || !player.isLocalPlayer()) {
			return original.call(player);
		}

		return client.level;
	}
}
