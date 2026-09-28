package codx.planeshift.client.appearance;

import java.util.HashMap;
import java.util.Map;

import codx.planeshift.client.ClientPlanes;
import codx.planeshift.client.render.SeeThrough;
import codx.planeshift.plane.Plane;
import codx.planeshift.registry.IdentifiedPlane;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Which name draws what, and the once-a-tick pass that asks.
 *
 * <p>The names come from the server, attached to the planes themselves; the drawing is
 * registered here, on the client, by the mod that knows what it means. Keeping the two
 * apart is what lets a server run a mod a client does not: an unregistered name draws
 * nothing rather than failing, and the doorway still works.
 *
 * <p>Register from a client initialiser. Nothing here is thread safe, because everything
 * here happens on the client thread.
 */
public final class PlaneAppearances {
	private static final Map<Identifier, PlaneAppearance> REGISTERED = new HashMap<>();

	private PlaneAppearances() {
	}

	/**
	 * Says what planes carrying this name look like.
	 *
	 * <p>Last registration wins, so a resource pack mod can take over another's look on
	 * purpose; there is no ordering to fight over, because a plane carries one name.
	 */
	public static void register(Identifier name, PlaneAppearance appearance) {
		REGISTERED.put(name, appearance);
	}

	/** Called once per client tick; draws every plane that has something to draw. */
	public static void onTick(Minecraft client) {
		if (client.level == null || REGISTERED.isEmpty()) {
			return;
		}

		for (IdentifiedPlane identified : ClientPlanes.all()) {
			Plane plane = identified.plane();

			if (plane.appearance().isEmpty()) {
				continue;
			}

			PlaneAppearance appearance = REGISTERED.get(plane.appearance().orElseThrow());

			if (appearance != null) {
				appearance.draw(plane, client, SeeThrough.isShowing(plane));
			}
		}
	}
}
