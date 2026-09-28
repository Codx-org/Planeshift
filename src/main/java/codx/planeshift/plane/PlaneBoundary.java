package codx.planeshift.plane;

import java.util.Optional;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;

/**
 * Two planes that join, each leading to the other.
 *
 * <p>A plane leads one way. A portal you can come back through is two of them, and
 * their transforms have to be exact inverses — a block of disagreement and you walk
 * into the Nether, walk back, and arrive somewhere you have never been. That reads as
 * a physics bug and is really a data one, so the pairing is derived here rather than
 * written out twice.
 *
 * <p>Inverting {@code p' = R(p - anchor) + offset} gives
 * {@code p = R⁻¹(p' - offset) + anchor}: the anchor and the offset swap and the
 * rotation inverts. Each side's offset is therefore simply the other side's anchor.
 *
 * @param a the plane in the first dimension
 * @param b the plane in the second, leading back
 */
public record PlaneBoundary(Plane a, Plane b) {
	/**
	 * Joins two surfaces so that crossing either arrives at the other.
	 *
	 * <p>The two shapes should be images of one another under {@code rotation}, since
	 * whatever is true of one side's extent has to be true of the other's — a doorway
	 * is the same size on both sides of the wall.
	 *
	 * @param rotation the yaw applied travelling from {@code a} to {@code b}
	 */
	public static PlaneBoundary between(
			ResourceKey<Level> levelA, PlaneShape shapeA, Direction facingA,
			ResourceKey<Level> levelB, PlaneShape shapeB, Direction facingB,
			Rotation rotation) {
		PlaneTransform there = new PlaneTransform(levelB, shapeB.anchor(facingB), rotation);
		PlaneTransform back = there.inverse(levelA, shapeA.anchor(facingA));

		return new PlaneBoundary(
				new Plane(shapeA, facingA, Optional.of(there)),
				new Plane(shapeB, facingB, Optional.of(back)));
	}

	/**
	 * Joins two openings so that walking into one comes out of the other facing away
	 * from it, the way a pair of portals on two walls does.
	 *
	 * <p>The rotation is derived rather than given: entering {@code a} means travelling
	 * along the opposite of its facing, and leaving {@code b} means travelling along
	 * {@code b}'s facing, so the turn is whichever quarter maps one to the other.
	 *
	 * <p>Only a pair of upright openings can be joined this way. A yaw cannot map a
	 * floor onto a wall, so a pair involving one falls back to no rotation — they still
	 * join, you simply arrive facing however you were.
	 */
	public static PlaneBoundary linking(
			ResourceKey<Level> levelA, PlaneShape shapeA, Direction facingA,
			ResourceKey<Level> levelB, PlaneShape shapeB, Direction facingB) {
		return between(levelA, shapeA, facingA, levelB, shapeB, facingB, turn(facingA, facingB));
	}

	/** The quarter turn taking travel into {@code a} to travel out of {@code b}. */
	private static Rotation turn(Direction a, Direction b) {
		Direction entering = a.getOpposite();

		for (Rotation rotation : Rotation.values()) {
			if (rotation.rotate(entering) == b) {
				return rotation;
			}
		}

		// One of them is a floor or a ceiling; no yaw maps that onto the other.
		return Rotation.NONE;
	}

	/**
	 * The same pair, with each end drawn as its own name says.
	 *
	 * <p>Two names rather than one, because the two ends of a doorway are not always meant
	 * to look alike: a portal gun's are a blue one and an orange one, and which you are
	 * standing at is the whole of how you tell them apart.
	 */
	public PlaneBoundary looking(Identifier a, Identifier b) {
		return new PlaneBoundary(a().looking(a), b().looking(b));
	}

	/** Joins a surface to the same coordinates in another dimension. */
	public static PlaneBoundary sameSpot(ResourceKey<Level> levelA, ResourceKey<Level> levelB,
			PlaneShape shape, Direction facing) {
		return between(levelA, shape, facing, levelB, shape, facing, Rotation.NONE);
	}
}
