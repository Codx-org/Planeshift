package codx.planeshift.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import codx.planeshift.Planeshift;
import codx.planeshift.client.mixin.ClientPacketListenerAccessor;
import codx.planeshift.network.DestChunkPayload;
import codx.planeshift.network.DestEntityBatch;
import codx.planeshift.network.WatchNet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The far sides the client is holding: one world per open watch, plus the level walked
 * out of most recently.
 *
 * <p>More than one, because a player standing among several portals will walk through
 * whichever they please, and one prepared far side means the rest fall back. Each world
 * carries its own renderer, so this is not free — the count is capped rather than
 * generous.
 */
public final class DestinationInbox {
	/**
	 * Ticks between a world being let go and its renderer actually closing.
	 *
	 * <p>A crossing closes its watch, and that close can reach the client before the
	 * respawn it caused; and a renderer released in the frame it was last drawn with
	 * upsets the driver.
	 */
	private static final int DISPOSE_DELAY = 10;

	private static final Map<Integer, DestinationWorld> worlds = new HashMap<>();

	/** The level the player last walked out of, kept whole for the walk back. */
	private static @Nullable DestinationWorld behind;

	private static final List<Expiring> disposing = new ArrayList<>();

	private record Expiring(DestinationWorld world, int[] ticksLeft) {
	}

	private DestinationInbox() {
	}

	/** The level to put back when the current run of entity packets ends. */
	private static @Nullable ClientLevel routedFrom;

