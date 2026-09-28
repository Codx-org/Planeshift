package codx.planeshift.network;

import java.util.List;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Blocks that have changed on the far side of a watch since the last tick.
 *
 * <p>Columns are sent once each, so without this the far side is a photograph: whatever
 * the world looked like when the watch filled. Everything that changes the far side while
 * it is being looked at — another player mining, a fluid spreading, the player's own hand
 * reaching through — arrives here.
 *
 * <p>Positions and states in step, rather than a list of pairs, so the two run as plain
 * varint arrays.
 *
 * @param handle    the watch these belong to, and so which level
 * @param positions packed block positions
 * @param states    block state ids, one per position
 */
public record DestBlockPayload(int handle, List<Long> positions, List<Integer> states)
		implements CustomPacketPayload {
	public static final Type<DestBlockPayload> TYPE = new Type<>(Planeshift.id("dest_block"));

	public static final StreamCodec<ByteBuf, DestBlockPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DestBlockPayload::handle,
			ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), DestBlockPayload::positions,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DestBlockPayload::states,
			DestBlockPayload::new);

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, STREAM_CODEC);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
