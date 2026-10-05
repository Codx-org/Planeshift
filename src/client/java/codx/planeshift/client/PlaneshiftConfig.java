package codx.planeshift.client;

import com.google.gson.JsonObject;

import codx.planeshift.ConfigFile;
import codx.planeshift.Planeshift;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneShape;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * What the player has chosen about how much of the far side to draw.
 *
 * <p>Client-side, because every setting here is about what this machine is willing to
 * spend on a frame. Nothing in it changes what the server will agree to.
 */
public final class PlaneshiftConfig {
	private static final String PLANES_KEY = "planes_seen_through";

	/**
	 * How many planes may show their far side at once, by default.
	 *
	 * <p>Each one is a whole extra level render in the same frame, so this is the setting
	 * that decides what standing in a room full of portals costs. Two is enough to see a
	 * pair facing each other, which is the case people build on purpose.
	 *
	 * <p>Minus one shows every plane that is near enough to be drawn at all, however many
	 * that turns out to be — the same escape hatch {@code plane_visible_distance} has, and
	 * the same warning: what it costs is decided by what you build, not by this file.
	 */
	private static final int DEFAULT_PLANES = 2;

	/**
	 * The most that may be asked for by a number.
	 *
	 * <p>Not a limit on what can be shown — {@code -1} asks for every plane in sight and is
	 * honoured. It is a guard against a typo in a hand-edited file turning into a hundred
	 * level renders in one frame.
	 */
	public static final int MAX_PLANES = 16;

	/** Asked for by {@code -1}: every plane near enough to be drawn at all. */
	public static final int ALL_PLANES = -1;

	private static final String ALWAYS_KEY = "planes_visible_at_any_distance";

	private static final String DISTANCE_KEY = "plane_visible_distance";

	private static final String COLOR_KEY = "plane_color";

	private static final String RAINBOW_KEY = "plane_rainbow";

	private static final String FADE_KEY = "plane_fade_distance";

	/**
	 * How far from a plane its far side has faded to nothing, in blocks.
	 *
	 * <p>Tied to the visible distance by default, so a doorway is worth looking through for
	 * exactly as long as it is worth preparing, and the fade is only there to stop it
	 * switching off at a line you can step back and forth across. A smaller number is a
	 * taste, not a saving: the far side is still drawn, it is only drawn fainter.
	 */
	private static final int DEFAULT_FADE = -1;

	private static final String DETAIL_KEY = "plane_detail_at_range";

	private static final String OUTLINES_KEY = "show_plane_outlines";

	/** The colour a plane is drawn in, as six hex digits. Cyan reads well against most terrain. */
	private static final String DEFAULT_COLOR = "00FFFF";

	/**
	 * How far off a plane's own colour is still drawn, in blocks.
	 *
	 * <p>Zero, the default, follows the render distance, so a plane fades out like anything
	 * else built of blocks. A positive number sets the reach directly. Minus one ties it to
	 * the world itself: a doorway is shown exactly as long as the blocks it is cut into are.
	 */
	private static int planeVisibleDistance;

	private static int planeFadeDistance = DEFAULT_FADE;

	private static int planesSeenThrough = DEFAULT_PLANES;

	private static int planeColor = 0x00FFFF;

	private static boolean planeRainbow;

	/**
	 * How much of the frame's resolution a far side is drawn at once its plane is as far off
	 * as planes are ever shown from.
	 *
	 * <p>One is the frame's own resolution however far away the doorway is. Lower spends
	 * less on the ones across the room, which is nearly all of what a far side costs: the
	 * whole of another world drawn at full size, for a view a few hundred pixels across by
	 * the time it reaches the screen. A plane you are standing at is always drawn in full.
	 */
	private static double planeDetailAtRange = 0.5;

	/**
	 * Whether a plane that is not showing its far side is drawn as a coloured sheet.
	 *
	 * <p>Off. It exists so that a doorway drawing nothing can be told apart from no doorway
	 * at all, which mattered when a far side out of range simply went blank — and matters
	 * much less now that distance costs detail rather than the whole view. Worth turning on
	 * while working out where a plane has got to.
	 */
	private static boolean showPlaneOutlines;

	private PlaneshiftConfig() {
	}

	/**
	 * How many planes may show their far side at once, or {@link #ALL_PLANES} for no limit.
	 *
	 * <p>Most callers want {@link #howManyOf} instead.
	 */
	public static int planesSeenThrough() {
		return planesSeenThrough;
	}

