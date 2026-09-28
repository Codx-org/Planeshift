package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.crossing.CrossingGrace;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Lets a player finish arriving.
 *
 * <p>Vanilla teleports a player back when they report a position further from the last one
 * than their speed allows. Just after a crossing they legitimately are: the client stepped
 * across and kept moving while the claim travelled to the server and back, so the first
 * position afterwards is a round trip's worth of movement away from where the server put
 * them. Falling, that is tens of blocks, and the correction costs the player their fall as
 * well as their place.
 *
 * <p>Only the correction, and only for that moment. The same method does every other
 * teleport a connection performs — the {@code /tp} command among them — so skipping it
 * wholesale silently swallowed those too, and a player who had crossed once could not be
 * moved by anything again. It is therefore only skipped while a position packet is being
 * handled, which is the one call that means "you moved wrongly".
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MovedWronglyMixin {
	/** Set while a client's reported position is being judged, and only then. */
	@Unique
	private boolean planeshift$judgingAMove;

	@Inject(method = "handlePlayerPositionChange", at = @At("HEAD"))
	private void planeshift$enterMoveCheck(double x, double y, double z, float yRot, float xRot,
			boolean onGround, boolean horizontalCollision, CallbackInfo info) {
		this.planeshift$judgingAMove = true;
	}

	@Inject(method = "handlePlayerPositionChange", at = @At("RETURN"))
	private void planeshift$leaveMoveCheck(double x, double y, double z, float yRot, float xRot,
			boolean onGround, boolean horizontalCollision, CallbackInfo info) {
		this.planeshift$judgingAMove = false;
	}

	@WrapMethod(method = "teleport(DDDFF)V")
	private void planeshift$allowArrival(double x, double y, double z, float yRot, float xRot,
			Operation<Void> original) {
		if (this.planeshift$judgingAMove
				&& CrossingGrace.active(((ServerGamePacketListenerAccessor) this).planeshift$player())) {
			codx.planeshift.debug.TraceLog.line(String.format(
					"        server let the arrival stand instead of yanking to %.3f %.3f %.3f", x, y, z));
			return;
		}

		original.call(x, y, z, yRot, xRot);
	}
}
