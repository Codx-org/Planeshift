package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One chunk column of a watch, as the bytes of vanilla's own chunk-with-light packet.
 *
 * <p>Bytes rather than the packet itself for two reasons. The server encodes it while
 * deciding what to send, so it can charge the exact size against that player's budget
 * instead of guessing. And the client decodes it when it applies it, so a burst of
 * columns is paced by the work of applying them rather than all landing at once.
 *
 * <p>Vanilla's chunk packet carries no dimension — it means "the level you are in" —
 * which is why this exists at all: the handle says which watch, and so which level.
 *
 * @param handle the watch this column belongs to
 * @param body   an encoded {@code ClientboundLevelChunkWithLightPacket}
 */
public record DestChunkPayload(int handle, byte[] body) implements CustomPacketPayload {
	public static final Type<DestChunkPayload> TYPE = new Type<>(Planeshift.id("dest_chunk"));

	public static final StreamCodec<ByteBuf, DestChunkPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DestChunkPayload::handle,
			ByteBufCodecs.BYTE_ARRAY, DestChunkPayload::body,
			DestChunkPayload::new);

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, STREAM_CODEC);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
