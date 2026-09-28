package codx.planeshift.network;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;
import codx.planeshift.registry.IdentifiedPlane;
import codx.planeshift.registry.Planes;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every plane the client should know about in the dimension it is currently in.
 *
 * <p>A full set rather than deltas: planes change rarely, and a resync is one packet
 * that cannot drift out of step with the server. Once providers are yielding hundreds
 * of planes this wants to become chunk-scoped — {@link PlayerLookup#tracking} is the
 * hook — but that is a cost worth paying only when there is something to pay it for.
 *
 * @param planes the complete set for the receiving player's current dimension, each
 *               with the id the client names it by
 */
public record PlanesPayload(List<IdentifiedPlane> planes) implements CustomPacketPayload {
	public static final Type<PlanesPayload> TYPE = new Type<>(Planeshift.id("planes"));

	public static final StreamCodec<ByteBuf, PlanesPayload> STREAM_CODEC = StreamCodec.composite(
			IdentifiedPlane.STREAM_CODEC.apply(ByteBufCodecs.list()), PlanesPayload::planes,
			PlanesPayload::new);

	/** Registered from the common entry point: both sides need to know the type. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, STREAM_CODEC);
	}

	/** Sends one player the planes for the level they are in. */
	public static void sendTo(ServerPlayer player, ServerLevel level) {
		ServerPlayNetworking.send(player, of(level));
	}

	/** Sends everyone in a level its planes, after something there changed. */
	public static void sendToEveryoneIn(ServerLevel level) {
		PlanesPayload payload = of(level);

		for (ServerPlayer player : PlayerLookup.level(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static PlanesPayload of(ServerLevel level) {
		List<IdentifiedPlane> planes = new ArrayList<>();
		Planes.store(level).forEachKnown(planes::add);
		return new PlanesPayload(planes);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
