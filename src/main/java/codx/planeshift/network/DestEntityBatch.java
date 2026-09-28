package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Brackets a run of ordinary entity packets that belong to a watch rather than to the
 * level the player is standing in.
 *
 * <p>Vanilla's entity packets carry no dimension, the same way its chunk packet does not:
 * they mean "the level you are in". Chunks solved that by shipping the bytes inside a
 * payload of our own, but entities cannot — there are a dozen packet types involved, and
 * more whenever a mod adds one, so enumerating them would be a list that quietly goes out
 * of date.
 *
 * <p>So the packets are sent as they are, with a marker either side saying which watch
 * they are for. The connection is ordered, so what arrives between the two markers is
 * exactly what was sent between them, and the client points its listener at that watch's
 * level for the length of the run.
 *
 * <p>Entity ids do not need remapping: the server counts them once for the whole game,
 * not once per level, so an entity on the far side can never share an id with one here.
 *
 * @param handle the watch these packets belong to, meaningless when {@code begin} is false
 * @param begin  whether this opens the run or closes it
 */
public record DestEntityBatch(int handle, boolean begin) implements CustomPacketPayload {
	public static final Type<DestEntityBatch> TYPE = new Type<>(Planeshift.id("dest_entity_batch"));

	public static final StreamCodec<ByteBuf, DestEntityBatch> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DestEntityBatch::handle,
			ByteBufCodecs.BOOL, DestEntityBatch::begin,
			DestEntityBatch::new);

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, STREAM_CODEC);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
