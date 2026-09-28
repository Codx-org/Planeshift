package codx.planeshift.plane;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;

import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * A region of space that entities cross to reach another dimension.
 *
 * <p>The surface lies on one face of the blocks the {@link PlaneShape} covers: the face
 * that {@code facing} points out of. A rectangle's two corners must therefore agree on
 * their coordinate along the facing axis, which is what makes the region flat.
 *
 * <p>{@code facing} is the plane's orientation: which way its normal points, and so
 * which face of its blocks the surface lies on. It does not restrict travel — a plane
 * is crossed from either side, and the same transform applies both ways.
 *
 * @param shape     where the surface is and how far it extends
 * @param facing    the plane's normal; picks which block face the surface sits on
 * <p>A plane without a transform is just a surface: crossings are still detected, but
 * nothing is carried anywhere. A plane with one is a portal.
 *
 * @param transform  where and how a crossing lands on the far side, if anywhere
 * @param appearance what this plane should be drawn to look like, if the mod that made it
 *                   cares. Planeshift attaches no meaning to the name: it is synced with
 *                   the plane and handed to whatever the client has registered under it,
 *                   which is how a nether portal can shimmer purple and a portal gun's
 *                   doorway can carry a blue rim without the library knowing what either
 *                   of those is. A name nothing is registered under draws nothing, which
 *                   is what makes it safe for a server to run a mod a client does not.
 */
public record Plane(PlaneShape shape, Direction facing, Optional<PlaneTransform> transform,
		Optional<Identifier> appearance) {
	/** A plane with no appearance of its own, which is most of them. */
	public Plane(PlaneShape shape, Direction facing, Optional<PlaneTransform> transform) {
		this(shape, facing, transform, Optional.empty());
	}

	public static final Codec<Plane> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			PlaneShape.CODEC.fieldOf("shape").forGetter(Plane::shape),
			Direction.CODEC.fieldOf("facing").forGetter(Plane::facing),
			PlaneTransform.CODEC.optionalFieldOf("transform").forGetter(Plane::transform),
			Identifier.CODEC.optionalFieldOf("appearance").forGetter(Plane::appearance)
	).apply(instance, Plane::new));

	public static final StreamCodec<ByteBuf, Plane> STREAM_CODEC = StreamCodec.composite(
			PlaneShape.STREAM_CODEC, Plane::shape,
			Direction.STREAM_CODEC, Plane::facing,
			ByteBufCodecs.optional(PlaneTransform.STREAM_CODEC), Plane::transform,
			ByteBufCodecs.optional(Identifier.STREAM_CODEC), Plane::appearance,
			Plane::new);

	public Plane {
		if (shape instanceof PlaneShape.Rectangle rect) {
			Direction.Axis axis = facing.getAxis();
			int a = axis.choose(rect.cornerA().getX(), rect.cornerA().getY(), rect.cornerA().getZ());
			int b = axis.choose(rect.cornerB().getX(), rect.cornerB().getY(), rect.cornerB().getZ());

			if (a != b) {
				throw new IllegalArgumentException("Plane corners must share their " + axis.getName()
						+ " coordinate to be flat, got " + a + " and " + b);
			}
		}
	}

	/** The point the transform pivots about and maps onto its offset. */
	public Vec3 anchor() {
		return shape.anchor(facing);
	}

	/** The coordinate along {@link #facing()}'s axis that the surface sits on. */
	public double surface() {
		return shape.surface(facing);
	}

	/** Whether a point on the surface falls within this plane's bounds. */
	public boolean contains(Vec3 point) {
		return shape.contains(point, facing.getAxis());
	}

	/** How far a point is from the nearest part of this plane's surface. */
	public double distanceTo(Vec3 point) {
		return shape.distanceTo(point, facing);
	}

	/**
	 * The same plane, drawn as {@code name} says.
	 *
	 * <p>Named rather than described, so that what a doorway looks like is decided on the
	 * client by the mod that cares, and the server sends four bytes rather than a texture.
	 */
	public Plane looking(Identifier name) {
		return new Plane(shape, facing, transform, Optional.of(name));
	}

	/** Whether crossing this plane carries you anywhere. */
	public boolean isPortal() {
		return transform.isPresent();
	}
}
