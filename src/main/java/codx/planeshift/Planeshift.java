package codx.planeshift;

import codx.planeshift.command.PlaneshiftCommand;
import codx.planeshift.crossing.CrossingDetector;
import codx.planeshift.crossing.PlaneTransfer;
import codx.planeshift.debug.CrossingDebug;
import codx.planeshift.debug.DebugPlanes;
import codx.planeshift.debug.PlaneStyle;
import codx.planeshift.destination.DestinationStreamer;
import codx.planeshift.destination.PlaneWatches;
import codx.planeshift.network.PlaneStylePayload;
import codx.planeshift.network.ClientNote;
import codx.planeshift.network.CrossNet;
import codx.planeshift.network.DestChunkPayload;
import codx.planeshift.network.DestEntityBatch;
import codx.planeshift.network.TracePayload;
import codx.planeshift.network.WhyPayload;
import codx.planeshift.network.PlanesPayload;
import codx.planeshift.network.WatchNet;
import codx.planeshift.registry.Planes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Planeshift implements ModInitializer {
	public static final String MOD_ID = "planeshift";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		PlaneshiftRules.load();
		Planes.init();
		codx.planeshift.registry.PlaneSync.init();
		DebugPlanes.init();
		CrossingDetector.init();
		PlaneTransfer.init();
		codx.planeshift.crossing.CrossingGrace.init();
		codx.planeshift.crossing.EntityCrossings.init();
		CrossingDebug.init();
		CommandRegistrationCallback.EVENT.register(PlaneshiftCommand::register);

		PlanesPayload.register();
		PlaneStylePayload.register();
		WatchNet.register();
		DestChunkPayload.register();
		codx.planeshift.network.DestBlockPayload.register();
		DestEntityBatch.register();
		CrossNet.register();
		codx.planeshift.network.FarNet.register();
		ClientNote.register();
		TracePayload.register();
		WhyPayload.register();
		PlaneWatches.init();
		DestinationStreamer.init();
		codx.planeshift.network.FarNet.init();
		codx.planeshift.debug.CrossingTrace.init();
		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
			PlanesPayload.sendTo(listener.player, listener.player.level());
			ServerPlayNetworking.send(listener.player, PlaneStyle.payload());
		});
		// Planes are per-dimension, so a player carrying the last dimension's set into a
		// new one would see planes that are not there.
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) ->
				PlanesPayload.sendTo(player, to));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
