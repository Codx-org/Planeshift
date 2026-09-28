package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.interact.FarHands;
import codx.planeshift.interact.FarSide;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;

/**
 * Keeps a block being mined through a plane being mined in the right world.
 *
 * <p>Breaking a block is the one interaction that outlives the packet that started it: the
 * game mode remembers the position and grinds it down over the following ticks, against
 * whatever level it is holding. Pointing that at the far side for the one packet is
 * therefore not enough — it has to stay pointed there for as long as the mining lasts, or
 * the progress lands on whatever happens to sit at those coordinates in the player's own
 * world.
 */
@Mixin(ServerPlayerGameMode.class)
public class GameModeTickMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@Shadow
	protected ServerLevel level;

	@WrapMethod(method = "tick")
	private void planeshift$tickInFarLevel(Operation<Void> original) {
		FarSide far = FarHands.breaking(this.player.getUUID());

		if (far == null) {
			original.call();
			return;
		}

		ServerLevel home = this.level;
		// Both, because the tick does two different things with two different ideas of
		// where it is. It grinds the block down in the level it holds, and it checks the
		// player can still reach it — and that check asks the player, who is on this side.
		// Without the second the progress is thrown away a tick after it starts, and only
		// blocks that break in one go ever break at all.
		FarSide before = FarHands.enter(this.player.getUUID(), far);
		this.level = far.level();

		try {
			original.call();
		} finally {
			this.level = home;
			FarHands.leave(this.player.getUUID(), before);
		}
	}

	/**
	 * How hard the block is, read from the world it is actually in.
	 *
	 * <p>The progress this adds up each tick comes from the block's destroy speed, which is
	 * asked of the player's own level — the one place in here that does not go through the
	 * level the game mode is holding.
	 */
	@WrapOperation(method = "incrementDestroyProgress",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;level()"
							+ "Lnet/minecraft/server/level/ServerLevel;"))
	private ServerLevel planeshift$hardnessFromFarLevel(ServerPlayer player, Operation<ServerLevel> original) {
		return FarHands.breaking(player.getUUID()) == null ? original.call(player) : this.level;
	}

	/**
	 * Any block action aimed at the player's own world ends a far one.
	 *
	 * <p>You cannot mine two blocks at once, and the far break is remembered past the tick
	 * it started in — so without this, starting on a block here would go on grinding down
	 * the one over there.
	 */
	@Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
	private void planeshift$nearActionEndsFarBreak(BlockPos pos, ServerboundPlayerActionPacket.Action action,
			Direction face, int maxY, int sequence, CallbackInfo info) {
		if (FarHands.acting(this.player.getUUID()) == null) {
			FarHands.forget(this.player.getUUID());
		}
	}
}
