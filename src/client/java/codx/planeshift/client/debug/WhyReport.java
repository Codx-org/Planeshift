package codx.planeshift.client.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import codx.planeshift.client.ClientPlanes;
import codx.planeshift.client.DestinationInbox;
import codx.planeshift.client.DestinationWatcher;
import codx.planeshift.client.DestinationWorld;
import codx.planeshift.client.PlaneshiftConfig;
import codx.planeshift.client.render.SeeThrough;
import codx.planeshift.network.ClientNote;
import codx.planeshift.network.WhyPayload;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneShape;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.IdentifiedPlane;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Answers {@code /planeshift why}: everything the client decided about the doorways
 * around the player, in words.
 *
 * <p>A doorway showing nothing looks exactly like a doorway showing nothing, whatever the
 * cause — out of range, not streamed yet, refused by the server, one too many for the
 * setting, or a failure in the pass that draws it. All of those are decided on the client,
 * none of them are visible, and a player reporting the problem cannot be expected to turn
 * on a trace before it happens. So the question is answerable after the fact, from inside
 * the game, in one command.
 *
 * <p>Said twice over: into the player's own chat, where they can screenshot it, and back
 * to the server's log, where an operator can read it without asking them to.
 */
public final class WhyReport {
	/**
	 * Mods that take the drawing of the world over.
	 *
	 * <p>Worth naming in a report. The far side is kept from showing what is behind the far
	 * opening by an oblique near plane in the projection — a thing a mod that builds its own
	 * projection, or draws terrain its own way, is free not to honour. If it does not, the
	 * wall the far portal is mounted on is drawn over the opening, which reads as the portal
	 * showing no world at all.
	 */
	private static final java.util.List<String> RENDERERS = java.util.List.of(
			"sodium", "embeddium", "rubidium", "iris", "oculus", "vulkanmod", "optifabric",
			"nvidium", "immediatelyfast", "distanthorizons");

