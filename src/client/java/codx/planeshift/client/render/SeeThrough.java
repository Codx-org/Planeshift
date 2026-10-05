package codx.planeshift.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;

import com.mojang.blaze3d.pipeline.TextureTarget;

import codx.planeshift.Planeshift;
import codx.planeshift.client.ClientPlanes;
import codx.planeshift.client.DestinationInbox;
import codx.planeshift.client.DestinationWorld;
import codx.planeshift.client.PlaneshiftConfig;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneShape;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.IdentifiedPlane;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Decides which planes, if any, are worth looking through this frame, draws their far
 * sides, and then shows those images through their openings.
 *
 * <p>How many is the player's to choose: each one is a whole extra level render inside
 * the same frame, which is a cost only they can judge. The nearest that are actually on
 * screen win, because those are the ones being looked through.
 *
 * <p>The two halves straddle the frame's own level render, because the composite has to
 * read the depth this world was drawn with. What is chosen before is held here until
 * after.
 */
public final class SeeThrough {

	/**
	 * How much to fatten a plane's bounds by before testing them, in blocks. A plane has
	 * no thickness, and a box with a zero-length side sits exactly on the frustum's edge
	 * when looked at side-on — the case where the answer matters most.
	 */
	private static final double EDGE = 0.5;

	/** One plane drawn this frame, the image its far side went into, and how far it may see. */
	private record Shown(Plane plane, TextureTarget image, float reach) {
	}

	/** This frame's planes, nearest first. Filled by {@link #beforeLevel}, drained after. */
	private static final List<Shown> shown = new ArrayList<>();

	/**
	 * The planes whose far side was actually drawn, kept past the frame that drew them so
	 * that anything else can ask.
	 *
	 * <p>A frame behind whatever reads it, which for a debug overlay is invisible and for
	 * anything else would be the wrong thing to ask anyway.
	 */
	private static final Set<Plane> showing = new HashSet<>();

	/** The size settled on for each far side this frame, so a shared one is sized once. */
	private static final java.util.Map<DestinationWorld, Float> sizes =
			new java.util.IdentityHashMap<>();

	/**
	 * Which offscreen image each plane is drawn into, kept for as long as it is being shown.
	 *
	 * <p>Not simply the order it came in. That order is by distance, so walking between two
	 * doorways swaps them — and an image is something a renderer builds machinery against
	 * and does not expect to be handed a different one of each frame. Holding the slot still
	 * while a plane keeps being shown means the image only really changes when its size
	 * does.
	 */
	private static final java.util.Map<Plane, Integer> slots = new java.util.HashMap<>();

	private static boolean drawn;

	/**
	 * When the see-through pass may be tried again, and how long it waits next time.
	 *
	 * <p>A failure used to turn it off for the rest of the session. That is right for a
	 * failure that will happen every frame — sixty stack traces a second drown out what
	 * caused them — and wrong for one that will not, which is most of them: a target
	 * resized mid-frame, a world let go of while it was being drawn. Those recover on their
	 * own, and the old behaviour meant one of them ended the portals until you rejoined.
	 *
	 * <p>So it backs off instead of stopping. A run of failures gets quiet quickly; a single
	 * one costs a moment.
	 */
	/** How long the first failure waits. */
	private static final long FIRST_QUIET = 1_000L;

	/** And the longest it ever waits, once failures have proved to be constant. */
	private static final long LONGEST_QUIET = 30_000L;

	private static long quietUntil;

	private static long quietFor = FIRST_QUIET;

	private SeeThrough() {
	}

