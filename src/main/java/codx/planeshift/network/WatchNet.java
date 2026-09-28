package codx.planeshift.network;

import io.netty.buffer.ByteBuf;

import codx.planeshift.Planeshift;
import codx.planeshift.destination.PlaneWatches;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The watch handshake: how a client asks to be shown the far side of a plane.
 *
 * <p>The client names a <em>plane</em>, never chunk coordinates, and only ever one in
 * the dimension it is standing in. Everything else — which dimension that leads to,
 * which chunks that means, how many of them — is the server's decision. A client that
 * could name coordinates could stream any part of the world it liked.
 *
 * <p>The handle is the client's own name for the watch, so that a reply can be matched
 * to a request without the server having to invent identifiers.
 */
public final class WatchNet {
	private WatchNet() {
	}

	/** C2S: show me what is behind this plane. */
	public record Watch(int handle, int planeId) implements CustomPacketPayload {
		public static final Type<Watch> TYPE = new Type<>(Planeshift.id("watch"));

		public static final StreamCodec<ByteBuf, Watch> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Watch::handle,
				ByteBufCodecs.VAR_INT, Watch::planeId,
				Watch::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** C2S: stop. */
	public record Unwatch(int handle) implements CustomPacketPayload {
		public static final Type<Unwatch> TYPE = new Type<>(Planeshift.id("unwatch"));

		public static final StreamCodec<ByteBuf, Unwatch> STREAM_CODEC =
				ByteBufCodecs.VAR_INT.map(Unwatch::new, Unwatch::handle);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * S2C: the watch is open, and here is what the client needs to build a level for
	 * that dimension.
	 *
	 * <p>Everything here comes from the server's real {@code ServerLevel}, never from
	 * the level the player is standing in. After a crossing this level becomes the
	 * player's world, so anything guessed would stay wrong afterwards.
	 *
	 * @param centerX the chunk the view is built around: where this player would arrive,
	 *                not where the plane is. An infinite plane has no centre of its own.
	 */
	public record Opened(int handle, ResourceKey<Level> dimension, Holder<DimensionType> dimensionType,
			Settings settings, int centerX, int centerZ, int radius) implements CustomPacketPayload {
		public static final Type<Opened> TYPE = new Type<>(Planeshift.id("watch_opened"));

		public static final StreamCodec<RegistryFriendlyByteBuf, Opened> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Opened::handle,
				ResourceKey.streamCodec(Registries.DIMENSION), Opened::dimension,
				DimensionType.STREAM_CODEC, Opened::dimensionType,
				Settings.STREAM_CODEC, Opened::settings,
				ByteBufCodecs.VAR_INT, Opened::centerX,
				ByteBufCodecs.VAR_INT, Opened::centerZ,
				ByteBufCodecs.VAR_INT, Opened::radius,
				Opened::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The destination's level flags, split out because composite tops out at eight. */
	public record Settings(boolean flat, boolean debug, long seedHash, int seaLevel) {
		public static final StreamCodec<ByteBuf, Settings> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, Settings::flat,
				ByteBufCodecs.BOOL, Settings::debug,
				ByteBufCodecs.LONG, Settings::seedHash,
				ByteBufCodecs.VAR_INT, Settings::seaLevel,
				Settings::new);
	}

	/** S2C: the watch is over, with why — refused outright, or dropped later. */
	public record Closed(int handle, String reason) implements CustomPacketPayload {
		public static final Type<Closed> TYPE = new Type<>(Planeshift.id("watch_closed"));

		public static final StreamCodec<ByteBuf, Closed> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Closed::handle,
				ByteBufCodecs.STRING_UTF8, Closed::reason,
				Closed::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Called from the common entry point: both sides need every type. */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Watch.TYPE, Watch.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Unwatch.TYPE, Unwatch.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Opened.TYPE, Opened.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Closed.TYPE, Closed.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Watch.TYPE,
				(payload, context) -> PlaneWatches.open(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Unwatch.TYPE,
				(payload, context) -> PlaneWatches.close(context.player(), payload.handle(), "unwatched"));
	}
}
