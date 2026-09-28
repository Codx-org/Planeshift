package codx.planeshift;

import com.google.gson.JsonObject;

/**
 * What the world lets through a plane.
 *
 * <p>Server-side, because each of these changes what happens in the world rather than what
 * one machine draws. A client has no say in them, and on a dedicated server they are the
 * only settings that exist.
 */
public final class PlaneshiftRules {
	private static final String ENTITIES_KEY = "entities_cross_planes";
	private static final String FLUIDS_KEY = "fluids_flow_through_planes";
	private static final String BREAKABLE_KEY = "portal_planes_breakable";
	private static final String WATCHES_KEY = "max_far_sides_per_player";

	/**
	 * Whether anything other than a player is carried through a plane it walks into.
	 *
	 * <p>On, because a hole you can walk through but a pig cannot is a stranger thing than
	 * either. Off, only players cross, which is what the mod did before and is worth being
	 * able to go back to: it is the difference between a portal and a doorway for everyone.
	 */
	private static boolean entitiesCross = true;

	/**
	 * Whether a fluid pressing against a plane flows out the other side.
	 *
	 * <p>On. A plane at the floor of a dimension with water standing on it is the obvious
	 * thing to try, and a plane that stops water is a plane with a pane of glass in it.
	 */
	private static boolean fluidsFlow = true;

	/**
	 * Whether the blocks a finite portal plane is made of can be broken through it.
	 *
	 * <p>Off. A plane you can see through is a hole, and pointing at a hole should reach
	 * what is beyond it — so the blocks standing in for the doorway step out of the way of
	 * the crosshair, and a nether portal is put out by breaking its frame, the way it always
	 * was. On, they answer the crosshair again and can be broken directly, which is how
	 * vanilla behaves and is worth being able to go back to.
	 *
	 * <p>This is about <em>finite</em> planes: the ones with an edge, made of blocks
	 * somebody placed. A dimensional plane has no blocks and no edge — it is a property of
	 * the world rather than a thing in it — so there is nothing there to break and this does
	 * not reach it either way.
	 */
	private static boolean portalPlanesBreakable;

	/**
	 * How many far sides the server will stream to one player at once.
	 *
	 * <p>The server's own say, because it is the one paying: every far side is a slice of a
	 * second world held open and sent down the wire. A client asking to see through every
	 * doorway in sight is asking this to agree, and on a busy server it may not.
	 *
	 * <p>Minus one agrees to whatever is asked.
	 */
	private static int maxFarSidesPerPlayer = 6;

	private PlaneshiftRules() {
	}

	/** Whether entities other than players cross planes. */
	public static boolean entitiesCross() {
		return entitiesCross;
	}

	/** Whether fluids flow through planes. */
	public static boolean fluidsFlow() {
		return fluidsFlow;
	}

	/**
	 * Whether the blocks making up a finite portal plane can be broken through it.
	 *
	 * <p>Never true of a dimensional plane, which is made of nothing.
	 */
	public static boolean portalPlanesBreakable() {
		return portalPlanesBreakable;
	}

	/** How many far sides one player may have open, or -1 for as many as they ask for. */
	public static int maxFarSidesPerPlayer() {
		return maxFarSidesPerPlayer;
	}

	public static void load() {
		JsonObject root = ConfigFile.read();
		boolean complete = root.has(ENTITIES_KEY) && root.has(FLUIDS_KEY)
				&& root.has(BREAKABLE_KEY) && root.has(WATCHES_KEY);

		if (root.has(ENTITIES_KEY)) {
			entitiesCross = root.get(ENTITIES_KEY).getAsBoolean();
		}

		if (root.has(FLUIDS_KEY)) {
			fluidsFlow = root.get(FLUIDS_KEY).getAsBoolean();
		}

		if (root.has(BREAKABLE_KEY)) {
			portalPlanesBreakable = root.get(BREAKABLE_KEY).getAsBoolean();
		}

		if (root.has(WATCHES_KEY)) {
			maxFarSidesPerPlayer = root.get(WATCHES_KEY).getAsInt();
		}

		if (!complete) {
			// Written back so the settings are visible in the file rather than something
			// you have to know the name of to use. A line of explanation with each, in the
			// same shape as the ones already in there.
			root.addProperty("_" + ENTITIES_KEY,
					"Whether mobs, items and anything else are carried through a plane. "
							+ "Off leaves planes as doorways for players only.");
			root.addProperty(ENTITIES_KEY, entitiesCross);
			root.addProperty("_" + FLUIDS_KEY,
					"Whether water and lava pressing against a plane flow out the other side. "
							+ "Only fills air or weaker fluid of the same kind; never breaks a block.");
			root.addProperty(FLUIDS_KEY, fluidsFlow);
			root.addProperty("_" + BREAKABLE_KEY,
					"Whether the blocks a see-through portal is made of can be broken through it. "
							+ "Off, the crosshair reaches past them and the portal is put out by "
							+ "breaking its frame. Dimensional planes have no blocks and are never "
							+ "affected.");
			root.addProperty(BREAKABLE_KEY, portalPlanesBreakable);
			root.addProperty("_" + WATCHES_KEY,
					"How many far sides the server will stream to one player at once. Each is a "
							+ "slice of another world held open and sent down the wire. -1 agrees to "
							+ "whatever the client asks for.");
			root.addProperty(WATCHES_KEY, maxFarSidesPerPlayer);
			ConfigFile.write(root);
		}

		Planeshift.LOGGER.info("Planes carry entities: {}; planes carry fluids: {};"
				+ " portal planes breakable: {}", entitiesCross, fluidsFlow, portalPlanesBreakable);
	}
}
