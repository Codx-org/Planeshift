package codx.planeshift.client;

import org.jspecify.annotations.Nullable;

import codx.planeshift.Planeshift;
import codx.planeshift.client.mixin.ClientPacketListenerAccessor;
import codx.planeshift.crossing.CrossingDetector;
import codx.planeshift.network.CrossNet;
import codx.planeshift.plane.Plane;
import codx.planeshift.registry.IdentifiedPlane;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Decides when the player has stepped through a plane, and answers the server's
 * catching up.
 *
 * <p>The client goes first. When its own eye passes a plane whose far side is streamed
 * and built, it steps through then and there and tells the server, rather than asking
 * and waiting a round trip in the doorway. The server checks the claim and follows; its
 * answer arrives as an ordinary respawn, which is absorbed here because the client is
 * already where it says.
 */
public final class DoorwayCrossing {
	/** Swapped, waiting for the server to catch up. */
	private static @Nullable Pending pending;

	/** Ticks the outstanding claim has gone unanswered. */
	private static int waiting;

	/**
	 * How long to wait for the server to confirm a crossing before giving up on it.
	 *
	 * <p>A claim holds back every move the player makes, because a move sent now would
	 * describe a step the server has not agreed to. That is right for the round trip it was
	 * meant to cover, and ruinous past it: nothing else clears the claim, so an answer that
	 * never comes leaves the player mute for the rest of the session — falling on their own
	 * screen, motionless on everyone else's, and unable to be moved even by a command.
	 *
	 * <p>Three seconds is far longer than any round trip and far shorter than noticing.
	 * Giving up costs a correction; not giving up costs the session.
	 */
	private static final int WAIT_LIMIT = 60;

	/** The respawn has been absorbed; the teleport it comes with is next. */

	/**
	 * Client ticks left in which the connection is still catching up with a crossing.
	 *
	 * <p>Stepping across ahead of the server means the server is still sending packets
	 * for the dimension it believes the player is in, and those are already on the wire
	 * when the client swaps. Vanilla never meets this, because it only ever swaps on the
	 * respawn that ends the old dimension's packets.
	 */
	private static int settling;

	/** How long that lasts. Long enough to cover a round trip, short enough to notice. */
	private static final int SETTLING = 40;

	/** Whether a crossing is still settling, and stray packets are to be expected. */
	public static boolean settling() {
		return settling > 0;
	}

	/** Runs the settling window down, and gives up on a claim nothing answered. */
	public static void tick() {
		if (settling > 0) {
			settling--;
		}

		if (pending == null) {
			waiting = 0;
			return;
		}

		if (++waiting <= WAIT_LIMIT) {
			return;
		}

		// Let go and start moving again. Where the player ends up is the server's to decide
		// from here; being put back is a moment's confusion, where staying silent is
		// permanent.
		note("no answer to the crossing into " + pending.world().dimension().identifier()
				+ " after " + WAIT_LIMIT + " ticks; moving again and letting the server say"
				+ " where we are");
		DoorwaySwap.revert(Minecraft.getInstance(), pending.world());
		pending = null;
		waiting = 0;
		lastEye = null;
	}

	/**
	 * Set by a confirmed crossing, and consumed by the one position that follows it.
	 *
	 * <p>The server places an arriving player where they said they were when they
	 * claimed. A claim takes a round trip, and a falling player covers sixty blocks in
	 * one — so that position is already well behind them, and applying it pulls them back
	 * up and stops their fall.
	 *
	 * <p>Exactly one packet, and answered rather than ignored: the acknowledgement is
	 * what lets the server move on, and the arrival grace on the other side is what makes
	 * it accept where the player actually is a tick later.
	 */
	private static boolean crossingCorrection;

	/** Owed silence after an acknowledgement: two position-bearing packets in a tick is a kick. */
	private static boolean acknowledged;

	/** Move packets let through since the last trace line, so silence can be told from rejection. */
	private static int sent;

	/** How many moves the client has emitted since this was last asked, and resets the count. */
	public static int sentSinceLastAsked() {
		int count = sent;
		sent = 0;
		return count;
	}

		private static @Nullable Vec3 lastEye;

	/**
	 * Forgets where the eye was, so the next tick starts a fresh line.
	 *
	 * <p>For anything that moves a player without them walking: the move itself is real,
	 * the path it implies is not.
	 */
	public static void forgetLastEye() {
		lastEye = null;
	}

