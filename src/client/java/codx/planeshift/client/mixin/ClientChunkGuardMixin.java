package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import codx.planeshift.Planeshift;
import codx.planeshift.client.DoorwayCrossing;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.RunningOnDifferentThreadException;

/**
 * Keeps a crossing from costing the player their connection.
 *
 * <p>Stepping across ahead of the server means the server is still sending chunks for
 * the dimension it believes the player is in, and those are already on the wire when the
 * client swaps. A chunk decoded against a level of a different height runs off the end of
 * its own buffer, and vanilla treats that as a protocol error and disconnects.
 *
 * <p>A dropped column is recoverable — the server sends it again as the view moves. A
 * disconnect is not. So for as long as a crossing is still settling, one that cannot be
 * applied is let go of instead.
 *
 * <p>Only while settling, and only for a real failure. Outside that window a chunk that
 * will not decode is a genuine fault, and swallowing it would hide the thing worth
 * knowing about.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientChunkGuardMixin {
	@WrapMethod(method = "handleLevelChunkWithLight")
	private void planeshift$guardChunk(ClientboundLevelChunkWithLightPacket packet,
			Operation<Void> original) {
		try {
			original.call(packet);
		} catch (RunningOnDifferentThreadException deferred) {
			// Not a failure at all: this is how vanilla hands a packet from the network
			// thread to the main one, by throwing its way out and running again there.
			// Catching it drops the packet instead of deferring it.
			throw deferred;
		} catch (RuntimeException e) {
			if (!DoorwayCrossing.settling()) {
				throw e;
			}

			Planeshift.LOGGER.warn("Dropped a column at {} {} that arrived across a crossing",
					packet.x(), packet.z());
		}
	}
}
