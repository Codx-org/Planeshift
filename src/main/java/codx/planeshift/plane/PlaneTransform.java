package codx.planeshift.plane;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/**
 * Maps positions, velocity and facing from one side of a plane to the other.
 *
 * <p>Positions transform anchor-relative:
 *
 * <pre>{@code p' = rotation(p - anchor) + offset}</pre>
 *
 * where {@code anchor} is the point on the plane being crossed (see
 * {@link Plane#anchor()}) and {@code offset} is the point in {@link #target()} that
 * the anchor maps onto. Rotating about the plane rather than the world origin keeps
 * the transform independent of where in the world the plane happens to sit, which is
 * what lets an infinite plane have a transform at all.
 *
 * <p>Rotation is restricted to quarter turns so that everything stays on the block
 * grid and a plane remains describable by two block corners.
 *
 * <p>There is deliberately no scale component. Vanilla's 1:8 nether ratio decides
 * which portal links to which; it does not describe how a crossing maps. Scaling
 * positions would also mean scaling velocity, which would change an entity's terminal
 * velocity as it fell through.
 *
 * @param target   the dimension on the far side
 * @param offset   where the crossed plane's anchor lands in {@code target}
 * @param rotation the yaw applied to everything crossing, about the anchor
 */
public record PlaneTransform(ResourceKey<Level> target, Vec3 offset, Rotation rotation) {
	public static final Codec<PlaneTransform> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ResourceKey.codec(Registries.DIMENSION).fieldOf("target").forGetter(PlaneTransform::target),
			Vec3.CODEC.fieldOf("offset").forGetter(PlaneTransform::offset),
			Rotation.CODEC.fieldOf("rotation").forGetter(PlaneTransform::rotation)
	).apply(instance, PlaneTransform::new));

	public static final StreamCodec<ByteBuf, PlaneTransform> STREAM_CODEC = StreamCodec.composite(
			ResourceKey.streamCodec(Registries.DIMENSION), PlaneTransform::target,
			Vec3.STREAM_CODEC, PlaneTransform::offset,
			Rotation.STREAM_CODEC, PlaneTransform::rotation,
			PlaneTransform::new);

	/** Maps a position across the plane. */
	public Vec3 position(Vec3 position, Vec3 anchor) {
		return rotate(position.subtract(anchor)).add(offset);
	}

	/**
	 * Maps a velocity across the plane. A velocity is a direction rather than a place,
	 * so only the rotation applies — adding the offset here would teleport the entity
	 * once per tick.
	 */
	public Vec3 velocity(Vec3 velocity) {
		return rotate(velocity);
	}

	public float yaw(float yaw) {
		return Mth.wrapDegrees(yaw + degrees());
	}

	public Direction facing(Direction facing) {
		return rotation.rotate(facing);
	}

	/**
	 * The transform back the other way, to be applied about {@link #offset()}.
	 *
	 * <p>Inverting {@code p' = R(p - anchor) + offset} gives
	 * {@code p = R⁻¹(p' - offset) + anchor}: the anchor and the offset swap places and
	 * the rotation inverts. Nothing needs negating.
	 *
	 * @param source the dimension this transform leads away from
	 * @param anchor the anchor this transform is applied about
	 */
	public PlaneTransform inverse(ResourceKey<Level> source, Vec3 anchor) {
		return new PlaneTransform(source, anchor, inverseRotation());
	}

	/**
	 * Quarter turns by repeated {@link Vec3#rotateClockwise90()}, which is
	 * {@code (x, y, z) -> (-z, y, x)}: exact, where going through {@code yRot} with a
	 * converted angle leaves floating point dust that compounds across a chain of
	 * transforms.
	 */
	private Vec3 rotate(Vec3 v) {
		return switch (rotation) {
			case NONE -> v;
			case CLOCKWISE_90 -> v.rotateClockwise90();
			case CLOCKWISE_180 -> v.rotateClockwise90().rotateClockwise90();
			case COUNTERCLOCKWISE_90 -> v.rotateClockwise90().rotateClockwise90().rotateClockwise90();
		};
	}

	private Rotation inverseRotation() {
		return switch (rotation) {
			case NONE -> Rotation.NONE;
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
		};
	}

	/** Minecraft yaw increases clockwise seen from above, matching {@link Rotation}. */
	private float degrees() {
		return switch (rotation) {
			case NONE -> 0.0F;
			case CLOCKWISE_90 -> 90.0F;
			case CLOCKWISE_180 -> 180.0F;
			case COUNTERCLOCKWISE_90 -> -90.0F;
		};
	}
}