	/** Draws the far sides of the chosen planes, before this world is drawn over them. */
	public static void beforeLevel(GameRenderer renderer, Matrix4f projection) {
		shown.clear();
		showing.clear();
		Minecraft client = Minecraft.getInstance();

		if (client.level == null || client.player == null) {
			return;
		}

		List<Plane> chosen = chosen(client, renderer, client.player.getEyePosition());
		sizes.clear();
		keepSlots(chosen);

		for (Plane plane : chosen) {
			DestinationWorld world = DestinationInbox.forPlane(idOf(plane),
					plane.transform().orElseThrow().target());

			if (world == null) {
				say(plane, "no far side world yet");
				continue;
			}

			// A watch that has not streamed anything yet draws as a black hole in the
			// wall, which is worse than not drawing at all: the plane keeps its own
			// colour instead, which says "a way through, not ready" rather than "a way
			// through to nothing". A level kept from a crossing is exempt — it is whole
			// already, and has no streamed columns to count.
			if (world.applied() == 0 && !world.adopted()) {
				say(plane, "far side streamed nothing yet");
				continue;
			}

			// One size per far side, settled by the nearest opening onto it. Several planes
			// can lead to the same world, and a world can only be one size at a time —
			// sizing it per plane means resizing it twice a frame, and a renderer resized
			// every frame never finishes working out what is visible through it.
			//
			// Nearest wins because this list is sorted nearest first, so the opening that
			// will be looked at most closely is the one that sets the detail.
			float detail = sizes.computeIfAbsent(world, ignored ->
					PlaneshiftConfig.planeDetail(plane.distanceTo(client.player.getEyePosition())));

			TextureTarget image = DestinationRenderer.draw(client, client.getDeltaTracker(),
					plane, world, projection, slots.getOrDefault(plane, shown.size()), detail);

			if (image == null) {
				// A failure stops the whole pass, so there is nothing to be gained by
				// trying the ones behind it.
				say(plane, "the far side drew nothing");
				break;
			}

			ghosts(client, renderer, plane, world);
			shown.add(new Shown(plane, image, reach(client, plane)));
		}

		for (Shown one : shown) {
			showing.add(one.plane());
			skips.remove(one.plane());
		}

		if (!shown.isEmpty() && !drawn) {
			drawn = true;
			Planeshift.LOGGER.info("Drew {} far side(s) into offscreen images", shown.size());
		}
	}

