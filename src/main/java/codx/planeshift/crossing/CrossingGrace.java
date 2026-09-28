package codx.planeshift.crossing;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * A moment after a crossing in which a player is allowed to be somewhere surprising.
 *
 * <p>A crossing is decided on the client and confirmed a round trip later. In between the
 * player keeps moving, so the first position they report afterwards is not one step from
 * where the server just put them — it is however many steps the round trip took. Falling,
 * that is tens of blocks, and vanilla rightly calls a jump like that illegal and drags
 * them back, losing their speed with it.
 *
 * <p>Vanilla already makes this exception for its own dimension changes, which is what a
 * crossing is; it simply has no way to know one happened. Server thread only.
 */
public final class CrossingGrace {
	/** Long enough for a round trip and the moves already in flight behind it. */
	private static final int TICKS = 20;

	private static final Map<UUID, Integer> LEFT = new HashMap<>();

	private CrossingGrace() {
	}

	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
				server -> LEFT.replaceAll((player, left) -> left - 1));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
				server -> LEFT.values().removeIf(left -> left <= 0));
	}

	/** Called when a crossing has just moved this player. */
	public static void grant(ServerPlayer player) {
		LEFT.put(player.getUUID(), TICKS);
	}

	/** As {@link #active}. Kept because it reads better where a crossing is being decided. */
	public static boolean granted(ServerPlayer player) {
		return active(player);
	}

	/**
	 * Whether this player's next surprising position is one we asked for.
	 *
	 * <p>A plain read. It used to count down as it answered, which made the window twenty
	 * <em>questions</em> rather than twenty ticks — and nothing guaranteed the questions
	 * came. A player who crossed and was then not asked about stayed inside the window for
	 * ever, and everything the window excuses stayed excused with them.
	 */
	public static boolean active(ServerPlayer player) {
		return LEFT.containsKey(player.getUUID());
	}

	public static void forget(ServerPlayer player) {
		LEFT.remove(player.getUUID());
	}
}
