package codx.planeshift.client.appearance;

import codx.planeshift.plane.Plane;

import net.minecraft.client.Minecraft;

/**
 * What a plane looks like, for planes whose maker cares.
 *
 * <p>Planeshift draws the far side <em>through</em> an opening and nothing else: no frame,
 * no glow, no sheet. What an opening looks like from the outside is the business of the mod
 * that put it there — a nether portal and a gun's doorway are the same thing to this library
 * and should not be to a player — so it is decided here, on the client, by whoever registers
 * a name in {@link PlaneAppearances}.
 *
 * <p>Called once per client tick for each plane carrying the registered name, in the level
 * the player is in. Emit gizmos, or anything else that survives to the frame; the collector
 * is drained each tick, so whatever is not emitted again disappears, which is what makes a
 * plane that has gone away stop being drawn without anyone tidying up.
 */
@FunctionalInterface
public interface PlaneAppearance {
	/**
	 * Draws one plane.
	 *
	 * @param plane  the plane as the server described it, in world coordinates
	 * @param client for the camera, the level, and the player's own settings
	 * @param shown  whether the far side is being drawn through this opening right now.
	 *               False means the opening is showing nothing — too far, too many nearer
	 *               ones, or nothing streamed behind it yet — which is usually worth
	 *               looking different rather than pretending otherwise.
	 */
	void draw(Plane plane, Minecraft client, boolean shown);
}
