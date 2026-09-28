package codx.planeshift.client.interact;

import java.util.function.Supplier;

import codx.planeshift.client.ClientPlanes;
import codx.planeshift.client.DestinationInbox;
import codx.planeshift.client.DestinationWorld;
import codx.planeshift.interact.FarAim;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.IdentifiedPlane;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.HitResult;

/**
 * Runs the game's own attacking and using against the world on the other side of a plane.
 *
 * <p>Everything the client does with a hit — grinding a block down, cracking it, placing
 * against it, opening it — reads two things: {@code Minecraft.level} and
 * {@code Minecraft.hitResult}. Point those at the far side for the length of the call and
 * all of it works there, unchanged, with its own prediction and its own progress.
 *
 * <p>The packets it sends are the one thing that cannot be left alone. Vanilla's block
 * packets name a position and nothing else, because there has never been anywhere else
 * they could mean; {@code PacketTagMixin} catches them while this is in the middle of a
 * far action and puts the plane's id in front.
 */
public final class FarHand {
	/** The plane being reached through, while a far action is running. */
	private static int through = -1;

	private FarHand() {
	}

	/** The plane an outgoing block packet is meant for, or -1 if it means this world. */
	public static int through() {
		return through;
	}

	/** Runs {@code body} against the far side, if the crosshair is on one. */
	public static void reaching(Minecraft client, Runnable body) {
		Supplier<Void> wrapped = () -> {
			body.run();
			return null;
		};
		reaching(client, wrapped);
	}

	/** Runs {@code body} against the far side, if the crosshair is on one. */
	public static <T> T reaching(Minecraft client, Supplier<T> body) {
		FarHit far = PlaneTargeting.current();

		if (far == null || through >= 0 || client.level == null || client.player == null) {
			return body.get();
		}

		DestinationWorld world = far.world();

		ClientLevel home = client.level;
		HitResult held = client.hitResult;
		client.level = world.level();
		client.hitResult = far.hit();
		through = far.planeId();
		// Items that cast their own ray — a bucket, above all — are told nothing about what
		// the crosshair found, so they have to be able to ask. The server sets the same
		// thing while it runs the item for real; if the two aimed differently the client
		// would show a bucket emptying somewhere the server never agreed to.
		FarAim before = FarAim.enter(client.player.getUUID(), aimOf(client, far));

		try {
			return body.get();
		} finally {
			FarAim.leave(client.player.getUUID(), before);
			through = -1;
			client.hitResult = held;
			client.level = home;
		}
	}

	/**
	 * Where the player's line of sight goes once it is through the plane.
	 *
	 * <p>The whole of their reach: what this measures from is their own eye carried across,
	 * which stands as far back from the far plane as they stand from the near one. The walk
	 * to the doorway is already in the measurement. Kept in step with the server's own
	 * version of this, because the two have to agree or the client shows a bucket emptying
	 * somewhere the server refused.
	 */
	private static FarAim aimOf(Minecraft client, FarHit far) {
		PlaneTransform transform = far.plane().transform().orElseThrow();
		return new FarAim(far.world().level(),
				transform.position(client.player.getEyePosition(), far.plane().anchor()),
				transform.velocity(client.player.getViewVector(1.0F)),
				client.player.blockInteractionRange(), client.player.level(), far.plane(),
				transform);
	}

	/** The far side a plane leads to, for an acknowledgement naming that plane. */
	public static @org.jspecify.annotations.Nullable DestinationWorld worldOf(int planeId) {
		for (IdentifiedPlane identified : ClientPlanes.all()) {
			if (identified.id() == planeId && identified.plane().isPortal()) {
				return DestinationInbox.forPlane(planeId,
						identified.plane().transform().orElseThrow().target());
			}
		}

		return null;
	}
}
