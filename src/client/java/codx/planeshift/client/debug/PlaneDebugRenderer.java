package codx.planeshift.client.debug;

import java.util.List;

import codx.planeshift.client.ClientPlanes;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneShape;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the planes the server has told this client about.
 *
 * <p>Read-only: the server owns planes, and this list is replaced wholesale by
 * {@code PlanesPayload}. Nothing here decides what exists.
 *
 * <p>Everything here must run on the client thread. {@link Gizmos} keeps its collector
 * in a {@link ThreadLocal} that only the client main thread has, so gizmos emitted from
 * anywhere else — including the integrated server — are silently dropped.
 */
public final class PlaneDebugRenderer {
	private static final float OUTLINE_WIDTH = 2.0F;

	/** Opaque outline, see-through fill: a plane you cannot look through is not useful. */
	private static final int FILL_ALPHA = 0x40000000;
	private static final int STROKE_ALPHA = 0xFF000000;

	/** How long a rainbow takes to come back round, in milliseconds. */
	private static final long RAINBOW_PERIOD = 5000L;

	/**
	 * Hue offset between successive planes, so a row of them reads as a spectrum rather
	 * than all flashing the same colour together.
	 */
	private static final float RAINBOW_SPREAD = 0.15F;

	/**
	 * How far off the block face to draw, in blocks. A plane picked off a block face is
	 * exactly coplanar with it, and coplanar geometry loses the depth test against an
	 * opaque block rather than merely z-fighting with it — the quad disappears. Nudging
	 * the drawing along the normal keeps {@link Plane}'s own geometry exact, which
	 * crossing detection will need.
	 */
	private static final double SURFACE_OFFSET = 0.01;

	/**
	 * How far past the render distance to carry an infinite plane, as a multiple. The
	 * quad's edge has to land outside everything the player can see, and the camera's
	 * far plane sits well beyond the last drawn chunk. Looking down you never notice —
	 * terrain and fog reach the edge first — but against open sky it is bare. Two
	 * triangles cost nothing, so this errs large.
	 */
	private static final double INFINITE_REACH = 4.0;

	private static boolean rainbow;
	private static int color = 0x00FFFF;

	private PlaneDebugRenderer() {
	}

	/**
	 * Re-emits every gizmo. The collector is drained each tick, so anything not added
	 * again here disappears.
	 */
	public static void onTick(Minecraft client) {
		if (client.level == null) {
			return;
		}

		List<codx.planeshift.registry.IdentifiedPlane> planes = ClientPlanes.all();

		for (int i = 0; i < planes.size(); i++) {
			Plane plane = planes.get(i).plane();

			// A portal showing its far side needs no outline, and drawing one over it
			// would be the wrong picture twice: it covers the view it is meant to be an
			// opening onto, and it writes depth a hair in front of its own surface —
			// exactly where the see-through pass looks to decide whether this world
			// blocks the opening.
			//
			// One that is *not* showing its far side keeps its colour, and that is the
			// point: a portal drawing nothing is otherwise indistinguishable from no
			// portal at all, whether the reason is distance, a nearer one taking its
			// place, or nothing streamed behind it yet.
			if (codx.planeshift.client.render.SeeThrough.isShowing(plane)) {
				continue;
			}

			if (!codx.planeshift.client.PlaneshiftConfig.showPlaneOutlines()) {
				// Off by default now. It exists so a doorway drawing nothing can be told
				// apart from no doorway at all — which mattered when distance meant the far
				// side went blank, and matters much less now that it only means softer.
				continue;
			}

			if (tooFar(client, plane)) {
				continue;
			}

			GizmoStyle style = styleFor(i);

			// Depth-tested on purpose: a plane hidden behind blocks should read as hidden.
			switch (plane.shape()) {
				case PlaneShape.Rectangle rect -> {
					Vec3 nudge = plane.facing().getUnitVec3().scale(SURFACE_OFFSET);
					Gizmos.rect(rect.min().add(nudge), rect.max().add(nudge), plane.facing(), style);
				}
				case PlaneShape.Infinite ignored -> drawInfinite(client, plane, style);
			}
		}
	}

	/**
	 * Whether this plane is further off than anything else would be drawn.
	 *
	 * <p>A plane is not made of blocks, so nothing fades it out: an edgeless one reaches
	 * as far as it is drawn, which is a coloured sheet across the sky from anywhere in
	 * the world. Held to the same distance as the terrain unless the player asks
	 * otherwise, which is worth asking for when hunting a plane you have just made.
	 */
	private static boolean tooFar(Minecraft client, Plane plane) {
		if (client.player == null) {
			return false;
		}

		return !codx.planeshift.client.PlaneshiftConfig.showsFarSide(client, plane);
	}

	/**
	 * Draws an infinite plane as a square centred on the player, sized to the render
	 * distance. The player cannot see past that anyway, so there is nothing to gain by
	 * drawing further, and a genuinely unbounded quad has no vertices to give.
	 */
	private static void drawInfinite(Minecraft client, Plane plane, GizmoStyle style) {
		if (client.player == null) {
			return;
		}

		Direction.Axis axis = plane.facing().getAxis();
		double radius = client.options.getEffectiveRenderDistance() * 16.0 * INFINITE_REACH;
		// Both corners sit on the surface, so whichever face fromCuboidFace picks for
		// this facing lands in the same place.
		double surface = plane.surface() + plane.facing().getUnitVec3().get(axis) * SURFACE_OFFSET;
		Vec3 eye = client.player.position();

		Vec3 min = corner(axis, surface, eye, -radius);
		Vec3 max = corner(axis, surface, eye, radius);
		Gizmos.rect(min, max, plane.facing(), style);
	}

	private static Vec3 corner(Direction.Axis axis, double surface, Vec3 eye, double reach) {
		return switch (axis) {
			case X -> new Vec3(surface, eye.y + reach, eye.z + reach);
			case Y -> new Vec3(eye.x + reach, surface, eye.z + reach);
			case Z -> new Vec3(eye.x + reach, eye.y + reach, surface);
		};
	}

	/**
	 * The colour for one plane. Rebuilt every tick rather than cached, because in
	 * rainbow mode it changes every tick by definition.
	 */
	private static GizmoStyle styleFor(int index) {
		int rgb = rainbow ? hue(index) : color;
		return GizmoStyle.strokeAndFill(STROKE_ALPHA | rgb, OUTLINE_WIDTH, FILL_ALPHA | rgb);
	}

	private static int hue(int index) {
		float phase = (System.currentTimeMillis() % RAINBOW_PERIOD) / (float) RAINBOW_PERIOD;
		// Wall-clock rather than tick count, so the cycle keeps its pace through lag.
		return Mth.hsvToArgb((phase + index * RAINBOW_SPREAD) % 1.0F, 1.0F, 1.0F, 0) & 0xFFFFFF;
	}

	/** Sets how planes are coloured, as told by the server. */
	public static void setStyle(boolean useRainbow, int rgb) {
		rainbow = useRainbow;
		color = rgb & 0xFFFFFF;
	}

}