	/**
	 * The level the player is really in, whatever {@code Minecraft.level} says right now.
	 *
	 * <p>{@link #route} lends that field to a far side for a run of entity packets. Anything
	 * asking which dimension the player is in during that window is told the wrong one — and
	 * a tick can land inside it, because packets and ticks both run on the render thread.
	 */
	public static @Nullable ClientLevel playersOwnLevel(Minecraft client) {
		return routedFrom != null ? routedFrom : client.level;
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(DestChunkPayload.TYPE,
				(payload, context) -> accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DestEntityBatch.TYPE,
				(payload, context) -> route(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(codx.planeshift.network.FarNet.Ack.TYPE,
				(payload, context) -> {
					DestinationWorld world = codx.planeshift.client.interact.FarHand
							.worldOf(payload.planeId());

					if (world != null) {
						// Drops the guess the far side's own prediction handler is holding
						// and takes what the server has since sent for it.
						world.level().handleBlockChangedAck(payload.sequence());
					}
				});
		ClientPlayNetworking.registerGlobalReceiver(codx.planeshift.network.DestBlockPayload.TYPE,
				(payload, context) -> {
					DestinationWorld world = worlds.get(payload.handle());

					if (world != null) {
						world.applyBlocks(payload.positions(), payload.states());
					}
				});
	}

	/**
	 * Points the connection at a watch's level for the run of entity packets that
	 * follows, and puts it back afterwards.
	 *
	 * <p>Both the listener's level and the game's are swapped, because vanilla's entity
	 * handlers read from each of them. The window is one run of packets on the render
	 * thread, between frames, and the closing marker cannot be lost or reordered — it
	 * travels the same ordered connection as the packets it closes.
	 */
	private static void route(Minecraft client, DestEntityBatch marker) {
		if (client.getConnection() == null) {
			return;
		}

		if (!marker.begin()) {
			if (routedFrom != null) {
				client.level = routedFrom;
				((ClientPacketListenerAccessor) client.getConnection()).planeshift$setLevel(routedFrom);
				routedFrom = null;
			}

			return;
		}

		DestinationWorld world = worlds.get(marker.handle());

		if (world == null || client.level == null || routedFrom != null) {
			// No world for it, or a run left open by a dropped connection: let the
			// packets fall where they would have anyway rather than stranding the swap.
			return;
		}

		routedFrom = client.level;
		client.level = world.level();
		((ClientPacketListenerAccessor) client.getConnection()).planeshift$setLevel(world.level());
	}

	/** A watch is open and the server has described the dimension: build a world for it. */
	public static void opened(Minecraft client, WatchNet.Opened opened) {
		if (client.level == null) {
			return;
		}

		close(opened.handle());
		DestinationWorld shared = existing(opened);
		DestinationWorld world = shared != null ? shared : DestinationWorld.create(client, opened);
		world.rebind(opened.handle());
		worlds.put(opened.handle(), world);
		DoorwayCrossing.note("destination world " + opened.handle() + " open for "
				+ opened.dimension().identifier() + (shared != null ? " (shared)" : "")
				+ " (" + distinct().size() + " world(s) held)");
	}

	/**
	 * A far side already held for this dimension, for a new watch to share.
	 *
	 * <p>A dimension is one place however many planes lead to it, and a plane with no
	 * edges is always in range — so a player standing near one reliably ends up with
	 * several watches onto the same world. Built fresh for each, those are several levels
	 * and several renderers showing the same thing, and the renderer is handed whichever
	 * the map iterates first: often an empty one still streaming while a built one sits
	 * beside it. That is what an infinite plane looked through into a void.
	 *
	 * <p>The level just walked out of counts too, and is the best of them: whole, with
	 * every section it had compiled.
	 */
	private static @Nullable DestinationWorld existing(WatchNet.Opened opened) {
		for (DestinationWorld world : worlds.values()) {
			// Only when it is looking at the same place. Two doorways into one dimension
			// are one view when they come out beside each other and two views when they do
			// not, and a world can only be centred on one of them — nor can one renderer
			// work out what is visible from two eyes a thousand blocks apart in the same
			// frame. It settles for neither and one of the two openings shows nothing.
			//
			// Deliberately not re-centred when it is shared: it is already streaming around
			// a centre of its own, and moving that would drop what it has for the sake of
			// the newcomer.
			if (world.dimension().equals(opened.dimension())
					&& world.chunksFrom(opened.centerX(), opened.centerZ()) <= opened.radius()) {
				return world;
			}
		}

		if (behind != null && behind.dimension().equals(opened.dimension())) {
			DestinationWorld kept = behind;
			behind = null;
			// This one has no watch centre yet, so it takes the new one's.
			kept.recentre(opened.centerX(), opened.centerZ());
			return kept;
		}

		return null;
	}

	/** Lets go of one watch's world, if no other watch is still showing it. */
	public static void close(int handle) {
		DestinationWorld going = worlds.remove(handle);

		if (going != null && !worlds.containsValue(going)) {
			disposing.add(new Expiring(going, new int[] {DISPOSE_DELAY}));
		}
	}

	public static void closeAll() {
		for (int handle : List.copyOf(worlds.keySet())) {
			close(handle);
		}
	}

	/** The distinct worlds held, however many watches each of them serves. */
	private static Set<DestinationWorld> distinct() {
		Set<DestinationWorld> unique = Collections.newSetFromMap(new IdentityHashMap<>());
		unique.addAll(worlds.values());
		return unique;
	}

	/** Runs the disposal delay down, and keeps what is held moving. */
	public static void tick() {
		for (DestinationWorld world : distinct()) {
			world.tick();
		}

		if (behind != null) {
			behind.tick();
		}

		Iterator<Expiring> each = disposing.iterator();

		while (each.hasNext()) {
			Expiring expiring = each.next();

			if (--expiring.ticksLeft()[0] > 0) {
				continue;
			}

			// Only once its own meshing has gone quiet. Closing a level renderer waits for
			// its section work to finish, on this thread — and this thread is the one the
			// section workers are waiting for to take their finished buffers off them. Wait
			// for them here and neither side moves again: the game stops with every worker
			// busy and the render thread parked, and nothing in the stack says why.
			//
			// So it is asked rather than forced. Whatever is still being built finishes on
			// a later tick, by which time this thread has gone round the loop and unblocked
			// them, and the close costs nothing.
			if (expiring.world().renderer() != null
					&& !expiring.world().renderer().hasRenderedAllSections()) {
				continue;
			}

			expiring.world().dispose();
			each.remove();
		}
	}

	/**
	 * Takes a world for a dimension, giving up ownership of it. Prefers the level just
	 * walked out of, which is whole and already built.
	 *
	 * <p>Taken rather than borrowed because a crossing makes it the player's, and what
	 * it leaves becomes the kept world a moment later; left in both places, that
	 * handover would dispose the renderer the game had just started drawing with.
	 */
	public static @Nullable DestinationWorld take(ResourceKey<Level> dimension) {
		if (behind != null && behind.dimension().equals(dimension)) {
			DestinationWorld kept = behind;
			behind = null;
			return kept;
		}

		for (DestinationWorld world : List.copyOf(worlds.values())) {
			if (world.dimension().equals(dimension)) {
				// Every watch showing it loses it at once, so none is left believing it
				// still has a world behind it.
				worlds.values().removeIf(held -> held == world);
				return world;
			}
		}

		for (Iterator<Expiring> each = disposing.iterator(); each.hasNext();) {
			Expiring expiring = each.next();

			if (expiring.world().dimension().equals(dimension)) {
				each.remove();
				return expiring.world();
			}
		}

		return null;
	}

	/** Takes the level walked out of, for stepping back after a refused crossing. */
	public static @Nullable DestinationWorld takeBehind() {
		DestinationWorld kept = behind;
		behind = null;
		return kept;
	}

	/** Puts a world back among those held, after stepping out of it again. */
	public static void keepAsWatched(DestinationWorld world) {
		disposing.add(new Expiring(world, new int[] {DISPOSE_DELAY}));
	}

	/**
	 * The far side prepared for one plane, by the watch that was opened on it.
	 *
	 * <p>By handle first, because a dimension can have more than one far side held for it
	 * now — two doorways into the Nether coming out in different places are two views, and
	 * asking by dimension alone would hand both of them the first one.
	 */
	public static @Nullable DestinationWorld forPlane(int planeId, ResourceKey<Level> target) {
		int handle = DestinationWatcher.handleFor(planeId);
		DestinationWorld held = handle < 0 ? null : worlds.get(handle);
		// Falling back to the dimension covers the world walked out of, which belongs to no
		// watch at all.
		return held != null ? held : peek(target);
	}

	/** The world held for a dimension, without taking it. */
	public static @Nullable DestinationWorld peek(ResourceKey<Level> dimension) {
		for (DestinationWorld world : worlds.values()) {
			if (world.dimension().equals(dimension)) {
				return world;
			}
		}

		return behind != null && behind.dimension().equals(dimension) ? behind : null;
	}

	/**
	 * Whether the level walked out of is this dimension.
	 *
	 * <p>Only that one. A watch's world is held too, but a watch is exactly what the caller
	 * is deciding whether to open, so answering yes for one would close the watch that is
	 * keeping the answer true.
	 */
	public static boolean keptBehind(ResourceKey<Level> dimension) {
		return behind != null && behind.dimension().equals(dimension);
	}

	/** Whether anything is held for this dimension, without taking it. */
	public static boolean holds(ResourceKey<Level> dimension) {
		if (behind != null && behind.dimension().equals(dimension)) {
			return true;
		}

		return worlds.values().stream().anyMatch(world -> world.dimension().equals(dimension));
	}

	/** Whether this watch has a world behind it. */
	public static boolean holdsWatch(int handle) {
		return worlds.containsKey(handle);
	}

	/** Remembers the level a crossing is taking the player out of, with its engines. */
	public static void keepBehind(@Nullable ClientLevel level, LevelRenderer renderer,
			LevelExtractor extractor, net.minecraft.client.renderer.state.level.LevelRenderState state) {
		if (level == null) {
			return;
		}

		if (behind != null && behind.level() != level) {
			behind.dispose();
		}

		behind = DestinationWorld.kept(level, renderer, extractor, state);
		Planeshift.LOGGER.info("Keeping {} and its renderer for the walk back",
				level.dimension().identifier());
	}

	public static void forgetAll() {
		closeAll();
		behind = null;
		routedFrom = null;
	}

	/** A short description of what is held, for the log. */
	public static String held() {
		StringBuilder out = new StringBuilder();

		for (Map.Entry<Integer, DestinationWorld> entry : worlds.entrySet()) {
			out.append(out.isEmpty() ? "" : ", ").append("watch ").append(entry.getKey()).append(" -> ")
					.append(entry.getValue().dimension().identifier())
					.append(" ").append(entry.getValue().applied()).append(" columns");
		}

		out.append(" (").append(distinct().size()).append(" world(s))");

		if (behind != null) {
			out.append(out.isEmpty() ? "" : ", ").append("behind ").append(behind.dimension().identifier());
		}

		return out.isEmpty() ? "nothing" : out.toString();
	}

	private static void accept(DestChunkPayload payload) {
		DestinationWorld world = worlds.get(payload.handle());

		if (world != null) {
			world.apply(payload.body());
		}
	}
}
