package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.client.DoorwayCrossing;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

/**
 * Lets the client's own crossing answer the server catching up with it.
 *
 * <p>Both hooks cancel vanilla only when a crossing is outstanding. A respawn otherwise
 * — dying, a command, another mod — runs exactly as it always did.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleRespawn", at = @At("HEAD"), cancellable = true)
	private void planeshift$absorbRespawn(ClientboundRespawnPacket packet, CallbackInfo info) {
		if (DoorwayCrossing.onRespawn((ClientPacketListener) (Object) this, packet)) {
			info.cancel();
		}
	}

	@Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
	private void planeshift$absorbPosition(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
		if (DoorwayCrossing.onPosition((ClientPacketListener) (Object) this, packet)) {
			info.cancel();
		}
	}
}