	/**
	 * How many of {@code found} planes to show, nearest first.
	 *
	 * <p>All of them when the setting says so. What "all" comes to is decided elsewhere and
	 * is already bounded: only planes near enough to be drawn are ever in this list, and the
	 * server has its own say in how many far sides it will stream to one player.
	 */
	public static int howManyOf(int found) {
		return planesSeenThrough == ALL_PLANES ? found : Math.min(planesSeenThrough, found);
	}

	/**
	 * How far a plane reaches, in blocks, given the render distance to fall back on.
	 *
	 * <p>The cutoff only. Whether a plane this far off is actually shown also depends on the
	 * blocks around it being there at all, which is what {@link #showsFarSide} adds.
	 */
	public static double planeVisibleDistance(int renderDistanceChunks) {
		return planeVisibleDistance > 0 ? planeVisibleDistance : renderDistanceChunks * 16.0;
	}

	/**
	 * How far from a plane its far side has faded to nothing.
	 *
	 * <p>Doorways only. A boundary between two stacked worlds has no edges and is under your
	 * feet as well as out at the horizon, so a number meant for a doorway across a room puts
	 * the world below out of sight long before you are anywhere near it; those use the
	 * visible distance. See {@code SeeThrough.reach}.
	 *
	 * <p>Separate from the visible distance, because the two answer different questions. The
	 * visible distance is how far away a doorway is worth preparing at all — a cost. This is
	 * how far away it is worth <em>looking</em> through — a taste. Left equal, the far side
	 * of a dimension boundary hangs over the sky for a couple of hundred blocks after you
	 * have fallen out of it, which is accurate and not what anybody wants to look at.
	 *
	 * <p>{@code -1} ties it back to the visible distance, which is the older behaviour.
	 */
	public static double planeFadeDistance(int renderDistanceChunks) {
		double visible = planeVisibleDistance(renderDistanceChunks);
		return planeFadeDistance > 0 ? Math.min(planeFadeDistance, visible) : visible;
	}

	/**
	 * Whether this plane is close enough, and solid enough, to show what is behind it.
	 *
	 * <p>The one question all three answers come from: whether to prepare a far side for it,
	 * whether to draw that far side, and whether to draw a coloured sheet in its place.
	 * Keeping them on one answer is what stops a plane being prepared and then not drawn, or
	 * drawn and then not prepared.
	 *
	 * <p>At {@code -1} the distance is the world's own: a doorway is shown exactly as long as
	 * the blocks it is cut into are, and goes when they do. That is a stricter test than a
	 * number, not a looser one — a portal within the render distance but in a chunk that has
	 * not arrived has nothing to be a doorway in yet.
	 */
	public static boolean showsFarSide(Minecraft client, Plane plane) {
		if (client.player == null || client.level == null) {
			return false;
		}

		double away = plane.distanceTo(client.player.getEyePosition());

		if (away > planeVisibleDistance(client.options.getEffectiveRenderDistance())) {
			return false;
		}

		if (planeVisibleDistance != -1) {
			return true;
		}

		if (!(plane.shape() instanceof PlaneShape.Rectangle)) {
			// An edgeless plane is cut into no blocks at all, so there is nothing whose
			// arrival it could be waiting on.
			return true;
		}

		return client.level.hasChunkAt(BlockPos.containing(plane.anchor()));
	}

	/** The colour planes are drawn in until the server says otherwise. */
	public static int planeColor() {
		return planeColor;
	}

	/** Whether planes cycle through the spectrum until the server says otherwise. */
	public static boolean planeRainbow() {
		return planeRainbow;
	}

	/**
	 * How much of the frame a far side seen through this plane should be drawn at.
	 *
	 * <p>Full at the opening, falling off with distance to the setting. Stepped rather than
	 * smooth: see {@link codx.planeshift.client.render.DestinationTarget}.
	 */
	public static float planeDetail(double distance) {
		double range = Math.max(1.0, planeVisibleDistance(
				Minecraft.getInstance().options.getEffectiveRenderDistance()));
		double along = Mth.clamp(distance / range, 0.0, 1.0);
		return codx.planeshift.client.render.DestinationTarget.step(
				(float) Mth.lerp(along, 1.0, planeDetailAtRange));
	}

	/** Whether a plane showing nothing is drawn as a coloured sheet. */
	public static boolean showPlaneOutlines() {
		return showPlaneOutlines;
	}

