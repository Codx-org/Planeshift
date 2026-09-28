package codx.planeshift.client;

import codx.planeshift.client.debug.PlaneDebugRenderer;
import codx.planeshift.client.render.SeeThrough;
import codx.planeshift.network.CrossNet;
import codx.planeshift.network.PlaneStylePayload;
import codx.planeshift.network.PlanesPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class PlaneshiftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(PlaneDebugRenderer::onTick);
		// What a plane looks like belongs to whoever made it; see PlaneAppearances.
		ClientTickEvents.END_CLIENT_TICK.register(
				codx.planeshift.client.appearance.PlaneAppearances::onTick);
		ClientTickEvents.END_CLIENT_TICK.register(codx.planeshift.client.debug.CrossingTrace::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DestinationWatcher::tick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			DestinationInbox.tick();
			DoorwayCrossing.tick();
		});
		ClientPlayNetworking.registerGlobalReceiver(CrossNet.Refused.TYPE,
				(payload, context) -> DoorwayCrossing.refused(payload));
		PlaneshiftConfig.load();
		// The player's own choice, until a server sends one with /planeshift color.
		PlaneDebugRenderer.setStyle(PlaneshiftConfig.planeRainbow(), PlaneshiftConfig.planeColor());
		codx.planeshift.client.debug.CrossingTrace.init();
		DestinationWatcher.init();
		DestinationInbox.init();

		// Handlers run on the render thread, so this can touch client state directly.
		ClientPlayNetworking.registerGlobalReceiver(PlanesPayload.TYPE,
				(payload, context) -> ClientPlanes.replaceAll(payload.planes()));
		ClientPlayNetworking.registerGlobalReceiver(PlaneStylePayload.TYPE,
				(payload, context) -> PlaneDebugRenderer.setStyle(payload.rainbow(), payload.color()));
		// Otherwise one server's planes would still be drawn on the next.
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			ClientPlanes.clear();
			DestinationWatcher.forget();
			DestinationInbox.forgetAll();
			DoorwayCrossing.forget();
			codx.planeshift.client.debug.CrossingTrace.forget();
			SeeThrough.forget();
			codx.planeshift.client.interact.PlaneTargeting.forget();
		});
	}
}