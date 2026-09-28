package codx.planeshift.interact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import codx.planeshift.Planeshift;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.network.FarNet;
import codx.planeshift.registry.Planes;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Doing things to the world on the other side of a plane.
 *
 * <p>A plane is a hole, so reaching through one should work the way reaching through a
 * window works. The client decides what is being pointed at — it is the only one that
 * knows where the crosshair is — and says which plane the reach went through; everything
 * that follows is checked here.
 *
 * <p>Nothing is reimplemented. {@link ServerPlayerGameMode} takes the level it acts on as
 * a field, so it is pointed at the far side for the length of one action and put back.
 * Breaking a block is the exception that needs more than that: it runs over many ticks
 * inside the game mode's own tick, so for as long as one is in progress the level stays
 * swapped for that tick too — see {@code GameModeTickMixin}.
 */
public final class FarHands {
	/**
	 * The far side currently being acted on, per player, while one action runs.
	 *
	 * <p>Read by the reach check: vanilla measures from the player's eye, which is in the
	 * wrong world for this, so while this is set it measures from the eye's place over
	 * there instead.
	 */
	private static final Map<UUID, FarSide> acting = new HashMap<>();

	/** Blocks being mined through a plane, which take more than one tick to finish. */
	private static final Map<UUID, FarSide> breaking = new HashMap<>();

	/**
	 * Acknowledgements owed, held until the end of the tick.
	 *
	 * <p>An acknowledgement tells the client to stop preferring its own guess about a
	 * block, so it has to arrive after the change it is acknowledging. Sent from
	 * {@link #flushAcks}, which runs after the tick's block changes have gone out.
	 */
	private static final Map<UUID, List<FarNet.Ack>> owed = new HashMap<>();

	private FarHands() {
	}

	/** The far side this player is acting on, if an action is being handled right now. */
	public static @Nullable FarSide acting(UUID player) {
		return acting.get(player);
	}

	/** The far side this player is mining into, if any. */
	public static @Nullable FarSide breaking(UUID player) {
		return breaking.get(player);
	}

	/**
	 * Marks a player as acting on a far side, for work that is not a packet.
	 *
	 * <p>Mining is ground down inside the game mode's own tick, and the checks it makes
	 * there — reach above all — need to know they are about another world just as much as
	 * the packet that started it did.
	 *
	 * @return the far side that was set before, to be handed back to {@link #leave}
	 */
	public static @Nullable FarSide enter(UUID player, FarSide far) {
		return acting.put(player, far);
	}

	/** Undoes an {@link #enter}, restoring whatever it displaced. */
	public static void leave(UUID player, @Nullable FarSide before) {
		if (before == null) {
			acting.remove(player);
		} else {
			acting.put(player, before);
		}
	}

	public static void forget(UUID player) {
		acting.remove(player);
		breaking.remove(player);
		FarAim.forget(player);
	}

	/** Notes that the client's guess about this action can be dropped at the end of the tick. */
	public static void ack(ServerPlayer player, int planeId, int sequence) {
		owed.computeIfAbsent(player.getUUID(), key -> new ArrayList<>())
				.add(new FarNet.Ack(planeId, sequence));
	}

	/** Sends the tick's acknowledgements, after its block changes. */
	public static void flushAcks(MinecraftServer server) {
		if (owed.isEmpty()) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			List<FarNet.Ack> mine = owed.remove(player.getUUID());

			if (mine != null) {
				mine.forEach(ack -> ServerPlayNetworking.send(player, ack));
			}
		}

