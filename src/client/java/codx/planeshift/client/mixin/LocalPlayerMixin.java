package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.client.DoorwayCrossing;

import net.minecraft.client.player.LocalPlayer;

/**
 * Where the client sees its own eye pass through a plane.
 *
 * <p>The last point in a tick at which the move is known and has not yet been told to
 * the server, which is what makes it the right place: the move that crossed is never
 * sent, the claim replaces it, and so the position the server still holds is exactly
 * the one the claim says it started from. Detecting a tick later instead leaves the
 * server already moved on, and the claim looks like a lie.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
	private void planeshift$crossing(CallbackInfo info) {
		if (DoorwayCrossing.beforeSendPosition((LocalPlayer) (Object) this)) {
			info.cancel();
		}
	}
}