	private record Pending(int planeId, DestinationWorld world) {
	}

	private DoorwayCrossing() {
	}

	/**
	 * Called as the client is about to tell the server where it is.
	 *
	 * @return whether to send nothing. A crossing's move is never sent — the claim
	 *         replaces it — and nothing is sent from the far side either, until the
	 *         server has put the player there and agreed.
	 */
	public static boolean beforeSendPosition(LocalPlayer player) {
		if (acknowledged) {
			acknowledged = false;
			return true;
		}

		if (pending != null) {
			// A claim is outstanding: a move now would describe a step the server has
			// not agreed to yet.
			codx.planeshift.debug.TraceLog.line("        client HELD BACK move: claim outstanding");
			return true;
		}

		Minecraft client = Minecraft.getInstance();

		if (client.level == null || player.isPassenger() || player.isVehicle()) {
			lastEye = null;
			return false;
		}

		// A dead player does not walk anywhere. Their body stays where it fell, which may
		// be past a plane — under a world's floor, most of the time — and every tick it
		// lies there looks exactly like the moment of crossing. The server will not carry a
		// corpse, so the claim goes unanswered, times out, steps back and starts again,
		// forever, swapping levels underneath a client that is only showing a death screen.
		if (!player.isAlive()) {
			lastEye = null;
			return false;
		}

		Vec3 eye = player.getEyePosition();
		Vec3 before = lastEye;
		lastEye = eye;

		if (before == null) {
			return false;
		}

		IdentifiedPlane crossed = crossedPlane(before, eye);

		if (crossed == null) {
			sent++;
			return false;
		}

		// From here the player really did walk through a plane, so anything that stops
		// the crossing is worth saying out loud.
		Plane plane = crossed.plane();
		int planeId = crossed.id();

		if (plane.transform().isEmpty()) {
			return false;
		}

		if (plane.transform().orElseThrow().target().equals(client.level.dimension())) {
			// Both ends in one dimension: there is no world to step into and no respawn
			// coming, so there would be nothing to wait for and nothing to swap. The
			// server moves the player, which within a dimension costs no loading screen
			// anyway.
			return false;
		}

		// The level walked out of last time is still here and still built, so coming
		// back costs nothing. Otherwise one of the watched far sides.
		DestinationWorld world = DestinationInbox.take(plane.transform().orElseThrow().target());

		if (world == null || world.renderer() == null || world.extractor() == null) {
			// Nothing built for the far side; let the server move the player the slow
			// way rather than stepping into an empty world. It sees the move, because
			// this returns false and the position goes out as usual.
			note("nothing built for " + plane.transform().orElseThrow().target().identifier()
					+ " through plane #" + planeId + "; falling back (held: "
					+ DestinationInbox.held() + ")");
			return false;
		}

		// Claimed before stepping, so the values describe the move the server is still
		// holding rather than one already half applied.
		ClientPlayNetworking.send(new CrossNet.Cross(planeId, before, eye,
				player.getDeltaMovement(), player.getYRot(), player.getXRot()));
		DoorwaySwap.swap(client, plane, world);
		// The plane just crossed is in the other dimension now, and the world behind it
		// has become the player's. Waiting for the server to say so leaves the watch
		// pointing at something that no longer applies.
		DestinationWatcher.forgetPlane(planeId);
		pending = new Pending(planeId, world);
		settling = SETTLING;
		lastEye = null;
		return true;
	}

