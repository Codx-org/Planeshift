package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A line from the client for the server's log, while this is being built.
 *
 * <p>Everything that decides whether a crossing happens is client-side, and on a
 * dedicated server that log is on another machine. Rather than guess at what the client
 * saw, it says so.
 *
 * <p>Debug scaffolding: it goes when crossings are reliable.
 */
public record ClientNote(String text) implements CustomPacketPayload {
	public static final Type<ClientNote> TYPE = new Type<>(Planeshift.id("client_note"));

	public static final StreamCodec<ByteBuf, ClientNote> STREAM_CODEC =
			ByteBufCodecs.STRING_UTF8.map(ClientNote::new, ClientNote::text);

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
				Planeshift.LOGGER.info("[{}] {}", context.player().getName().getString(), payload.text()));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
