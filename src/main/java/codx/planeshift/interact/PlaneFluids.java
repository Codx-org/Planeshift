package codx.planeshift.interact;

import codx.planeshift.PlaneshiftRules;
import codx.planeshift.crossing.Crossing;
import codx.planeshift.crossing.CrossingDetector;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.PlaneStore;
import codx.planeshift.registry.Planes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Sends a fluid through a plane instead of across it.
 *
 * <p>Two blocks either side of a plane are not neighbours. They look like neighbours to a
 * fluid, because a plane is not a block and nothing in the world says otherwise, so a
 * fluid poured against one simply flows past it — which is the one thing a doorway must
 * never let anything do.
 *
 * <p>So this does not add a way for fluids to move; it takes one away and puts it back
 * somewhere else. Vanilla decides whether the fluid spreads and how strong it arrives, as
 * it always has. All that changes is which world the answer is written into.
 *
 * <p>When it cannot be written anywhere — the far side is unloaded, or something is already
 * in the way — the spread is still cancelled. A fluid meeting a doorway it cannot get
 * through should stop at it, the way it stops at a wall, rather than carry on as though the
 * doorway were not there.
 */
public final class PlaneFluids {
	private PlaneFluids() {
	}

	/**
	 * Wakes the fluid on the other side of any plane beside this block.
	 *
	 * <p>A fluid block only works out how deep it should be when something tells it to. In
	 * one level that telling is the neighbour update a changing block sends to the six
	 * around it — and a plane is not a block, so it sends nothing, and the far side is never
	 * told that what was feeding it has gone.
	 *
	 * <p>Which is water pouring through a doorway for ever after the bucket is picked back
	 * up: nothing was wrong with the draining, it simply was never asked to happen.
	 *
	 * <p>Only the block against the plane needs waking. Everything below it is fed by
	 * ordinary neighbours in its own level and follows on its own.
	 */
	public static void nudgeAcross(ServerLevel level, BlockPos pos) {
		if (!PlaneshiftRules.fluidsFlow()) {
			return;
		}

		PlaneStore store = Planes.store(level);

		if (store.empty()) {
			return;
		}

		Vec3 here = Vec3.atCenterOf(pos);

		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			Crossing crossing = CrossingDetector.nearest(store, here, Vec3.atCenterOf(neighbour));

			if (crossing != null) {
				wake(level, crossing, Vec3.atCenterOf(neighbour));
			}
		}
	}

	/** Asks the fluid at the far side of this crossing to think again. */
	private static void wake(ServerLevel level, Crossing crossing, Vec3 neighbour) {
		Plane plane = crossing.plane();
		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null) {
			return;
		}

		ServerLevel target = level.getServer().getLevel(transform.target());

		if (target == null) {
			return;
		}

		BlockPos there = BlockPos.containing(transform.position(neighbour, plane.anchor()));

		if (!target.isLoaded(there)) {
			return;
		}

		FluidState fluid = target.getBlockState(there).getFluidState();

		// Already due to think again: a block change next to a busy doorway would otherwise
		// queue one of these per change per tick.
		if (!fluid.isEmpty() && !target.getFluidTicks().hasScheduledTick(there, fluid.getType())) {
			target.scheduleTick(there, fluid.getType(), fluid.getType().getTickDelay(target));
		}
	}

	/**
	 * What a plane is feeding into this block from the other side, if anything.
	 *
	 * <p>The other half of {@link #carry}, and the half without which it does nothing. A
	 * flowing block works out afresh every tick how deep it should be, by asking the
	 * neighbours that feed it — and a block that nothing feeds deletes itself. Fluid put
	 * down on the far side of a plane has no neighbour over there, so without this it
	 * arrives and drains on its very next tick, which looks from the other side like
	 * nothing ever came through.
	 *
	 * <p>So a plane has to be readable in both directions: written through by a fluid
	 * spreading, and read through by a fluid deciding whether it is still being fed.
	 *
	 * @param already what this level alone says should be here
	 * @param dropOff how much a fluid weakens per block, which this fluid knows and the
	 *                far level decides
	 * @return the stronger of what this level says and what the far side is pushing through
	 */
	public static FluidState fedThroughPlane(FlowingFluid type, ServerLevel level, BlockPos pos,
			FluidState already, int dropOff) {
		if (!PlaneshiftRules.fluidsFlow()) {
			return already;
		}

		PlaneStore store = Planes.store(level);

		if (store.empty()) {
			return already;
		}

		FluidState best = already;

		Vec3 here = Vec3.atCenterOf(pos);

		for (Direction direction : FEEDING) {
			FluidState across = reachThrough(type, store, level, here, pos.relative(direction),
					direction, dropOff);

			if (stronger(across, best)) {
				best = across;
			}
		}

		return best;
	}

	/**
	 * The directions a fluid can be fed from.
	 *
	 * <p>The four sides, which feed one step weaker, and above, which feeds a full falling
	 * block. Not below: a fluid is never held up by what is under it, and leaving it out is
	 * also what keeps a pair of joined planes from feeding each other in a circle.
	 */
	private static final Direction[] FEEDING = {
			Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
	};

	/** What the block across a plane in this direction is pushing through, if anything. */
	private static FluidState reachThrough(FlowingFluid type, PlaneStore store, ServerLevel level,
			Vec3 here, BlockPos neighbour, Direction direction, int dropOff) {

		Crossing crossing = CrossingDetector.nearest(store, here, Vec3.atCenterOf(neighbour));

		if (crossing == null) {
			return NOTHING;
		}

		Plane plane = crossing.plane();
		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null) {
			return NOTHING;
		}

		ServerLevel target = level.getServer().getLevel(transform.target());

		if (target == null) {
			return NOTHING;
		}

		BlockPos there = BlockPos.containing(transform.position(Vec3.atCenterOf(neighbour),
				plane.anchor()));

		if (!target.isLoaded(there)) {
			return NOTHING;
		}

		FluidState feeding = target.getBlockState(there).getFluidState();

		if (feeding.isEmpty() || !feeding.getType().isSame(type)) {
			return NOTHING;
		}

		if (direction == Direction.UP) {
			// Coming down through the plane onto this block: a falling column arrives whole,
			// however deep it was above.
			return type.getFlowing(8, true);
		}

		int arriving = feeding.getAmount() - dropOff;
		// A fluid one step from running out gives nothing to the block past the plane, the
		// same as it would give nothing to the block past it in its own level.
		return arriving > 0 ? type.getFlowing(arriving, false) : NOTHING;
	}

	/**
	 * No fluid at all.
	 *
	 * <p>Not {@code getFlowing(0, false)}: a flowing fluid's depth runs from one to eight,
	 * and asking for a zero-deep one throws rather than giving you nothing.
	 */
	private static final FluidState NOTHING = net.minecraft.world.level.material.Fluids.EMPTY
			.defaultFluidState();

	/** Whether {@code candidate} would leave more fluid here than {@code current}. */
	private static boolean stronger(FluidState candidate, FluidState current) {
		if (candidate.isEmpty()) {
			return false;
		}

		if (current.isEmpty()) {
			return true;
		}

		int difference = candidate.getAmount() - current.getAmount();
		// A falling block of the same depth wins, because falling fluid goes on falling
		// rather than settling into a slope.
		return difference > 0 || (difference == 0 && candidate.isSource() == current.isSource()
				&& !current.getValue(FlowingFluid.FALLING) && candidate.getValue(FlowingFluid.FALLING));
	}

	/**
	 * Takes over one step of a fluid's spread, if that step crosses a plane.
	 *
	 * @param from  where the fluid is
	 * @param to    where vanilla is about to put it
	 * @param fluid what vanilla worked out should arrive there
	 * @return whether this was a step through a plane, and so should not also happen here
	 */
	public static boolean carry(ServerLevel level, BlockPos from, BlockPos to, FluidState fluid) {
		if (!PlaneshiftRules.fluidsFlow()) {
			return false;
		}

		PlaneStore store = Planes.store(level);

		if (store.empty()) {
			return false;
		}

		Crossing crossing = CrossingDetector.nearest(store, Vec3.atCenterOf(from), Vec3.atCenterOf(to));

		if (crossing == null) {
			return false;
		}

		Plane plane = crossing.plane();
		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null) {
			// A surface, not a doorway. Crossings of it are reported and nothing goes
			// through, so a fluid passes it as it would pass any other line drawn in the
			// air.
			trace(level, from, to, "plane there leads nowhere");
			return false;
		}

		ServerLevel target = level.getServer().getLevel(transform.target());

		if (target == null) {
			trace(level, from, to, "no level " + transform.target().identifier());
			return true;
		}

		BlockPos there = BlockPos.containing(transform.position(Vec3.atCenterOf(to), plane.anchor()));
		trace(level, from, to, "through plane into " + target.dimension().identifier()
				+ " at " + there.toShortString() + ": " + place(target, there, fluid));
		return true;
	}

	/**
	 * Puts the fluid down on the far side, if there is room for it there.
	 *
	 * @return what happened, for the trace to say
	 */
	private static String place(ServerLevel target, BlockPos there, FluidState fluid) {
		// Never generate terrain from a fluid tick. A fluid pouring into a dimension nobody
		// is looking at would otherwise load and generate chunks at whatever rate it
		// happened to flow, which is a tick cost with no upper bound and nothing watching
		// to justify it.
		if (!target.isLoaded(there)) {
			return "not loaded over there";
		}

		BlockState state = target.getBlockState(there);
		FluidState standing = state.getFluidState();

		if (!standing.isEmpty()) {
			// Something of the same kind is already there. Only a stronger arrival is worth
			// writing, which is also what stops two planes facing each other from feeding
			// one another for ever.
			if (standing.getType() != fluid.getType()
					|| standing.getType().getAmount(standing) >= fluid.getType().getAmount(fluid)) {
				return "already " + standing.getType().getAmount(standing) + " deep there";
			}
		} else if (!state.isAir()) {
			// A block is in the way. Vanilla has a whole protocol for a fluid destroying
			// what it flows into — lava meeting water, a torch washing away — and doing
			// half of it across two levels would be worse than not doing it.
			return state.getBlock().getName().getString() + " is in the way";
		}

		target.setBlock(there, fluid.createLegacyBlock(), Block.UPDATE_ALL);
		return "placed";
	}

	/**
	 * Says what one step of spreading did, while {@code /planeshift debug trace} is on.
	 *
	 * <p>Only for levels that have planes in them, which is the check the caller has
	 * already made — otherwise this would be a line per fluid block per tick for the whole
	 * server.
	 */
	private static void trace(ServerLevel level, BlockPos from, BlockPos to, String what) {
		if (!codx.planeshift.debug.TraceLog.on()) {
			// Asked first, because this runs once per spreading fluid per tick and building
			// the line is most of the cost. Left unguarded it was every fluid in every level
			// paying for a string nobody reads.
			return;
		}

		codx.planeshift.debug.TraceLog.line("        fluid " + from.toShortString() + " -> "
				+ to.toShortString() + " in " + level.dimension().identifier() + ": " + what);
	}
}
