package codx.planeshift.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.jspecify.annotations.Nullable;

import codx.planeshift.debug.DebugPlanes;
import codx.planeshift.debug.PlaneStyle;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneBoundary;
import codx.planeshift.plane.PlaneShape;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.IdentifiedPlane;
import codx.planeshift.registry.Planes;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.HexColorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /planeshift}, a debug command for placing planes by hand.
 *
 * <p>Server-side: the server owns planes, so it has to be the one creating them. That
 * means the block being looked at comes from a server raycast rather than the client's
 * {@code hitResult}, which trails the client's aim by up to a tick — occasionally it
 * picks a different block than your crosshair showed. Fine for a debug tool, confusing
 * if you do not know it.
 */
public final class PlaneshiftCommand {
	private static final ResourceKey<Level> DEFAULT_TARGET = Level.NETHER;

	private static final Map<UUID, PendingCorner> PENDING = new HashMap<>();
	private static final Map<UUID, ResourceKey<Level>> TARGETS = new HashMap<>();

	/**
	 * How many blocks of room to clear either side of a new portal, per player.
	 *
	 * <p>Nothing by default, because it destroys blocks. Opt in and it is the difference
	 * between a doorway and a hole in a wall: a portal buried in solid rock shows nothing
	 * at all through it, since Minecraft only builds faces where a block meets air, so
	 * there is no surface on the far side to draw and you see straight through to the
	 * first cavity.
	 */
	private static final Map<UUID, Integer> CLEARANCE = new HashMap<>();
	private static final Map<UUID, Pending> PENDING_LINK = new HashMap<>();

