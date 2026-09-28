package codx.planeshift.registry;

import io.netty.buffer.ByteBuf;

import codx.planeshift.plane.Plane;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A plane together with the id a client names it by.
 *
 * <p>The id is not part of {@link Plane} on purpose. A plane is a value — two planes
 * with the same shape, facing and transform are the same plane — while an id is a
 * registry's opinion about which one you mean. Putting identity in the value would
 * break that equality, and it is exactly that equality which keeps an id stable when a
 * provider re-derives the plane after a chunk reload.
 *
 * <p>Ids are unique within a level, which is enough: a client only ever names a plane
 * in the dimension it is standing in, so the server resolves one against that player's
 * own level.
 */
public record IdentifiedPlane(int id, Plane plane) {
	public static final StreamCodec<ByteBuf, IdentifiedPlane> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, IdentifiedPlane::id,
			Plane.STREAM_CODEC, IdentifiedPlane::plane,
			IdentifiedPlane::new);
}