	/**
	 * Copies the far side's straddlers into this frame, the other half of what
	 * {@link DestinationRenderer} does for this world's.
	 *
	 * <p>After the far side has been drawn, not before: that draw aims the shared entity
	 * dispatcher at the destination camera and only puts it back when it is done.
	 */
	private static void ghosts(Minecraft client, GameRenderer renderer, Plane plane,
			DestinationWorld world) {
		if (client.level == null) {
			return;
		}

		PlaneTransform transform = plane.transform().orElseThrow();
		LevelRenderState frame = renderer.gameRenderState().levelRenderState;
		Vec3 anchor = plane.anchor();
		// The far plane, derived from the transform rather than looked up, and the way
		// back from it: the anchor and the offset swap places.
		PlaneGeometry far = PlaneGeometry.of(plane, frame.cameraRenderState.pos).through(transform, anchor);
		PlaneTransform back = transform.inverse(client.level.dimension(), anchor);

		PlaneGhosts.add(world.level(), far, back, transform.offset(), frame.entityRenderStates,
				frame.cameraRenderState.pos, client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	/** Shows this frame's images through their openings. */
	public static void afterLevel(GameRenderer renderer, Matrix4f projection) {
		if (shown.isEmpty() || System.currentTimeMillis() < quietUntil) {
			shown.clear();
			return;
		}

		CameraRenderState camera = renderer.gameRenderState().levelRenderState.cameraRenderState;

		try {
			// Furthest first, so that where two openings overlap on screen the nearer one
			// is the one you end up looking through.
			for (int i = shown.size() - 1; i >= 0; i--) {
				Shown one = shown.get(i);
				PlaneComposite.draw(renderer.mainRenderTarget(), one.image(), camera,
						projection, one.plane(), one.reach());
			}
		} catch (RuntimeException e) {
			Planeshift.LOGGER.error("Showing a plane's far side failed; waiting {}ms before"
					+ " trying again", quietFor, e);
			lastFailure = e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : ": " + e.getMessage());
			quietUntil = System.currentTimeMillis() + quietFor;
			quietFor = Math.min(LONGEST_QUIET, quietFor * 4);
		} finally {
			shown.clear();
		}
	}

	/**
	 * Whether this plane's far side is being drawn through it.
	 *
	 * <p>A portal that is <em>not</em> is worth marking: it is still a way through, and
	 * the reason it is showing nothing — too far, too many nearer ones, nothing streamed
	 * behind it yet — is invisible without something to see.
	 */
	public static boolean isShowing(Plane plane) {
		return showing.contains(plane);
	}

	/** Lets go of what is held between worlds, and lets a failed pass be tried again. */
	public static void forget() {
		shown.clear();
		showing.clear();
		slots.clear();
		quietUntil = 0L;
		quietFor = FIRST_QUIET;
		PlaneComposite.close();
		DestinationRenderer.forget();
	}

	/**
	 * The portal planes worth drawing this frame: in range, on screen, nearest first, and
	 * no more of them than the player asked for.
	 *
	 * <p>The view test is not a refinement. Drawing a far side means rendering a whole
	 * second world, and without it that happens every frame the player stands near a
	 * portal — facing it, facing away, or with their back to a wall between them. Being
	 * on screen is the only cheap question that rules most of that out.
	 *
	 * <p>It is a frustum test and no more, so a plane behind a wall still counts as
	 * visible. Deciding that properly means occlusion, which costs more to ask than the
	 * odd wasted frame it would save.
	 */
	private static List<Plane> chosen(Minecraft client, GameRenderer renderer, Vec3 eye) {
		Frustum frustum = renderer.mainCamera().getCullFrustum();

		if (frustum == null) {
			return List.of();
		}

		List<Plane> found = new ArrayList<>();

		for (IdentifiedPlane identified : ClientPlanes.all()) {
			Plane plane = identified.plane();

			if (!plane.isPortal()) {
				continue;
			}

			if (!codx.planeshift.client.PlaneshiftConfig.showsFarSide(client, plane)) {
				say(plane, "out of range, or its blocks are not here");
				continue;
			}

			if (frustum.isVisible(bounds(client, plane, eye))) {
				found.add(plane);
			} else {
				say(plane, "off screen");
			}
		}

		found.sort(Comparator.comparingDouble(plane -> plane.distanceTo(eye)));
		int wanted = PlaneshiftConfig.howManyOf(found.size());

		// Being nearer is the whole of why the others won, and it is invisible: a player
		// looking at two doorways and seeing through one of them is looking at a setting.
		for (int i = wanted; i < found.size(); i++) {
			say(found.get(i), wanted + " nearer opening(s) are being shown, which is all"
					+ " planes_seen_through allows");
		}

		return found.subList(0, wanted);
	}

	/**
	 * Gives every plane being shown an image of its own, and keeps it between frames.
	 *
	 * <p>Slots are handed back when a plane stops being shown, so a player walking past a row
	 * of doorways does not collect an image per doorway they ever glanced at.
	 */
	private static void keepSlots(List<Plane> chosen) {
		slots.keySet().retainAll(chosen);

		for (Plane plane : chosen) {
			if (!slots.containsKey(plane)) {
				int free = 0;

				while (slots.containsValue(free)) {
					free++;
				}

				slots.put(plane, free);
			}
		}
	}

	/**
	 * Says, into the trace log, why a plane is not being looked through this frame.
	 *
	 * <p>Every reason a plane goes undrawn is invisible from the outside: the opening looks
	 * the same whether the far side is out of range, not streamed yet, or behind the
	 * player. Once a second is enough to tell which, and costs nothing when tracing is off.
	 */
	private static void say(Plane plane, String why) {
		long now = System.currentTimeMillis();
		Skip was = skips.get(plane);
		// Kept whether or not anything is listening, because the question "why is this
		// doorway showing nothing" is asked long after the frame that decided it — by a
		// player typing /planeshift why, and by the warning below.
		skips.put(plane, new Skip(why, was != null && was.why().equals(why) ? was.since() : now));
		warn(plane, why, skips.get(plane).since(), now);

		if (!codx.planeshift.debug.TraceLog.on()) {
			return;
		}


		// Per plane, so that a far one being off screen does not hide why the near one is
		// undrawn — which is exactly the pair of answers worth having at once.
		if (now - lastSaid.getOrDefault(plane, 0L) < 1_000L) {
			return;
		}

		lastSaid.put(plane, now);
		codx.planeshift.debug.TraceLog.line(String.format(
				"        see-through skipped plane facing %s at %.1f %.1f %.1f: %s",
				plane.facing().getName(), plane.anchor().x, plane.anchor().y, plane.anchor().z, why));
	}

	private static final java.util.Map<Plane, Long> lastSaid = new java.util.WeakHashMap<>();

	/** A reason a plane is showing nothing, and the moment it started being the reason. */
	private record Skip(String why, long since) {
	}

	/**
	 * The standing reason each plane is showing nothing, kept past the frame that decided it.
	 *
	 * <p>Every reason looks the same from the outside — the opening simply shows the wall —
	 * so without this the only way to tell "out of range" from "nothing streamed yet" is to
	 * have been tracing at the time. Kept always, so {@code /planeshift why} can answer
	 * afterwards and so a failure that lasts can announce itself.
	 */
	private static final java.util.Map<Plane, Skip> skips = new java.util.WeakHashMap<>();

	/** When each reason was last written to the log, so a standing one says itself once. */
	private static final java.util.Map<String, Long> lastWarned = new java.util.HashMap<>();

	/** How long a reason must hold before it is worth the game log. */
	private static final long STUCK_AFTER = 5_000L;

	/** And how long before the same reason is worth repeating. */
	private static final long REPEAT_AFTER = 60_000L;

	private static @Nullable String lastFailure;

	/**
	 * Says, once, into the game log, that a doorway has been showing nothing for a while.
	 *
	 * <p>The trace file answers this too, and better — but it has to be turned on before
	 * the thing goes wrong, which is never how a report arrives. This is what makes
	 * {@code latest.log} enough on its own.
	 */
	private static void warn(Plane plane, String why, long since, long now) {
		if (now - since < STUCK_AFTER) {
			return;
		}

		String key = why + " @ " + plane.anchor();

		if (now - lastWarned.getOrDefault(key, 0L) < REPEAT_AFTER) {
			return;
		}

		lastWarned.put(key, now);
		Planeshift.LOGGER.warn("A portal at {} {} {} has shown nothing for {}s: {}."
				+ " Run /planeshift why for the rest of the picture.",
				String.format("%.1f", plane.anchor().x), String.format("%.1f", plane.anchor().y),
				String.format("%.1f", plane.anchor().z), (now - since) / 1000L, why);
	}

	/**
	 * Why this plane is showing nothing, in words, or {@code null} if it is being drawn.
	 *
	 * <p>For {@code /planeshift why}: the whole point is that a player who can see the
	 * problem cannot see any of the reasons for it.
	 */
	public static @Nullable String whyNotShowing(Plane plane) {
		if (showing.contains(plane)) {
			return null;
		}

		Skip skip = skips.get(plane);

		if (skip == null) {
			return "not looked at this frame";
		}

		return skip.why() + ", for " + (System.currentTimeMillis() - skip.since()) / 1000L + "s";
	}

	/** What went wrong the last time the far sides were shown, if anything has. */
	public static @Nullable String lastFailure() {
		return System.currentTimeMillis() < quietUntil
				? lastFailure + " (waiting before trying again)"
				: lastFailure;
	}

	/**
	 * How far a pixel may be looking through this plane before it has faded to nothing.
	 *
	 * <p>The fading itself is per pixel, in the composite: what matters is how far away the
	 * part of the plane a ray meets is, not how far away the plane is. For a doorway those
	 * are nearly the same number. For the floor of a world they are not.
	 */
	private static float reach(Minecraft client, Plane plane) {
		if (client.player == null) {
			return Float.MAX_VALUE;
		}

		int chunks = client.options.getEffectiveRenderDistance();
		// An edgeless plane is the floor of a world, and how far off you can see one is not
		// a matter of taste: from the surface of the Overworld the world below is a hundred
		// and fifty blocks down, and a fade meant for doorways across a room puts it out
		// long before you are anywhere near it. The doorway fade is for doorways; the floor
		// of the world is shown as far as it is prepared.
		return (float) (plane.shape() instanceof PlaneShape.Infinite
				? PlaneshiftConfig.planeVisibleDistance(chunks)
				: PlaneshiftConfig.planeFadeDistance(chunks));
	}

	/** This plane as the server names it, or -1 if the client has not been told. */
	private static int idOf(Plane plane) {
		for (codx.planeshift.registry.IdentifiedPlane identified : ClientPlanes.all()) {
			if (identified.plane() == plane) {
				return identified.id();
			}
		}

		return -1;
	}

	/** A box holding as much of a plane as could be drawn. */
	private static AABB bounds(Minecraft client, Plane plane, Vec3 eye) {
		Direction.Axis axis = plane.facing().getAxis();
		double surface = plane.surface();

		return switch (plane.shape()) {
			case PlaneShape.Rectangle rect -> new AABB(rect.min(), rect.max()).inflate(EDGE);
			// No edges to bound, so bound what can be seen of it instead: the render
			// distance about the eye, on the two axes it runs along.
			case PlaneShape.Infinite ignored -> {
				double reach = client.options.getEffectiveRenderDistance() * 16.0;
				yield new AABB(corner(axis, surface - EDGE, eye, -reach),
						corner(axis, surface + EDGE, eye, reach));
			}
		};
	}

	private static Vec3 corner(Direction.Axis axis, double surface, Vec3 eye, double reach) {
		return switch (axis) {
			case X -> new Vec3(surface, eye.y + reach, eye.z + reach);
			case Y -> new Vec3(eye.x + reach, surface, eye.z + reach);
			case Z -> new Vec3(eye.x + reach, eye.y + reach, surface);
		};
	}
}
