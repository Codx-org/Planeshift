package codx.planeshift.client.mixin;

import io.netty.buffer.Unpooled;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import codx.planeshift.client.interact.FarHand;
import codx.planeshift.network.FarNet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

/**
 * Says which dimension a block packet is about, when it is not this one.
 *
 * <p>Caught here rather than at each place that builds one: the game mode has several ways
 * of sending these and they all end up on the connection, so one gate covers mining,
 * placing and using without touching any of that code.
 *
 * <p>The bytes are the packet's own, so the server decodes exactly what the client built
 * and runs it through vanilla's handler. Only the envelope changes.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class PacketTagMixin {
	@WrapMethod(method = "send")
	private void planeshift$tagFarPackets(Packet<?> packet, Operation<Void> original) {
		int plane = FarHand.through();

		if (plane < 0) {
			original.call(packet);
			return;
		}

		if (packet instanceof ServerboundPlayerActionPacket action) {
			ClientPlayNetworking.send(new FarNet.Action(plane, encode(
					buf -> ServerboundPlayerActionPacket.STREAM_CODEC.encode(buf, action))));
			return;
		}

		if (packet instanceof ServerboundUseItemOnPacket use) {
			ClientPlayNetworking.send(new FarNet.UseOn(plane, encode(
					buf -> ServerboundUseItemOnPacket.STREAM_CODEC.encode(buf, use))));
			return;
		}

		if (packet instanceof ServerboundUseItemPacket use) {
			// Names no position at all — it is the item that works out where it is pointing,
			// from the player. Which is why the server needs to be told it was aimed through
			// a plane; there is nothing in the packet that could say so.
			ClientPlayNetworking.send(new FarNet.UseItem(plane, encode(
					buf -> ServerboundUseItemPacket.STREAM_CODEC.encode(buf, use))));
			return;
		}

		// Swings, punches, held-item changes: they mean the player, not a place, so they
		// travel as themselves.
		original.call(packet);
	}

	private static byte[] encode(java.util.function.Consumer<FriendlyByteBuf> write) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

		try {
			write.accept(buf);
			byte[] body = new byte[buf.readableBytes()];
			buf.readBytes(body);
			return body;
		} finally {
			buf.release();
		}
	}
}
