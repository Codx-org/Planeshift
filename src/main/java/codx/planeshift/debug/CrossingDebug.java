package codx.planeshift.debug;

import codx.planeshift.crossing.PlaneCrossingCallback;

import net.minecraft.network.chat.Component;

/** Reports crossings in chat. Step 2 detects; transferring the player is step 3. */
public final class CrossingDebug {
	private CrossingDebug() {
	}

	public static void init() {
		PlaneCrossingCallback.EVENT.register((player, crossing) -> player.sendSystemMessage(
				Component.literal(String.format(
						"Crossed a %s-facing plane from %s at %.2f %.2f %.2f, %s",
						crossing.plane().facing().getName(),
						crossing.fromFacingSide() ? "the front" : "behind",
						crossing.point().x, crossing.point().y, crossing.point().z,
						crossing.plane().transform()
								.map(t -> "heading for " + t.target().identifier())
								.orElse("leading nowhere"))),
				true));
	}
}
