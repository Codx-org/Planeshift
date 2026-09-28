package codx.planeshift.crossing;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import net.minecraft.server.level.ServerPlayer;

/** Fired on the server when a player passes through a plane. */
@FunctionalInterface
public interface PlaneCrossingCallback {
	Event<PlaneCrossingCallback> EVENT = EventFactory.createArrayBacked(PlaneCrossingCallback.class,
			callbacks -> (player, crossing) -> {
				for (PlaneCrossingCallback callback : callbacks) {
					callback.onCross(player, crossing);
				}
			});

	void onCross(ServerPlayer player, Crossing crossing);
}
