package codx.planeshift.crossing;

import java.util.ArrayList;
import java.util.List;

import codx.planeshift.Planeshift;
import codx.planeshift.PlaneshiftRules;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;
import codx.planeshift.registry.PlaneStore;
import codx.planeshift.registry.Planes;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Carries everything that is not a player through a plane it walks, falls or flies into.
 *
 * <p>Players cross by agreement: the client goes first so that there is no loading screen,
 * and the server follows. Nothing else has a client to agree with, so this is the simple
 * version — noticed at the end of the tick, moved at once.
 *
 * <p>Where a player's crossing is decided by their eye, because that is what the client
 * decides by, everything else is decided by its position. For something falling into a
 * plane lying flat that is its feet, which is the part of it that arrives first.
 *
 * <p>No history is kept. {@code xOld} is where the entity was at the start of this tick,
 * which is exactly the movement to test, and it costs nothing to read.
 */
public final class EntityCrossings {
	/** Movement longer than this is a teleport, not a journey, and crosses nothing. */
	private static final double MAX_STEP = 16.0;

	private EntityCrossings() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(EntityCrossings::tick);
	}

	private static void tick(MinecraftServer server) {
		if (!PlaneshiftRules.entitiesCross()) {
			return;
		}

		for (ServerLevel level : server.getAllLevels()) {
			PlaneStore store = Planes.store(level);

			if (store.empty()) {
				continue;
			}

			// Collected first: carrying one removes it from this level, and its passengers
			// with it, which is not something to do while walking the list.
			List<Entity> crossed = new ArrayList<>();
			List<Crossing> through = new ArrayList<>();

			for (Entity entity : level.getAllEntities()) {
				Crossing crossing = crossingOf(store, entity);

				if (crossing != null) {
					crossed.add(entity);
					through.add(crossing);
				}
			}

			for (int i = 0; i < crossed.size(); i++) {
				carry(server, crossed.get(i), through.get(i));
			}
		}
	}

	/** The plane this entity went through this tick, if it is one that may cross at all. */
	private static Crossing crossingOf(PlaneStore store, Entity entity) {
		if (entity instanceof ServerPlayer || entity.isPassenger() || entity.isRemoved()) {
			// Players cross by their own agreement with the client. A passenger goes where
			// its vehicle goes, and is carried with it rather than separately — otherwise
			// the two arrive apart and the rider is thrown off.
			return null;
		}

		Vec3 from = new Vec3(entity.xOld, entity.yOld, entity.zOld);
		Vec3 to = entity.position();

		if (from.distanceToSqr(to) > MAX_STEP * MAX_STEP || from.equals(to)) {
			return null;
		}

		return CrossingDetector.nearest(store, from, to);
	}

	/**
	 * Sends one entity through.
	 *
	 * <p>Its position rather than the crossing point, for the reason a player's transfer
	 * uses theirs: the point lies exactly on the surface, and arriving exactly on the far
	 * surface invites crossing straight back on the next tick from nothing worse than
	 * rounding.
	 */
	private static void carry(MinecraftServer server, Entity entity, Crossing crossing) {
		Plane plane = crossing.plane();
		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null) {
			return;
		}

		ServerLevel target = server.getLevel(transform.target());

		if (target == null) {
			Planeshift.LOGGER.warn("Plane leads to {}, which this server does not have",
					transform.target());
			return;
		}

		Vec3 anchor = plane.anchor();
		entity.teleport(new TeleportTransition(target,
				transform.position(entity.position(), anchor),
				transform.velocity(entity.getDeltaMovement()),
				transform.yaw(entity.getYRot()), entity.getXRot(),
				TeleportTransition.DO_NOTHING));
	}
}
