package codx.planeshift.plane;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

/**
 * Where a plane's surface sits and how far it extends.
 *
 * <p>A shape does not know its own normal — that belongs to the {@link Plane} as its
 * facing — so the methods here take the direction or axis they need. Both cases lie on
 * a block face: the shape knows which block the surface belongs to, and the facing
 * decides which of that block's two faces it is.
 */
public sealed interface PlaneShape {
	/** Both codecs dispatch on {@link #type()}, so a new variant is added in one place. */
	Codec<PlaneShape> CODEC = Type.CODEC.dispatch("type", PlaneShape::type, Type::mapCodec);

	StreamCodec<ByteBuf, PlaneShape> STREAM_CODEC = Type.STREAM_CODEC.dispatch(PlaneShape::type, Type::streamCodec);

	/** Which variant this is, for the codecs to discriminate on. */
	Type type();

	/**
	 * The coordinate along {@code facing}'s axis that the surface sits on. The sign of
	 * {@code point.get(axis) - surface(facing)} is which side of the plane a point is;
	 * crossing detection watches for that sign changing, in either direction.
	 */
	double surface(Direction facing);

	/** The point on the surface that a {@link PlaneTransform} pivots about. */
	Vec3 anchor(Direction facing);

	/**
	 * Whether a point already known to be on the surface falls within the shape. Only
	 * the two axes tangent to {@code normal} are considered.
	 */
	boolean contains(Vec3 point, Direction.Axis normal);

	/**
	 * How far {@code point} is from the nearest part of the surface. Used to decide who
	 * is close enough to a plane to be shown what is behind it.
	 */
	double distanceTo(Vec3 point, Direction facing);

	/**
	 * A finite rectangle covering the block volume between two corners.
	 *
	 * <p>Flatness is not checked here, because whether two corners are flat depends on
	 * the facing they are paired with. {@link Plane} enforces it.
	 *
	 * @param inset how far inside the block volume the surface sits, in blocks, measured
	 *              from the face the facing points out of. Zero puts it on that face,
	 *              which is right for a plane drawn across a doorway. Half puts it down
	 *              the middle of a one-block-thick sheet, which is right for a plane
	 *              standing in for blocks that are themselves the doorway — a lit nether
	 *              portal, where the surface belongs where the purple sheet was rather
	 *              than half a block in front of it.
	 */
	record Rectangle(BlockPos cornerA, BlockPos cornerB, double inset) implements PlaneShape {
		/** A rectangle whose surface lies on the face itself. */
		public Rectangle(BlockPos cornerA, BlockPos cornerB) {
			this(cornerA, cornerB, 0.0);
		}