		// Anyone who left between the action and the flush.
		owed.clear();
	}

	/** A block action — start, abort or finish mining — aimed through a plane. */
	public static void action(ServerPlayer player, int planeId, ServerboundPlayerActionPacket packet) {
		FarSide far = resolve(player, planeId);

		if (far == null) {
			return;
		}

		switch (packet.getAction()) {
			case START_DESTROY_BLOCK -> breaking.put(player.getUUID(), far);
			case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> breaking.remove(player.getUUID());
			default -> {
			}
		}

		run(player, far, () -> {
			player.gameMode.handleBlockBreakAction(packet.getPos(), packet.getAction(),
					packet.getDirection(), far.level().getMaxY(), packet.getSequence());
			trace(player, packet.getAction() + " at " + packet.getPos().toShortString()
					+ " in " + far.level().dimension().identifier() + ": now "
					+ far.level().getBlockState(packet.getPos()).getBlock().getName().getString());
		});
	}

	/** Using an item on, or interacting with, a block through a plane. */
	public static void useOn(ServerPlayer player, int planeId, ServerboundUseItemOnPacket packet) {
		FarSide far = resolve(player, planeId);

		if (far == null) {
			return;
		}

		BlockHitResult hit = packet.hitResult();
		BlockPos pos = hit.getBlockPos();

		// What is placed goes in the cell beside the block that was clicked, and that cell
		// can be on either side of the plane. Click the face of a far block that points back
		// at you and what you are putting down belongs in your own world — the space between
		// that block and the doorway is on this side of it.
		BlockHitResult thisSide = comingBackThrough(player, far, hit);

		if (thisSide != null) {
			// Not through the far-side machinery at all: this is an ordinary placement in the
			// level the player is standing in, and everything vanilla does with it is already
			// right.
			InteractionResult result = player.gameMode.useItemOn(player, player.level(),
					player.getItemInHand(packet.hand()), packet.hand(), thisSide);
			trace(player, "placed back through plane #" + planeId + " at "
					+ thisSide.getBlockPos().toShortString() + " in "
					+ player.level().dimension().identifier() + ": " + result);
			return;
		}

		if (!far.level().mayInteract(player, pos)) {
			// Deliberately nothing sent back. A block update naming this position would be
			// read by the client against the level the player is standing in, and would
			// change a block over here to whatever is over there. The acknowledgement that
			// follows already undoes the client's guess, which is the whole correction
			// needed.
			refuse(player, "not allowed to build at " + pos.toShortString()
					+ " in " + far.level().dimension().identifier());
			return;
		}

		run(player, far, () -> {
			ItemStack stack = player.getItemInHand(packet.hand());
			InteractionResult result = player.gameMode.useItemOn(player, far.level(), stack,
					packet.hand(), hit);
			// The block that was clicked and the one a placement would land on, as they
			// stand now. Between them they say whether the server did the thing, which is
			// the one question a block appearing and vanishing cannot answer by itself.
			BlockPos against = pos.relative(hit.getDirection());
			trace(player, "use " + stack.getHoverName().getString() + " on "
					+ pos.toShortString() + " in " + far.level().dimension().identifier()
					+ ": " + result + "; clicked is now "
					+ far.level().getBlockState(pos).getBlock().getName().getString()
					+ ", " + against.toShortString() + " is now "
					+ far.level().getBlockState(against).getBlock().getName().getString());
		});
	}

	/**
	 * Using an item into space through a plane: a bucket above all.
	 *
	 * <p>Unlike the others this names no position. The item works out for itself what it is
	 * pointing at, from the player, which is why {@link FarAim} exists — and why the
	 * player's rotation is taken from the packet first, exactly as vanilla's own handler
	 * does, so that the ray it casts is the one the player aimed.
	 */
	public static void useItem(ServerPlayer player, int planeId, ServerboundUseItemPacket packet) {
		FarSide far = resolve(player, planeId);

		if (far == null) {
			return;
		}

		// The same thing vanilla's own handler does with this packet: the rotation it
		// carries is the one the player aimed with, and is newer than the one the server is
		// holding.
		player.setYRot(packet.yRot());
		player.setXRot(packet.xRot());
		run(player, far, () -> player.gameMode.useItem(player, far.level(),
				player.getItemInHand(packet.hand()), packet.hand()));
	}

	/**
	 * The same placement said in this world's terms, when it belongs in this world.
	 *
	 * <p>The hit that comes back names the <em>cell the block goes in</em> rather than the
	 * block that was clicked, which is not how a hit usually reads. It is how this one has
	 * to read: the block that was clicked is in the other dimension and has no counterpart
	 * here, so there is nothing to name — while the cell itself is empty, and a placement
	 * against an empty cell lands in it.
	 *
	 * @return the near-side hit to place against, or null if the block really does belong on
	 *         the far side after all
	 */
	private static @Nullable BlockHitResult comingBackThrough(ServerPlayer player, FarSide far,
			BlockHitResult hit) {
		BlockPos target = hit.getBlockPos().relative(hit.getDirection());
		BlockPos here = aimOf(player, far).comingBack(hit.getBlockPos(), target);

		if (here == null) {
			return null;
		}

		PlaneTransform back = far.transform().inverse(player.level().dimension(),
				far.plane().anchor());
		return new BlockHitResult(back.position(hit.getLocation(), far.transform().offset()),
				back.facing(hit.getDirection()), here, hit.isInside());
	}

	/**
	 * Runs one action with the player's game mode pointed at the far side.
	 *
	 * <p>The swap is a field assignment and the action is one synchronous call, so nothing
	 * ticks in between and there is no state to reconcile — but it is put back in a
	 * {@code finally} all the same, because a block that throws while being used would
	 * otherwise leave every later action aimed at the wrong world.
	 */
	private static void run(ServerPlayer player, FarSide far, Runnable action) {
		ServerPlayerGameMode mode = player.gameMode;
		ServerLevel home = player.level();
		acting.put(player.getUUID(), far);
		FarAim before = FarAim.enter(player.getUUID(), aimOf(player, far));
		mode.setLevel(far.level());

		try {
			action.run();
		} catch (RuntimeException e) {
			Planeshift.LOGGER.error("Reaching into {} through a plane failed",
					far.level().dimension().identifier(), e);
		} finally {
			mode.setLevel(home);
			FarAim.leave(player.getUUID(), before);
			acting.remove(player.getUUID());
		}
	}

	/**
	 * Where the player's line of sight goes once it is through the plane.
	 *
	 * <p>The whole of their reach, not what is left of it. {@link FarSide#eye()} is not the
	 * opening — it is the player's own eye carried across, which stands as far back from the
	 * far plane as they stand from the near one. Measured from there, the distance to
	 * anything through the plane is already the distance they would have had to it if the
	 * two worlds were one, and taking the walk to the plane off a second time makes the ray
	 * stop short of everything the crosshair says they can touch.
	 */
	private static FarAim aimOf(ServerPlayer player, FarSide far) {
		return new FarAim(far.level(), far.eye(),
				far.transform().velocity(player.getViewVector(1.0F)),
				player.blockInteractionRange(), player.level(), far.plane(), far.transform());
	}

	/**
	 * Checks the claim: that the plane exists where the player is, leads somewhere, and is
	 * close enough to reach.
	 *
	 * <p>Only the plane is checked here, not the block. The block is checked by vanilla's
	 * own reach test, which measures from {@link FarSide#eye()} while this action runs.
	 *
	 * @return the far side, or null if the claim does not hold
	 */
	private static @Nullable FarSide resolve(ServerPlayer player, int planeId) {
		ServerLevel here = player.level();
		Plane plane = Planes.store(here).byId(planeId);

		if (plane == null || plane.transform().isEmpty()) {
			return refused(player, planeId, plane == null ? "no such plane here" : "plane leads nowhere");
		}

		Vec3 eye = player.getEyePosition();

		// Reaching through a plane means standing at it. Measured to the plane rather than
		// along the view, because the server's copy of where the player is looking lags a
		// packet behind and a rejection here reads as an action that silently did nothing.
		if (plane.distanceTo(eye) > player.blockInteractionRange() + REACH_SLACK) {
			return refused(player, planeId, "standing "
					+ String.format("%.1f", plane.distanceTo(eye))
					+ " from the plane, reach is " + player.blockInteractionRange());
		}

		PlaneTransform transform = plane.transform().orElseThrow();
		ServerLevel destination = here.getServer().getLevel(transform.target());

		if (destination == null) {
			return refused(player, planeId, "no level " + transform.target().identifier());
		}

		return new FarSide(plane, transform, destination, transform.position(eye, plane.anchor()));
	}

	/** Says why an action through a plane was not carried out, into the trace. */
	private static @Nullable FarSide refused(ServerPlayer player, int planeId, String why) {
		refuse(player, "reach through plane #" + planeId + " refused: " + why);
		return null;
	}

	/**
	 * Says an action through a plane did not happen, to the trace and to the player.
	 *
	 * <p>To the player as well as the log, because the alternative is what this whole round
	 * has been: something appears for a moment and then is not there, with nothing anywhere
	 * saying why. A refusal the player can read is a refusal that needs no log.
	 */
	private static void refuse(ServerPlayer player, String why) {
		trace(player, why);
		player.sendSystemMessage(Component.literal("Planeshift: " + why)
				.withStyle(ChatFormatting.GRAY));
	}

	/** One line about what a far action did, while {@code /planeshift debug trace} is on. */
	private static void trace(ServerPlayer player, String what) {
		codx.planeshift.debug.TraceLog.line("        far action for "
				+ player.getName().getString() + ": " + what);
	}

	/** How much further than their reach a player may stand from the plane itself. */
	private static final double REACH_SLACK = 1.5;
}
