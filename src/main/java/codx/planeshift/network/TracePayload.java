package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Turns the client's own per-tick trace on or off.
 *
 * <p>Debug scaffolding. A crossing is decided on the client and confirmed on the server,
 * so a step that comes undone is only legible with both sides' accounts of the same tick
 * side by side.
 */
public record TracePayload(boolean on) implements CustomPacketPayload {
	public static final Type<TracePayload> TYPE = new Type<>(Planeshift.id("trace"));

	public static final StreamCodec<ByteBuf, TracePayload> STREAM_CODEC =
			ByteBufCodecs.BOOL.map(TracePayload::new, TracePayload::on);

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, STREAM_CODEC);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