		static final MapCodec<Rectangle> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				BlockPos.CODEC.fieldOf("corner_a").forGetter(Rectangle::cornerA),
				BlockPos.CODEC.fieldOf("corner_b").forGetter(Rectangle::cornerB),
				Codec.DOUBLE.optionalFieldOf("inset", 0.0).forGetter(Rectangle::inset)
		).apply(instance, Rectangle::new));

		static final StreamCodec<ByteBuf, Rectangle> STREAM_CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, Rectangle::cornerA,
				BlockPos.STREAM_CODEC, Rectangle::cornerB,
				ByteBufCodecs.DOUBLE, Rectangle::inset,
				Rectangle::new);

		@Override
		public Type type() {
			return Type.RECTANGLE;
		}

		/** The lower corner of the block volume. */
		public Vec3 min() {
			return new Vec3(
					Math.min(cornerA.getX(), cornerB.getX()),
					Math.min(cornerA.getY(), cornerB.getY()),
					Math.min(cornerA.getZ(), cornerB.getZ()));
		}

		/**
		 * The upper corner, exclusive: one past the highest block on each axis, so the
		 * volume covers whole blocks rather than stopping at their origins.
		 */
		public Vec3 max() {
			return new Vec3(
					Math.max(cornerA.getX(), cornerB.getX()) + 1,
					Math.max(cornerA.getY(), cornerB.getY()) + 1,
					Math.max(cornerA.getZ(), cornerB.getZ()) + 1);
		}

		@Override
		public double surface(Direction facing) {
			Direction.Axis axis = facing.getAxis();
			return facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
					? max().get(axis) - inset
					: min().get(axis) + inset;
		}

		@Override
		public Vec3 anchor(Direction facing) {
			// The centre of the rectangle. A corner would serve the maths equally well,
			// but under a quarter turn a corner maps onto a different corner on the far
			// side, which makes pairing two planes by hand a puzzle. Centres map onto
			// centres under every rotation.
			Vec3 centre = min().add(max()).scale(0.5);
			return onSurface(centre, facing, surface(facing));
		}

		@Override
		public double distanceTo(Vec3 point, Direction facing) {
			Vec3 min = min();
			Vec3 max = max();
			Direction.Axis normal = facing.getAxis();
			// The nearest point of the rectangle: on the surface along the normal, and
			// clamped to the edges along the other two.
			return point.distanceTo(new Vec3(
					normal == Direction.Axis.X ? surface(facing) : Mth.clamp(point.x, min.x, max.x),
					normal == Direction.Axis.Y ? surface(facing) : Mth.clamp(point.y, min.y, max.y),
					normal == Direction.Axis.Z ? surface(facing) : Mth.clamp(point.z, min.z, max.z)));
		}

		@Override
		public boolean contains(Vec3 point, Direction.Axis normal) {
			Vec3 min = min();
			Vec3 max = max();

			for (Direction.Axis axis : Direction.Axis.VALUES) {
				if (axis == normal) {
					continue;
				}

				double value = point.get(axis);

				if (value < min.get(axis) || value > max.get(axis)) {
					return false;
				}
			}

			return true;
		}
	}

	/**
	 * A plane with no edges, filling its level on both axes tangent to the facing.
	 *
	 * @param coordinate the block along the facing axis whose face the surface lies on
	 */
	record Infinite(int coordinate) implements PlaneShape {
		/**
		 * The plane whose surface sits at {@code surface}, given which way it faces.
		 *
		 * <p>A surface lies on a block <em>face</em>, so which block it belongs to
		 * depends on the facing: a floor you land on at y=-64 is the top face of the
		 * block below it. Saying the height you mean and letting this work the block
		 * out keeps that off-by-one out of everything upstream.
		 */
		public static Infinite atSurface(int surface, Direction facing) {
			return new Infinite(facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
					? surface - 1
					: surface);
		}

		static final MapCodec<Infinite> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.INT.fieldOf("coordinate").forGetter(Infinite::coordinate)
		).apply(instance, Infinite::new));

		static final StreamCodec<ByteBuf, Infinite> STREAM_CODEC =
				ByteBufCodecs.VAR_INT.map(Infinite::new, Infinite::coordinate);

		@Override
		public Type type() {
			return Type.INFINITE;
		}

		@Override
		public double surface(Direction facing) {
			return facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
					? coordinate + 1
					: coordinate;
		}

		@Override
		public Vec3 anchor(Direction facing) {
			// There is no centre, but an infinite plane is translation-invariant along
			// its tangent axes, so any point on the surface serves equally. The origin
			// keeps the numbers small and a transform's offset readable.
			return onSurface(Vec3.ZERO, facing, surface(facing));
		}

		@Override
		public double distanceTo(Vec3 point, Direction facing) {
			// No edges to be near, so distance is purely how far off the surface it is.
			return Math.abs(point.get(facing.getAxis()) - surface(facing));
		}

		@Override
		public boolean contains(Vec3 point, Direction.Axis normal) {
			return true;
		}
	}

	/**
	 * The discriminator both codecs dispatch on.
	 *
	 * <p>The per-variant codecs are reached through methods rather than held in fields,
	 * so that initialising this enum does not force the records to initialise first.
	 */
	enum Type implements StringRepresentable {
		RECTANGLE("rectangle"),
		INFINITE("infinite");

		static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

		static final StreamCodec<ByteBuf, Type> STREAM_CODEC =
				ByteBufCodecs.idMapper(id -> values()[id], Type::ordinal);

		private final String name;

		Type(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}

		MapCodec<? extends PlaneShape> mapCodec() {
			return switch (this) {
				case RECTANGLE -> Rectangle.MAP_CODEC;
				case INFINITE -> Infinite.MAP_CODEC;
			};
		}

		StreamCodec<? super ByteBuf, ? extends PlaneShape> streamCodec() {
			return switch (this) {
				case RECTANGLE -> Rectangle.STREAM_CODEC;
				case INFINITE -> Infinite.STREAM_CODEC;
			};
		}
	}

	/** Replaces {@code point}'s component along {@code facing}'s axis with {@code surface}. */
	private static Vec3 onSurface(Vec3 point, Direction facing, double surface) {
		return switch (facing.getAxis()) {
			case X -> new Vec3(surface, point.y, point.z);
			case Y -> new Vec3(point.x, surface, point.z);
			case Z -> new Vec3(point.x, point.y, surface);
		};
	}
}