	public static void load() {
		JsonObject root = ConfigFile.read();

		try {
			if (root.has(PLANES_KEY)) {
				int asked = root.get(PLANES_KEY).getAsInt();
				planesSeenThrough = asked == ALL_PLANES ? ALL_PLANES : Mth.clamp(asked, 1, MAX_PLANES);
			}

			// The older on-or-off setting, kept working: it said the same thing with less
			// of it.
			if (root.has(ALWAYS_KEY) && root.get(ALWAYS_KEY).getAsBoolean()) {
				planeVisibleDistance = -1;
			}

			if (root.has(DISTANCE_KEY)) {
				planeVisibleDistance = root.get(DISTANCE_KEY).getAsInt();
			}

			if (root.has(RAINBOW_KEY)) {
				planeRainbow = root.get(RAINBOW_KEY).getAsBoolean();
			}

			if (root.has(FADE_KEY)) {
				planeFadeDistance = root.get(FADE_KEY).getAsInt();
			}

			if (root.has(DETAIL_KEY)) {
				planeDetailAtRange = Mth.clamp(root.get(DETAIL_KEY).getAsDouble(), 0.25, 1.0);
			}

			if (root.has(OUTLINES_KEY)) {
				showPlaneOutlines = root.get(OUTLINES_KEY).getAsBoolean();
			}

			if (root.has(COLOR_KEY)) {
				// Six hex digits, with or without the hash some people will write anyway.
				String text = root.get(COLOR_KEY).getAsString().trim().replace("#", "");
				planeColor = Integer.parseInt(text, 16) & 0xFFFFFF;
			}

			Planeshift.LOGGER.info("Seeing through up to {} plane(s) at once; planes drawn {}",
					planesSeenThrough,
					planeRainbow ? "in rainbow" : String.format("in %06X", planeColor));
		} catch (RuntimeException e) {
			// A hand-edited file with a typo in it should not stop the game starting.
			Planeshift.LOGGER.warn("Could not read {}; keeping the defaults",
					ConfigFile.path(), e);
		}

		fillIn(root);
	}

	/**
	 * Adds whichever of these settings the file does not have yet, so they can be found by
	 * reading it rather than by knowing their names.
	 *
	 * <p>Only the missing ones, and nothing else in the file is touched: the server's half
	 * of it lives here too, and in single player both halves are written in the same run.
	 */
	private static void fillIn(JsonObject root) {
		if (root.has(PLANES_KEY) && root.has(DISTANCE_KEY) && root.has(COLOR_KEY)
				&& root.has(RAINBOW_KEY) && root.has(DETAIL_KEY) && root.has(OUTLINES_KEY)
				&& root.has(FADE_KEY)) {
			return;
		}

		if (!root.has(PLANES_KEY)) {
			root.addProperty("_" + PLANES_KEY,
					"How many planes may show their far side at once. -1 shows every one in range, "
							+ "however many that is. Each costs a whole extra world render per frame.");
		}

		root.addProperty(PLANES_KEY, planesSeenThrough);
		root.addProperty(DISTANCE_KEY, planeVisibleDistance);

		if (!root.has(FADE_KEY)) {
			root.addProperty("_" + FADE_KEY,
					"How far a doorway lets you see, in blocks: whole up to half of this and gone "
							+ "at it, measured where you are looking rather than to the doorway "
							+ "itself. -1 uses the visible distance instead. It does not apply to "
							+ "a boundary between two stacked worlds, which is a floor rather than "
							+ "a doorway and is shown as far as it is prepared - set "
							+ "plane_visible_distance for those.");
		}

		root.addProperty(FADE_KEY, planeFadeDistance);

		if (!root.has(COLOR_KEY)) {
			root.addProperty(COLOR_KEY, DEFAULT_COLOR);
		}

		root.addProperty(RAINBOW_KEY, planeRainbow);

		if (!root.has(DETAIL_KEY)) {
			root.addProperty("_" + DETAIL_KEY,
					"How much of the screen's resolution the view through a distant plane is drawn "
							+ "at, between 0.25 and 1. Planes close to you are always drawn in full.");
			root.addProperty(DETAIL_KEY, planeDetailAtRange);
		}

		if (!root.has(OUTLINES_KEY)) {
			root.addProperty("_" + OUTLINES_KEY,
					"Whether a plane that is showing nothing is drawn as a coloured sheet. "
							+ "For finding planes while building; off by default.");
			root.addProperty(OUTLINES_KEY, showPlaneOutlines);
		}

		ConfigFile.write(root);
	}
}