	/**
	 * The server's answer. It names the level the client is already in, so instead of
	 * building a new one the connection is pointed at the adopted level.
	 *
	 * @return whether this was ours, and vanilla should not run
	 */
	public static boolean onRespawn(ClientPacketListener connection, ClientboundRespawnPacket packet) {
		Pending claim = pending;

		if (claim == null) {
			return false;
		}

		if (!packet.commonPlayerSpawnInfo().dimension().equals(claim.world().dimension())) {
			// The server sent the player somewhere else entirely; let vanilla handle it
			// and lose the swap rather than pretend.
			Planeshift.LOGGER.warn("Crossing answered with {}, not the claimed {}",
					packet.commonPlayerSpawnInfo().dimension().identifier(),
					claim.world().dimension().identifier());
			pending = null;
			return false;
		}

		Minecraft client = Minecraft.getInstance();
		ClientPacketListenerAccessor access = (ClientPacketListenerAccessor) connection;
		access.planeshift$setLevel(claim.world().level());
		access.planeshift$setLevelData((net.minecraft.client.multiplayer.ClientLevel.ClientLevelData)
				claim.world().level().getLevelData());

		if (client.player != null) {
			claim.world().level().getChunkSource().updateViewCenter(
					SectionPos.blockToSectionCoord(client.player.getBlockX()),
					SectionPos.blockToSectionCoord(client.player.getBlockZ()));
		}

		// The watch streamed at its own small radius; from here the server sends this
		// level at the player's.
		claim.world().level().getChunkSource().updateViewRadius(access.planeshift$serverChunkRadius());

		pending = null;
		waiting = 0;
		Planeshift.LOGGER.info("Crossing confirmed: now in {}", claim.world().dimension().identifier());
		return true;
	}

	/**
	 * The teleport that comes with the respawn. Its position is where the player was a
	 * round trip ago, so it is acknowledged and answered with where they are now rather
	 * than applied — applying it would drag them back through the plane.
	 */
	public static boolean onPosition(ClientPacketListener connection, ClientboundPlayerPositionPacket packet) {
		if (crossingCorrection) {
			crossingCorrection = false;
			LocalPlayer player = Minecraft.getInstance().player;

			if (player != null) {
				connection.send(new ServerboundAcceptTeleportationPacket(packet.id(),
						player.getX(), player.getY(), player.getZ(),
						player.getYRot(), player.getXRot()));
				acknowledged = true;
				codx.planeshift.debug.TraceLog.line(
						"        client kept its own position through the arrival");
				return true;
			}
		}

		// Everything else through, always.
		//
		// This used to swallow the server's catch-up for twenty ticks and acknowledge it
		// with the client's own position, on the theory that the server would otherwise
		// drag the player back through the plane. It does not: by the time this arrives
		// the respawn has confirmed the crossing, so the server's position is already on
		// the far side. And 26.3's server ignores the coordinates in an acknowledgement
		// entirely — it places the player at the position it was holding — so insisting
		// only left the two disagreeing.
		//
		// What that cost: after a crossing the client runs a few ticks ahead, the server
		// judges the next move wrongly made and stops committing any of them, and every
		// correction it sends is swallowed. Twenty ticks later the window lapses, one
		// lands, and the player is snapped back fifteen blocks with their fall reset.
		// Taking the small correction immediately is the whole fix.
		//
		// The step the player is about to appear to have taken is not one they took, so it
		// is not measured against the planes. A crossing is the line from where the eye was
		// to where it is; a teleport makes that line pass through everything between two
		// points the player was never between. Without this, an ender pearl thrown past a
		// doorway, a chorus fruit, or a command puts you through it.
		forgetLastEye();
		return false;
	}

	/**
	 * A refusal means the claim did not hold, and the client has stepped somewhere the
	 * server will not follow. Everything goes back: the player returns to the level it
	 * came from, with its engines, and the world it stepped into becomes a kept one
	 * again rather than being thrown away.
	 */
	public static void refused(CrossNet.Refused refused) {
		Pending claim = pending;
		pending = null;
		waiting = 0;
		lastEye = null;

		if (claim == null) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		note("crossing through plane #" + refused.planeId() + " refused: " + refused.reason()
				+ "; stepping back");
		DoorwaySwap.revert(client, claim.world());
	}

	public static void forget() {
		settling = 0;
		waiting = 0;
		crossingCorrection = false;
		acknowledged = false;
		pending = null;
		lastEye = null;
	}

	/** Any plane this movement went through. */
	private static @Nullable IdentifiedPlane crossedPlane(Vec3 before, Vec3 eye) {
		for (IdentifiedPlane identified : ClientPlanes.all()) {
			if (CrossingDetector.test(identified.plane(), before, eye) != null) {
				return identified;
			}
		}

		return null;
	}

	static void note(String text) {
		Planeshift.LOGGER.info(text);
		// Into the trace too, so a snap-back is read with the reasoning beside it rather
		// than as a bare pair of positions that disagree.
		codx.planeshift.debug.TraceLog.line("        client " + text);
		ClientPlayNetworking.send(new codx.planeshift.network.ClientNote(text));
	}

}
