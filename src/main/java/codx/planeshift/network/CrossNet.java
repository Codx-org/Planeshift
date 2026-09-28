package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;
import codx.planeshift.crossing.CrossingClaims;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * The crossing claim: the client has already stepped through a plane and says so.
 *
 * <p>A client that moves itself and reports afterwards is a free teleport unless the
 * server checks the claim, so it sends what it did — which plane, and the movement that
 * went through it — and the server confirms that really happened before following.
 */
public final class CrossNet {
	private CrossNet() {
	}

	/**
	 * C2S: I crossed this plane, going from {@code from} to {@code to}.
	 *
	 * @param planeId the plane, named the same way a watch names one
	 */
	public record Cross(int planeId, Vec3 from, Vec3 to, Vec3 velocity, float yRot, float xRot)
			implements CustomPacketPayload {
		public static final Type<Cross> TYPE = new Type<>(Planeshift.id("cross"));

		public static final StreamCodec<ByteBuf, Cross> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Cross::planeId,
				Vec3.STREAM_CODEC, Cross::from,
				Vec3.STREAM_CODEC, Cross::to,
				Vec3.STREAM_CODEC, Cross::velocity,
				ByteBufCodecs.FLOAT, Cross::yRot,
				ByteBufCodecs.FLOAT, Cross::xRot,
				Cross::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** S2C: no, and why. The client puts everything back the way it was. */
	public record Refused(int planeId, String reason) implements CustomPacketPayload {
		public static final Type<Refused> TYPE = new Type<>(Planeshift.id("cross_refused"));

		public static final StreamCodec<ByteBuf, Refused> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Refused::planeId,
				ByteBufCodecs.STRING_UTF8, Refused::reason,
				Refused::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Cross.TYPE, Cross.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Refused.TYPE, Refused.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Cross.TYPE,
				(payload, context) -> CrossingClaims.claim(context.player(), payload));
	}
}
