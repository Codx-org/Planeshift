package codx.planeshift.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import codx.planeshift.Planeshift;
import codx.planeshift.interact.FarHands;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

/**
 * Reaching through a plane: the player's own block packets, said of another dimension.
 *
 * <p>Vanilla's block packets carry no dimension, because there has never been anywhere
 * else they could mean. Rather than inventing new ones, these carry the original packet's
 * bytes with the plane's id in front — so the server decodes exactly what the client built
 * and handles it with the same code, and nothing has to be kept in step as vanilla's
 * packets change.
 */
public final class FarNet {
	/** Mining, in all three of its parts, on the other side of a plane. */
	public record Action(int planeId, byte[] body) implements CustomPacketPayload {
		public static final Type<Action> TYPE = new Type<>(Planeshift.id("far_action"));

		public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Action::planeId,
				ByteBufCodecs.BYTE_ARRAY, Action::body,
				Action::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Using an item on, or interacting with, a block on the other side of a plane. */
	public record UseOn(int planeId, byte[] body) implements CustomPacketPayload {
		public static final Type<UseOn> TYPE = new Type<>(Planeshift.id("far_use_on"));

		public static final StreamCodec<ByteBuf, UseOn> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, UseOn::planeId,
				ByteBufCodecs.BYTE_ARRAY, UseOn::body,
				UseOn::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Using an item into space on the other side of a plane: emptying a bucket, above all. */
	public record UseItem(int planeId, byte[] body) implements CustomPacketPayload {
		public static final Type<UseItem> TYPE = new Type<>(Planeshift.id("far_use_item"));

		public static final StreamCodec<ByteBuf, UseItem> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, UseItem::planeId,
				ByteBufCodecs.BYTE_ARRAY, UseItem::body,
				UseItem::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * The server has dealt with a far action, so the client can stop predicting it.
	 *
	 * <p>The far side has its own prediction handler with its own sequence numbers, and
	 * vanilla's acknowledgement names the level the player is in. Without this the
	 * prediction is never closed, and the level goes on preferring the guess to everything
	 * the server says afterwards.
	 *
	 * <p>Sent after the tick's block changes have gone out, not while the action is being
	 * handled. An acknowledgement drops the guess and takes what the level actually holds,
	 * so arriving first means the block flicks back to what it was and then changes again.
	 *
	 * @param planeId  the plane that was reached through, which names the far side
	 * @param sequence the sequence the client used
	 */
	public record Ack(int planeId, int sequence) implements CustomPacketPayload {
		public static final Type<Ack> TYPE = new Type<>(Planeshift.id("far_ack"));

		public static final StreamCodec<ByteBuf, Ack> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Ack::planeId,
				ByteBufCodecs.VAR_INT, Ack::sequence,
				Ack::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private FarNet() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UseOn.TYPE, UseOn.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UseItem.TYPE, UseItem.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Ack.TYPE, Ack.STREAM_CODEC);
	}

	/**
	 * Handlers run on the server thread, so they may touch levels directly.
	 *
	 * <p>The acknowledgement flush is registered here, which is after the streamer's own
	 * end-of-tick work — so a tick's block changes are on the wire before the
	 * acknowledgements that tell the client to trust them.
	 */
	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, context) -> {
			ServerboundPlayerActionPacket packet =
					ServerboundPlayerActionPacket.STREAM_CODEC.decode(read(payload.body()));
			FarHands.action(context.player(), payload.planeId(), packet);
			FarHands.ack(context.player(), payload.planeId(), packet.getSequence());
		});
		ServerPlayNetworking.registerGlobalReceiver(UseOn.TYPE, (payload, context) -> {
			ServerboundUseItemOnPacket packet =
					ServerboundUseItemOnPacket.STREAM_CODEC.decode(read(payload.body()));
			FarHands.useOn(context.player(), payload.planeId(), packet);
			FarHands.ack(context.player(), payload.planeId(), packet.sequence());
		});
		ServerPlayNetworking.registerGlobalReceiver(UseItem.TYPE, (payload, context) -> {
			ServerboundUseItemPacket packet =
					ServerboundUseItemPacket.STREAM_CODEC.decode(read(payload.body()));
			FarHands.useItem(context.player(), payload.planeId(), packet);
			FarHands.ack(context.player(), payload.planeId(), packet.sequence());
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
				FarHands::flushAcks);
		// A plane id means something only in the level it was handed out in, so anything
		// that changes which level a player is in ends whatever they were reaching into.
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> FarHands.forget(handler.getPlayer().getUUID()));
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL
				.register((player, from, to) -> FarHands.forget(player.getUUID()));
	}

	private static FriendlyByteBuf read(byte[] body) {
		return new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
	}
}
