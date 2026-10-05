package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Asks the client to say why the doorways around it are showing nothing.
 *
 * <p>The question has to be asked of the client, because the client is where it is
 * answered: the server knows what it has agreed to stream and nothing about what was
 * drawn. What comes back goes to the player's chat and to the server's log, so a report
 * can be made by somebody who is only willing to take one screenshot.
 */
public record WhyPayload() implements CustomPacketPayload {
	public static final Type<WhyPayload> TYPE = new Type<>(Planeshift.id("why"));

	public static final StreamCodec<ByteBuf, WhyPayload> STREAM_CODEC =
			StreamCodec.unit(new WhyPayload());

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, STREAM_CODEC);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
