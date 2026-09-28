package codx.planeshift.client;

import java.util.List;

import codx.planeshift.registry.IdentifiedPlane;

/**
 * What the server has told this client about the planes in the dimension it is in.
 *
 * <p>Read-only: the server owns planes. This is a mirror, replaced wholesale by
 * {@code PlanesPayload}, and it keeps the ids because naming a plane — never
 * coordinates — is how the client asks to see the far side of one.
 */
public final class ClientPlanes {
	private static List<IdentifiedPlane> planes = List.of();

	private ClientPlanes() {
	}

	public static List<IdentifiedPlane> all() {
		return planes;
	}

	public static void replaceAll(List<IdentifiedPlane> updated) {
		planes = List.copyOf(updated);
		// The client works out collisions for itself, so it has to know which blocks a
		// doorway is drawn across just as the server does, or it would walk into a wall the
		// server has already let it through.
		codx.planeshift.crossing.Passable.hereIs(
				codx.planeshift.crossing.Passable.openingsOf(planes));
	}

	/** Drops everything, for leaving a server. */
	public static void clear() {
		planes = List.of();
		codx.planeshift.crossing.Passable.forget();
	}
}
