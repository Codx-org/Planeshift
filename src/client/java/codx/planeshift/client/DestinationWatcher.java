package codx.planeshift.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import codx.planeshift.Planeshift;
import codx.planeshift.network.WatchNet;
import codx.planeshift.registry.IdentifiedPlane;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Asks the server to show what is behind the portals the player is near.
 *
 * <p>Several at once, because somebody standing among portals will walk through
 * whichever they please, and a far side that was not prepared means a loading screen.
 * Each one costs a renderer and a stream, so the number is small and the range is
 * short.
 */
public final class DestinationWatcher {

	/** The server allows four; one is kept spare for the plane leading back. */
	/**
	 * How many far sides to keep prepared.
	 *
	 * <p>One more than are drawn, so that the next plane you turn towards has already
	 * started streaming rather than beginning when you look at it. Unlimited when the
	 * setting is, in which case what bounds it is how many planes are in range and what the
	 * server is willing to serve.
	 */
	private static int maxWatches() {
		int shown = PlaneshiftConfig.planesSeenThrough();
		return shown == PlaneshiftConfig.ALL_PLANES ? Integer.MAX_VALUE : shown + 1;
	}

	/** Consecutive ticks a watch must go unwanted before it is let go of. */
	private static final int GIVE_UP_AFTER = 40;

	/** How long each watch has been unwanted for, by handle. */
	private static final java.util.Map<Integer, Integer> unwantedFor = new java.util.HashMap<>();

	/** Ticks to wait after a refusal before asking for anything again. */
	private static final int COOLDOWN = 40;

	private static int nextHandle = 1;

	/** Watches asked for, by handle, against the plane each names. */
	private static final Map<Integer, Integer> planeByHandle = new HashMap<>();

	/** Watches the server has confirmed. */
	private static final Map<Integer, Integer> openByHandle = new HashMap<>();

	private static int cooldown;

	private DestinationWatcher() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(WatchNet.Opened.TYPE, (payload, context) -> {
			if (planeByHandle.containsKey(payload.handle())) {
				openByHandle.put(payload.handle(), planeByHandle.get(payload.handle()));
				Planeshift.LOGGER.info("Watch {} opened into {} around chunk {} {}, radius {}",
						payload.handle(), payload.dimension().identifier(),
						payload.centerX(), payload.centerZ(), payload.radius());
				DestinationInbox.opened(context.client(), payload);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(WatchNet.Closed.TYPE, (payload, context) -> {
			boolean refused = planeByHandle.containsKey(payload.handle())
					&& !openByHandle.containsKey(payload.handle());
			Planeshift.LOGGER.info("Watch {} {}: {}", payload.handle(),
					refused ? "refused" : "closed", payload.reason());
			// The world behind it goes with it, or every watch that ever opened is still
			// holding a renderer and a level's worth of meshed chunks.
			DestinationInbox.close(payload.handle());
			drop(payload.handle());

			if (refused) {
				cooldown = COOLDOWN;
			}
		});
	}

	/** Called every client tick; keeps a watch on each nearby portal. */
	public static void tick(Minecraft client) {
		if (client.player == null) {
			forget();
			return;
		}

		if (cooldown > 0) {
			cooldown--;
			return;
		}

		Vec3 eye = client.player.getEyePosition();
		List<IdentifiedPlane> wanted = nearby(eye);

		// Let go of watches on planes that are no longer near, and of any that lost the
		// world behind them -- a crossing takes one without the watch ending, and
		// believing otherwise leaves the client never asking again.
		for (int handle : List.copyOf(planeByHandle.keySet())) {
			int planeId = planeByHandle.get(handle);
			boolean stillWanted = wanted.stream().anyMatch(plane -> plane.id() == planeId);
			boolean lostWorld = openByHandle.containsKey(handle) && !DestinationInbox.holdsWatch(handle);

			if (stillWanted) {
				unwantedFor.remove(handle);
				continue;
			}

			// Not on one tick's evidence. A watch is a whole level renderer and everything
			// streamed into it, and the list it is judged against is replaced wholesale
			// every time the server re-sends it — so a plane can be missing for a moment
			// for reasons that have nothing to do with the player. Dropped on that, a pair
			// of portals opens and closes several times a second, never streams enough to
			// draw, and buries the section workers in the churn.
			if (!lostWorld && unwantedFor.merge(handle, 1, Integer::sum) < GIVE_UP_AFTER) {
				continue;
			}

			ClientPlayNetworking.send(new WatchNet.Unwatch(handle));
			DestinationInbox.close(handle);
			unwantedFor.remove(handle);
			drop(handle);
		}

		for (IdentifiedPlane plane : wanted) {
			if (planeByHandle.size() >= maxWatches()) {
				break;
			}

			if (planeByHandle.containsValue(plane.id())) {
				continue;
			}

			int handle = nextHandle++;
			planeByHandle.put(handle, plane.id());
			ClientPlayNetworking.send(new WatchNet.Watch(handle, plane.id()));
		}
	}

	/** The watch open on this plane, or -1 if there is none. */
	public static int handleFor(int planeId) {
		for (java.util.Map.Entry<Integer, Integer> open : openByHandle.entrySet()) {
			if (open.getValue() == planeId) {
				return open.getKey();
			}
		}

		return -1;
	}

	/** Whether this plane is one the server has a watch open on. */
	public static boolean isWatched(int planeId) {
		return openByHandle.containsValue(planeId);
	}

	public static void forget() {
		planeByHandle.clear();
		openByHandle.clear();
		unwantedFor.clear();
	}

	/** Gives up the watch naming this plane, after crossing it. */
	public static void forgetPlane(int planeId) {
		for (int handle : List.copyOf(planeByHandle.keySet())) {
			if (planeByHandle.get(handle) == planeId) {
				ClientPlayNetworking.send(new WatchNet.Unwatch(handle));
				DestinationInbox.close(handle);
				drop(handle);
			}
		}
	}

	private static void drop(int handle) {
		planeByHandle.remove(handle);
		openByHandle.remove(handle);
	}

	/** The nearest portals in range. Plain planes lead nowhere, so there is nothing behind them. */
	private static List<IdentifiedPlane> nearby(Vec3 eye) {
		List<IdentifiedPlane> found = new ArrayList<>();
		Minecraft client = Minecraft.getInstance();

		for (IdentifiedPlane identified : ClientPlanes.all()) {
			if (!identified.plane().isPortal()
					|| !PlaneshiftConfig.showsFarSide(client, identified.plane())) {
				continue;
			}

			// A plane whose far side is a dimension already held whole — the one just
			// walked out of — needs no watch of its own: that level is still here with
			// its renderer, and DestinationInbox hands it straight back.
			//
			// A plane leading back into the dimension the player is standing in does need
			// one. It is tempting to think it does not, since that world is right here,
			// but the level the player is in is being drawn by the renderer that is
			// drawing this frame; the far side needs a level and a renderer of its own to
			// be drawn from another eye inside the same frame. Two portals on two walls of
			// one room are the ordinary case of that, so skipping it left them blank.
			if (DestinationInbox.keptBehind(
					identified.plane().transform().orElseThrow().target())) {
				continue;
			}

			found.add(identified);
		}

		found.sort((a, b) -> Double.compare(a.plane().distanceTo(eye), b.plane().distanceTo(eye)));
		int keep = maxWatches();
		return found.size() > keep ? found.subList(0, keep) : found;
	}
}
