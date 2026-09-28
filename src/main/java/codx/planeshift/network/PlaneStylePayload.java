package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * How the client should colour the planes it draws.
 *
 * <p>Separate from {@link PlanesPayload} so that changing a colour does not resend
 * every plane, and so that appearance stays out of the plane data itself — colour is a
 * property of this debug view, not of a plane.
 *
 * @param rainbow when true, cycle hue over time and ignore {@code color}
 * @param color   an {@code 0xRRGGBB} tint; the alpha the plane is drawn at is the
 *                renderer's business, not the sender's
 */
public record PlaneStylePayload(boolean rainbow, int color) implements CustomPacketPayload {
	public static final Type<PlaneStylePayload> TYPE = new Type<>(Planeshift.id("plane_style"));

	public static final StreamCodec<ByteBuf, PlaneStylePayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, PlaneStylePayload::rainbow,
			ByteBufCodecs.INT, PlaneStylePayload::color,
			PlaneStylePayload::new);

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, STREAM_CODEC);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
