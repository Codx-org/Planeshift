package codx.planeshift.client.render;

import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneShape;
import codx.planeshift.plane.PlaneTransform;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * A plane written the way the composite shader wants it: a centre, two unit axes along
 * the surface, a normal, and how far the opening reaches along each axis.
 *
 * <p>The shader casts a ray per pixel and asks whether it lands inside the opening, so
 * everything here is a frame the answer can be given in — {@code |(hit - centre) · right|}
 * against {@link #halfWidth()}, the same along {@code up}. That suits both shapes: an
 * infinite plane is the same test with a reach nothing can exceed.
 */
record PlaneGeometry(Vec3 centre, Vec3 right, Vec3 up, Vec3 normal, float halfWidth, float halfHeight) {
	/**
	 * A reach no ray can pass, for a plane that has no edges. Large enough that the test
	 * never fires, small enough to stay exact in a float.
	 */
	private static final float UNBOUNDED = 1.0E9F;

	static PlaneGeometry of(Plane plane, Vec3 eye) {
		Direction facing = plane.facing();
		Direction.Axis axis = facing.getAxis();
		Vec3 right = tangent(axis, true);
		Vec3 up = tangent(axis, false);
		Vec3 normal = facing.getUnitVec3();

		return switch (plane.shape()) {
			case PlaneShape.Rectangle rect -> {
				Vec3 size = rect.max().subtract(rect.min());
				yield new PlaneGeometry(plane.anchor(), right, up, normal,
						(float) Math.abs(size.dot(right)) / 2.0F,
						(float) Math.abs(size.dot(up)) / 2.0F);
			}
			// Any point of an infinite plane's surface would do as its centre; the one
			// straight out from the eye keeps the numbers small, which matters because
			// the shader works in camera-relative coordinates at float precision.
			case PlaneShape.Infinite ignored -> new PlaneGeometry(
					onSurface(eye, axis, plane.surface()), right, up, normal, UNBOUNDED, UNBOUNDED);
		};
	}

	/**
	 * The same plane seen from the other side of a transform: the far plane of a pair,
	 * derived rather than looked up. A rigid transform keeps the opening's size, so only
	 * the centre and the three axes move.
	 */
	PlaneGeometry through(PlaneTransform transform, Vec3 anchor) {
		return new PlaneGeometry(transform.position(centre, anchor), transform.velocity(right),
				transform.velocity(up), transform.velocity(normal), halfWidth, halfHeight);
	}

	/** One of the two unit axes the surface runs along, given the axis it faces down. */
	private static Vec3 tangent(Direction.Axis normal, boolean first) {
		return switch (normal) {
			case X -> first ? new Vec3(0.0, 0.0, 1.0) : new Vec3(0.0, 1.0, 0.0);
			case Y -> first ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 0.0, 1.0);
			case Z -> first ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
		};
	}

	private static Vec3 onSurface(Vec3 point, Direction.Axis axis, double surface) {
		return switch (axis) {
			case X -> new Vec3(surface, point.y, point.z);
			case Y -> new Vec3(point.x, surface, point.z);
			case Z -> new Vec3(point.x, point.y, surface);
		};
	}
}