	private PlaneshiftCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
			CommandBuildContext buildContext, Commands.CommandSelection selection) {
		// Its own command, and the only one here anybody may run: it changes nothing, reads
		// nothing but the asking player's own game, and the people who need it are the ones
		// reporting that something looks wrong on a server they do not run.
		dispatcher.register(Commands.literal("planeshiftwhy")
				.executes(PlaneshiftCommand::why));

		dispatcher.register(Commands.literal("planeshift")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("corner")
						.executes(ctx -> corner(ctx, false)))
				.then(Commands.literal("infinite")
						.executes(ctx -> infinite(ctx, OptionalInt.empty(), false))
						.then(Commands.argument("coordinate", IntegerArgumentType.integer())
								.executes(ctx -> infinite(ctx,
										OptionalInt.of(IntegerArgumentType.getInteger(ctx, "coordinate")), false))))
				.then(Commands.literal("portal")
						.then(Commands.literal("corner")
								.executes(ctx -> corner(ctx, true)))
						.then(Commands.literal("link")
								.then(Commands.argument("width", IntegerArgumentType.integer(1, 16))
										.then(Commands.argument("height", IntegerArgumentType.integer(1, 16))
												.executes(PlaneshiftCommand::link))))
						.then(Commands.literal("dimension")
								.then(Commands.argument("y", IntegerArgumentType.integer())
										.then(Commands.argument("dimension", DimensionArgument.dimension())
												.then(Commands.argument("other_y", IntegerArgumentType.integer())
														.executes(PlaneshiftCommand::dimension))))))
				.then(Commands.literal("debug")
						.then(Commands.literal("stack")
								.executes(PlaneshiftCommand::stack))
						.then(Commands.literal("trace")
								.executes(PlaneshiftCommand::trace)))
				.then(Commands.literal("clearance")
						.then(Commands.argument("blocks", IntegerArgumentType.integer(0, 16))
								.executes(PlaneshiftCommand::setClearance)))
				.then(Commands.literal("target")
						.then(Commands.argument("dimension", DimensionArgument.dimension())
								.executes(PlaneshiftCommand::setTarget)))
				.then(Commands.literal("color")
						.then(Commands.literal("rainbow")
								.executes(PlaneshiftCommand::colorRainbow))
						.then(Commands.argument("hex", HexColorArgument.hexColor())
								.executes(PlaneshiftCommand::colorHex)))
				.then(Commands.literal("list")
						.executes(PlaneshiftCommand::list))
				.then(Commands.literal("clear")
						.executes(PlaneshiftCommand::clear)));
	}

	/**
	 * Picks the block face being looked at. The first call stores a corner; the second
	 * completes the plane, provided it agrees with the first on facing and is flat.
	 */
	private static int corner(CommandContext<CommandSourceStack> ctx, boolean portal) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		BlockHitResult hit = look(player);

		if (hit == null) {
			source.sendFailure(Component.literal("Look at a block face to pick a corner."));
			return 0;
		}

		BlockPos pos = hit.getBlockPos();
		Direction facing = hit.getDirection();
		PendingCorner first = PENDING.get(player.getUUID());

		if (first == null) {
			PENDING.put(player.getUUID(), new PendingCorner(pos, facing, portal));
			source.sendSuccess(() -> Component.literal("First corner of a " + (portal ? "portal" : "plane")
					+ " at " + describe(pos) + ", facing " + facing.getName() + "."), false);
			return Command.SINGLE_SUCCESS;
		}

		if (first.facing() != facing) {
			// Replaces the pending corner rather than refusing: otherwise one bad first
			// pick leaves you unable to make a plane at all.
			PENDING.put(player.getUUID(), new PendingCorner(pos, facing, portal));
			source.sendFailure(Component.literal("Second corner faces " + facing.getName()
					+ " but the first faces " + first.facing().getName() + ". Started over from this corner."));
			return 0;
		}

		// The first pick decides whether this is a portal; the flavour of the call that
		// completes it is ignored, which is why the first one says which it is.
		PlaneShape.Rectangle shape = new PlaneShape.Rectangle(first.pos(), pos);

		try {
			// Constructed here only to surface a bad corner pair before anything is
			// registered; place() builds the planes it actually keeps.
			new Plane(shape, facing, Optional.empty());
		} catch (IllegalArgumentException e) {
			// Same facing still allows two corners on different slices, e.g. the north
			// faces of two blocks at different z.
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}

		PENDING.remove(player.getUUID());
		return place(source, player, shape, facing, first.portal());
	}

	/**
	 * Creates an infinite plane from the block face being looked at: that face gives the
	 * facing, and the block gives the coordinate unless one is passed explicitly, which
	 * is how you put a plane at the world floor without travelling to it.
	 */
	private static int infinite(CommandContext<CommandSourceStack> ctx, OptionalInt override, boolean portal)
			throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		BlockHitResult hit = look(player);

		if (hit == null) {
			source.sendFailure(Component.literal("Look at a block face to choose the plane's facing."));
			return 0;
		}

		Direction facing = hit.getDirection();
		Direction.Axis axis = facing.getAxis();
		BlockPos pos = hit.getBlockPos();
		int coordinate = override.orElseGet(() -> axis.choose(pos.getX(), pos.getY(), pos.getZ()));

		return place(source, player, new PlaneShape.Infinite(coordinate), facing, portal);
	}

	/**
	 * Joins two dimensions along a horizontal boundary: a plane at {@code y} here and
	 * one at {@code other_y} there, each leading to the other. Nothing rotates, so you
	 * come out at the same x and z you went in at.
	 */
	private static int dimension(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel here = source.getLevel();
		ServerLevel there = DimensionArgument.getDimension(ctx, "dimension");
		int hereY = IntegerArgumentType.getInteger(ctx, "y");
		int thereY = IntegerArgumentType.getInteger(ctx, "other_y");

		// The near plane faces the side the player is standing on: a floor is met from
		// above, a ceiling from below. The far one is met from the other side, coming
		// back, so it faces the other way.
		Direction nearFacing = player.getEyePosition().y >= hereY ? Direction.UP : Direction.DOWN;
		Direction farFacing = nearFacing.getOpposite();

		PlaneBoundary boundary = PlaneBoundary.between(
				here.dimension(), PlaneShape.Infinite.atSurface(hereY, nearFacing), nearFacing,
				there.dimension(), PlaneShape.Infinite.atSurface(thereY, farFacing), farFacing,
				Rotation.NONE);

		DebugPlanes.add(there, boundary.b());
		return added(source, here, boundary.a(), there);
	}

	/**
	 * Places an opening of the given size flat on the face being looked at. The first
	 * one waits; the second is joined to it, so walking into either comes out of the
	 * other facing away from it.
	 *
	 * <p>This is the shape a portal gun wants: fire once for the way in, once for the
	 * way out, and the pair is linked without anyone naming coordinates.
	 */
	private static int link(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		BlockHitResult hit = look(player);

		if (hit == null) {
			source.sendFailure(Component.literal("Look at a block face to place an opening."));
			return 0;
		}

		int width = IntegerArgumentType.getInteger(ctx, "width");
		int height = IntegerArgumentType.getInteger(ctx, "height");
		Direction facing = hit.getDirection();
		PlaneShape.Rectangle shape = faceRectangle(hit.getBlockPos(), facing, width, height);
		ServerLevel here = source.getLevel();
		Pending waiting = PENDING_LINK.remove(player.getUUID());

		if (waiting == null) {
			PENDING_LINK.put(player.getUUID(), new Pending(here.dimension(), shape, facing));
			source.sendSuccess(() -> Component.literal("First opening placed. Place the second to join them."), false);
			return Command.SINGLE_SUCCESS;
		}

		if (!PlaneBoundary.canLink(waiting.facing(), facing)) {
			// Kept, not dropped: the first opening is still where they put it, and the
			// answer is to place the second one somewhere the turn can be expressed.
			PENDING_LINK.put(player.getUUID(), waiting);
			source.sendFailure(Component.literal("Openings facing " + waiting.facing().getName()
					+ " and " + facing.getName() + " cannot be joined: a doorway turns about the "
					+ "vertical only, so a floor pairs with a ceiling, and an upright opening "
					+ "with another upright one. The first one is still waiting."));
			return 0;
		}

		PlaneBoundary boundary = PlaneBoundary.linking(
				waiting.dimension(), waiting.shape(), waiting.facing(),
				here.dimension(), shape, facing);

		ServerLevel first = here.getServer().getLevel(waiting.dimension());

		if (first == null) {
			source.sendFailure(Component.literal("The first opening's dimension is gone."));
			return 0;
		}

		DebugPlanes.add(first, boundary.a());
		return added(source, here, boundary.b(), first == here ? null : first);
	}

	/** A rectangle of the given size lying on one face of a block, centred on it. */
	private static PlaneShape.Rectangle faceRectangle(BlockPos pos, Direction facing, int width, int height) {
		Direction.Axis normal = facing.getAxis();
		// Whichever two axes are not the normal: the first runs across, the second up,
		// except on a floor or ceiling where both run across.
		Direction.Axis across = normal == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
		Direction.Axis up = normal == Direction.Axis.Y ? Direction.Axis.Z : Direction.Axis.Y;

		return new PlaneShape.Rectangle(
				corner(pos, across, -(width - 1) / 2, up, -(height - 1) / 2),
				corner(pos, across, width / 2, up, height / 2));
	}

	private static BlockPos corner(BlockPos pos, Direction.Axis across, int acrossOffset,
			Direction.Axis up, int upOffset) {
		return pos
				.offset(across == Direction.Axis.X ? acrossOffset : 0,
						across == Direction.Axis.Y ? acrossOffset : 0,
						across == Direction.Axis.Z ? acrossOffset : 0)
				.offset(up == Direction.Axis.X ? upOffset : 0,
						up == Direction.Axis.Y ? upOffset : 0,
						up == Direction.Axis.Z ? upOffset : 0);
	}

	/** One half of a pair being placed, waiting for the other. */
	private record Pending(ResourceKey<Level> dimension, PlaneShape.Rectangle shape, Direction facing) {
	}

	private static int setTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ResourceKey<Level> target = DimensionArgument.getDimension(ctx, "dimension").dimension();
		TARGETS.put(player.getUUID(), target);
		ctx.getSource().sendSuccess(() -> Component.literal(
				"Your new planes will target " + target.identifier() + "."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int colorRainbow(CommandContext<CommandSourceStack> ctx) {
		PlaneStyle.setRainbow(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.literal("Planes now cycle through the spectrum."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int colorHex(CommandContext<CommandSourceStack> ctx) {
		int rgb = HexColorArgument.getHexColor(ctx, "hex") & 0xFFFFFF;
		PlaneStyle.setColor(ctx.getSource().getServer(), rgb);
		ctx.getSource().sendSuccess(() -> Component.literal(
				String.format("Planes are now #%06X.", rgb)), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Reads back through {@link Planes#store}, not from {@link DebugPlanes}, so this
	 * reports what the server's index actually holds rather than what was put in.
	 */
	private static int list(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		List<IdentifiedPlane> planes = new ArrayList<>();
		Planes.store(source.getLevel()).forEachKnown(planes::add);

		if (planes.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No planes in this dimension."), false);
			return Command.SINGLE_SUCCESS;
		}

		for (IdentifiedPlane identified : planes) {
			String line = "#" + identified.id() + " " + describe(identified.plane());
			source.sendSuccess(() -> Component.literal(line), false);
		}

		return planes.size();
	}

	/**
	 * Removes the planes placed by hand here. Also drops a half-finished corner, the only
	 * way out of a bad first pick.
	 *
	 * <p>By hand and no others. Every other plane in the world belongs to whichever mod
	 * supplies it — a portal gun holds its pair, a stack of worlds derives its boundaries
	 * from the worlds themselves — and a plane taken from under its owner would be back
	 * the moment the owner was asked again. So the count is of what this command placed,
	 * and it says as much when there is anything else about: "cleared nothing" and "there
	 * is nothing here" look identical otherwise, and the first is what somebody sees when
	 * they try to clear a portal they shot.
	 */
	private static int clear(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		int removed = DebugPlanes.clear(source.getLevel());

		if (source.isPlayer()) {
			PENDING.remove(source.getPlayer().getUUID());
		}

		int[] others = {0};
		Planes.store(source.getLevel()).forEachKnown(each -> others[0]++);

		String message = "Cleared " + removed + " plane(s) placed with this command.";

		if (others[0] > 0) {
			message += " The " + others[0] + " still here belong to the mod that supplies them"
					+ " — a portal gun's pair, a world's own boundary — and go the way that mod"
					+ " takes them away, not this one.";
		}

		String said = message;
		source.sendSuccess(() -> Component.literal(said), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int added(CommandSourceStack source, ServerLevel level, Plane plane,
			@Nullable ServerLevel counterpart) {
		DebugPlanes.add(level, plane);
		String note = counterpart == null ? "" :
				" A matching portal leads back from " + counterpart.dimension().identifier() + ".";
		int id = Planes.store(level).idOf(plane);
		source.sendSuccess(() -> Component.literal("Plane #" + id + " " + describe(plane) + "." + note), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Registers the plane, and for a portal its counterpart in the target dimension so
	 * the trip works both ways. A plain plane leads nowhere on purpose: it is for
	 * checking geometry and rendering without being carried off mid-look.
	 */
	private static int place(CommandSourceStack source, ServerPlayer player,
			PlaneShape shape, Direction facing, boolean portal) {
		ServerLevel here = source.getLevel();

		if (!portal) {
			return added(source, here, new Plane(shape, facing, Optional.empty()), null);
		}

		ResourceKey<Level> targetKey = target(player);
		ServerLevel target = here.getServer().getLevel(targetKey);

		if (target == null) {
			source.sendFailure(Component.literal("This server has no dimension " + targetKey.identifier() + "."));
			return 0;
		}

		// Same coordinates on both sides, so the counterpart is the same surface in the
		// other dimension. Once transforms are more than identity the far shape becomes
		// the near one's image, which is why PlaneBoundary takes both.
		PlaneBoundary boundary = PlaneBoundary.sameSpot(here.dimension(), targetKey, shape, facing);
		DebugPlanes.add(target, boundary.b());

		if (shape instanceof PlaneShape.Rectangle rect) {
			int blocks = CLEARANCE.getOrDefault(player.getUUID(), 0);
			clearRoom(here, rect, facing, blocks);
			clearRoom(target, rect, facing, blocks);
		}

		return added(source, here, boundary.a(), target);
	}

	/** The block face the player is aiming at, or null if they are not aiming at one. */
	private static @Nullable BlockHitResult look(ServerPlayer player) {
		return player.pick(player.blockInteractionRange(), 0.0F, false) instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	/**
	 * Stacks the three vanilla dimensions into one column, for testing.
	 *
	 * <p>The end above, the overworld in the middle, the nether below — joined where each
	 * one runs out. Deliberately fixed: which dimensions stack, and where, is a decision
	 * for whatever mod is building a world out of them, not for the library that carries
	 * people between them. This is here so that library can be tried on its own.
	 */
	private static int stack(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerLevel overworld = source.getServer().getLevel(Level.OVERWORLD);
		ServerLevel nether = source.getServer().getLevel(Level.NETHER);
		ServerLevel end = source.getServer().getLevel(Level.END);

		if (overworld == null || nether == null || end == null) {
			source.sendFailure(Component.literal("This server is missing one of the three dimensions."));
			return 0;
		}

		int cleared = DebugPlanes.clear(overworld) + DebugPlanes.clear(nether) + DebugPlanes.clear(end);

		// The upper one's floor is met from above; the lower one's ceiling from below.
		join(end, floor(end), Direction.UP, overworld, buildTop(overworld));
		join(overworld, floor(overworld), Direction.UP, nether, buildTop(nether));

		source.sendSuccess(() -> Component.literal("Stacked the dimensions"
				+ (cleared > 0 ? ", clearing " + cleared + " old plane(s)" : "") + ": the end above "
				+ buildTop(overworld) + ", the overworld from " + floor(overworld) + " up, the nether "
				+ "below " + buildTop(nether) + "."), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Asks this player's own game why the doorways around them are showing what they are.
	 *
	 * <p>Answered on the client, because that is where every one of those decisions is
	 * made: what is in range, what has streamed, what was drawn, and what the setting
	 * allows. The server only knows what it agreed to send.
	 */
	private static int why(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerPlayNetworking.send(player, new codx.planeshift.network.WhyPayload());
		ctx.getSource().sendSuccess(() -> Component.literal(
				"Asking your game about the doorways around you; the answer follows."), false);
		return Command.SINGLE_SUCCESS;
	}

	/** Starts or stops the per-tick account of where this player is, from both sides. */
	private static int trace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		boolean on = codx.planeshift.debug.CrossingTrace.toggle(player);
		ctx.getSource().sendSuccess(() -> Component.literal(on
				? "Tracing this player every tick, from both sides, into the server log."
				: "Stopped tracing."), false);
		return Command.SINGLE_SUCCESS;
	}

	/** One pair of the stack: a plane in each dimension, each leading to the other. */
	private static void join(ServerLevel here, int hereY, Direction nearFacing,
			ServerLevel there, int thereY) {
		Direction farFacing = nearFacing.getOpposite();
		PlaneBoundary boundary = PlaneBoundary.between(
				here.dimension(), PlaneShape.Infinite.atSurface(hereY, nearFacing), nearFacing,
				there.dimension(), PlaneShape.Infinite.atSurface(thereY, farFacing), farFacing,
				Rotation.NONE);

		DebugPlanes.add(here, boundary.a());
		DebugPlanes.add(there, boundary.b());
	}

	/** One past the highest block that may be placed: the surface met from below. */
	private static int buildTop(ServerLevel level) {
		return level.dimensionType().minY() + level.dimensionType().logicalHeight();
	}

	private static int floor(ServerLevel level) {
		return level.dimensionType().minY();
	}

	private static ResourceKey<Level> target(ServerPlayer player) {
		return TARGETS.getOrDefault(player.getUUID(), DEFAULT_TARGET);
	}

	private static int setClearance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		int blocks = IntegerArgumentType.getInteger(ctx, "blocks");
		CLEARANCE.put(player.getUUID(), blocks);
		ctx.getSource().sendSuccess(() -> Component.literal(blocks == 0
				? "New portals will not clear any blocks."
				: "New portals will clear " + blocks + " block(s) either side, on both sides."), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Empties the space either side of a new portal, in both dimensions.
	 *
	 * <p>The opening's own cross-section, carried the set number of blocks each way along
	 * the facing. Both dimensions, because a doorway with rock against one face is only
	 * half a doorway — and the far side is the one you cannot see to fix.
	 */
	private static void clearRoom(ServerLevel level, PlaneShape.Rectangle rect, Direction facing, int blocks) {
		if (blocks <= 0) {
			return;
		}

		BlockPos a = rect.cornerA();
		BlockPos b = rect.cornerB();
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
				Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
				Math.max(a.getZ(), b.getZ()));
		// Only along the normal: the other two axes are the opening itself, and widening
		// those would clear more than was asked for.
		BlockPos reach = new BlockPos(facing.getStepX(), facing.getStepY(), facing.getStepZ())
				.multiply(blocks);
		BlockPos from = min.subtract(absolute(reach));
		BlockPos to = max.offset(absolute(reach));

		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			if (!level.getBlockState(pos).isAir()) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	private static BlockPos absolute(BlockPos pos) {
		return new BlockPos(Math.abs(pos.getX()), Math.abs(pos.getY()), Math.abs(pos.getZ()));
	}

	private static String describe(Plane plane) {
		String where = switch (plane.shape()) {
			case PlaneShape.Rectangle rect -> describe(rect.cornerA()) + " to " + describe(rect.cornerB());
			case PlaneShape.Infinite infinite -> "infinite at " + infinite.coordinate();
		};

		return where + ", facing " + plane.facing().getName() + ", "
				+ plane.transform()
						.map(transform -> "targeting " + transform.target().identifier())
						.orElse("leading nowhere");
	}

	private static String describe(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private record PendingCorner(BlockPos pos, Direction facing, boolean portal) {
	}
}