	private static String renderers() {
		return RENDERERS.stream()
				.filter(id -> net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id))
				.collect(java.util.stream.Collectors.joining(", "));
	}

	/** How many openings to account for. Enough for a room built of them, short of a wall. */
	private static final int MOST = 8;

	private WhyReport() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(WhyPayload.TYPE,
				(payload, context) -> report(context.client()));
	}

	private static void report(Minecraft client) {
		for (String line : lines(client)) {
			if (client.player != null) {
				client.player.sendSystemMessage(
						Component.literal(line).withStyle(ChatFormatting.GRAY));
			}

			ClientPlayNetworking.send(new ClientNote(line));
		}
	}

	private static List<String> lines(Minecraft client) {
		List<String> out = new ArrayList<>();

		if (client.player == null || client.level == null) {
			out.add("planeshift: no world.");
			return out;
		}

		int chunks = client.options.getEffectiveRenderDistance();
		List<IdentifiedPlane> planes = new ArrayList<>(ClientPlanes.all());
		Vec3 eye = client.player.getEyePosition();
		planes.sort(Comparator.comparingDouble(each -> each.plane().distanceTo(eye)));

		out.add(String.format(java.util.Locale.ROOT, "planeshift: %d plane(s) known here, %d of them portals;"
						+ " %s shown at once; visible to %.0f blocks, faded by %.0f; render distance %d",
				planes.size(), planes.stream().filter(each -> each.plane().isPortal()).count(),
				PlaneshiftConfig.planesSeenThrough() == PlaneshiftConfig.ALL_PLANES
						? "all" : String.valueOf(PlaneshiftConfig.planesSeenThrough()),
				PlaneshiftConfig.planeVisibleDistance(chunks),
				PlaneshiftConfig.planeFadeDistance(chunks), chunks));

		String renderers = renderers();

		if (!renderers.isEmpty()) {
			out.add("  drawing is also being changed by: " + renderers
					+ ". A mod that replaces the world renderer may ignore the cut that keeps"
					+ " what is behind a far opening out of it.");
		}

		String range = codx.planeshift.client.render.DestinationRenderer.depthRange();

		if (range != null) {
			out.add("  the far side's depth range reads: " + range
					+ " (the cut at the opening is built on these)");
		}

		String failure = SeeThrough.lastFailure();

		if (failure != null) {
			out.add("  the pass that shows far sides has failed: " + failure);
		}

		String refusal = DestinationWatcher.lastRefusal();

		if (refusal != null) {
			out.add("  the server last turned a watch down: " + refusal);
		}

		if (planes.isEmpty()) {
			out.add("  nothing here has a plane in it. The server has not told this client"
					+ " about any, which is where to look next.");
			return out;
		}

		for (IdentifiedPlane identified : planes.subList(0, Math.min(MOST, planes.size()))) {
			out.add("  " + account(client, identified, eye));
		}

		return out;
	}

	private static String account(Minecraft client, IdentifiedPlane identified, Vec3 eye) {
		Plane plane = identified.plane();
		String where = String.format(java.util.Locale.ROOT, "#%d %s %s at %.1f %.1f %.1f, %.1f blocks off",
				identified.id(),
				plane.shape() instanceof PlaneShape.Infinite ? "edgeless" : "opening",
				plane.facing().getName(), plane.anchor().x, plane.anchor().y, plane.anchor().z,
				plane.distanceTo(eye));

		if (!plane.isPortal()) {
			return where + ": leads nowhere, so there is nothing behind it to draw.";
		}

		String why = SeeThrough.whyNotShowing(plane);
		String state = why == null ? "drawn" : why + "; " + watch(client, identified);

		return where + ": " + state + ". " + farEye(client, identified, eye);
	}

	/**
	 * Where the far side is being drawn from, and what is standing there.
	 *
	 * <p>The one thing that cannot be worked out from this side of a report. A portal draws
	 * the far world from where your eye would be if you had already stepped through, which
	 * is <em>behind</em> the far opening — inside whatever it is mounted on. The projection
	 * is cut at the far plane so that none of that is drawn, and if any of it is showing
	 * anyway, this line is what says so: a far eye sunk in oak, and the opening full of oak,
	 * is a different fault from a far eye out in open air.
	 */
	private static String farEye(Minecraft client, IdentifiedPlane identified, Vec3 eye) {
		Plane plane = identified.plane();
		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null) {
			return "";
		}

		Vec3 there = transform.position(eye, plane.anchor());
		// What decides whether the cut is made at all: too near the surface and there is no
		// room between the eye and it to cut against.
		double across = Math.abs(eye.subtract(plane.anchor()).dot(plane.facing().getUnitVec3()));
		DestinationWorld world = DestinationInbox.forPlane(identified.id(), transform.target());
		String standing = world == null ? "no world to ask"
				: "in " + world.level().dimension().identifier() + ", inside "
						+ world.level().getBlockState(BlockPos.containing(there))
								.getBlock().builtInRegistryHolder().key().identifier();

		return String.format(java.util.Locale.ROOT, "Far eye %.2f blocks behind the far side at %.1f %.1f %.1f, %s; %s.",
				across, there.x, there.y, there.z, standing,
				across <= 0.07 ? "TOO CLOSE TO CUT THE VIEW AT THE OPENING" : "view cut at the opening");
	}

	/** What the far side of this plane is doing, which is most of why it is not drawn. */
	private static String watch(Minecraft client, IdentifiedPlane identified) {
		int id = identified.id();

		if (!DestinationWatcher.isAsked(id)) {
			return "no watch asked for";
		}

		if (!DestinationWatcher.isWatched(id)) {
			return "watch asked for, the server has not opened it";
		}

		DestinationWorld world = DestinationInbox.forPlane(id,
				identified.plane().transform().orElseThrow().target());

		if (world == null) {
			return "watch open, no world behind it yet";
		}

		return "watch open, " + (world.adopted() ? "world kept from a crossing"
				: world.applied() + " column(s) streamed");
	}
}
