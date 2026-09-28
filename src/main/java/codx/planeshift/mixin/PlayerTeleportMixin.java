package codx.planeshift.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codx.planeshift.crossing.CrossingDetector;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * A player who is put somewhere did not walk there.
 *
 * <p>Crossings are decided from the line between where a player's eye was last tick and
 * where it is now. A teleport leaves exactly the same evidence as a very fast walk, and a
 * short one — an ender pearl across a room, a chorus fruit, {@code /tp} to a friend — is
 * short enough to pass the step limit that catches the long ones. Every plane on the line
 * between the two points then counts as crossed, and the player arrives somewhere they
 * were never sent.
 *
 * <p>Starting the line again at wherever they landed is the whole fix: the step they never
 * took is not measured, and the next real one is.
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerTeleportMixin {
	@Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)"
			+ "Lnet/minecraft/server/level/ServerPlayer;", at = @At("RETURN"))
	private void planeshift$forgetTheJump(TeleportTransition transition,
			CallbackInfoReturnable<ServerPlayer> info) {
		if (info.getReturnValue() != null) {
			CrossingDetector.resync(info.getReturnValue());
		}
	}

	@Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
			at = @At("RETURN"))
	private void planeshift$forgetTheHop(ServerLevel level, double x, double y, double z,
			Set<Relative> relatives, float yaw, float pitch, boolean resetCamera,
			CallbackInfoReturnable<Boolean> info) {
		if (info.getReturnValueZ()) {
			CrossingDetector.resync((ServerPlayer) (Object) this);
		}
	}
}
