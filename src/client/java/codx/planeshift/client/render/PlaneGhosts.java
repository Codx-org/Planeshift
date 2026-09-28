package codx.planeshift.client.render;

import java.util.List;

import codx.planeshift.plane.PlaneTransform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Entities halfway through an opening, drawn on both sides of it.
 *
 * <p>An entity lives in one level at a time, so one standing in the opening has a half
 * that the other image cannot show: this world is painted over by the far side beyond the
 * plane, and the far side is clipped in front of it. Each render therefore also gets a
 * copy of the other level's straddlers, carried across by the same transform a crossing
 * would use.
 *
 * <p>The copies need no clipping of their own. The part of a copy that falls on the wrong
 * side of the plane lands exactly where the composite already discards pixels, or where
 * the oblique near plane has already cut the far side off.
 */
final class PlaneGhosts {
	/**
	 * How far either side of the opening to look for entities, in blocks. Only ones
	 * actually crossing the surface are kept, but the search has to start somewhere.
	 */
	private static final double SEARCH = 2.0;

	/**
	 * How far along an edgeless plane to bother looking, in blocks. An infinite plane
	 * reaches as far as the level does, and extracting every entity in it to find the
	 * handful standing in the doorway would cost more than the drawing does.
	 */
	private static final double REACH = 64.0;

	private PlaneGhosts() {
	}

	/**
	 * Copies {@code from}'s straddlers across {@code opening} into another level's render
	 * state.
	 *
	 * @param opening   the plane as it stands in {@code from}'s world
	 * @param transform how to carry a point of {@code from} into the world being drawn
	 * @param anchor    the anchor {@code transform} is applied about
	 * @param camera    where the target render is drawn from, for the states' sort distance
	 */
	static void add(ClientLevel from, PlaneGeometry opening, PlaneTransform transform, Vec3 anchor,
			List<EntityRenderState> into, Vec3 camera, float partial) {
		Minecraft client = Minecraft.getInstance();
		EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
		AABB search = search(opening);
		// In first person the player's own body is not drawn, and a copy of it through
		// the opening would be the first time they ever saw it.
		Entity self = client.options.getCameraType().isFirstPerson() ? client.getCameraEntity() : null;
		float yaw = transform.yaw(0.0F);

		for (Entity entity : from.entitiesForRendering()) {
			if (entity == self || entity.isRemoved() || !entity.getBoundingBox().intersects(search)) {
				continue;
			}

			EntityRenderState state = dispatcher.extractEntity(entity, partial);

			if (!straddles(opening, state)) {
				continue;
			}

			Vec3 at = transform.position(new Vec3(state.x, state.y, state.z), anchor);
			state.x = at.x;
			state.y = at.y;
			state.z = at.z;

			if (state instanceof LivingEntityRenderState living) {
				// yRot is the head relative to the body, so only the body turns.
				living.bodyRot += yaw;
			}

			// Both are built from the source level's blocks and positions, and would be
			// drawn against the wrong world entirely.
			state.shadowPieces.clear();
			state.leashStates = null;
			state.distanceToCameraSq = at.distanceToSqr(camera);
			into.add(state);
		}
	}

	/**
	 * The player's own body, drawn into a far side that looks back at their own world.
	 *
	 * <p>Two portals facing each other across a room show you standing between them, and
	 * that view is drawn from a level the player is not an entity in: their client holds
	 * their own body and a streamed copy of the world, and the one is not in the other. So
	 * it is put in by hand, at the position it really occupies — untransformed, because the
	 * body is where it is and only the camera has been carried across.
	 *
	 * <p>The server used to relay the player to their own watch, which worked and looked
	 * wrong: that copy is a tick or two old and interpolated, so the body swam against a
	 * view drawn from the eye's real position. This one is the same body the frame is drawn
	 * from and cannot disagree with it.
	 *
	 * <p>Only into their own dimension. Through a doorway into another world the player is
	 * simply not there, and drawing them would be inventing a second one.
	 */
	static void addSelf(ClientLevel far, List<EntityRenderState> into, Vec3 camera, float partial) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null || client.level == null
				|| !far.dimension().equals(client.level.dimension())) {
			return;
		}

		EntityRenderState state = client.getEntityRenderDispatcher()
				.extractEntity(client.player, partial);

		// Built against the level the player is in, which is not the one being drawn.
		state.shadowPieces.clear();
		state.leashStates = null;
		state.distanceToCameraSq = new Vec3(state.x, state.y, state.z).distanceToSqr(camera);
		into.add(state);
	}

	/** A box around as much of the opening as is worth searching. */
	private static AABB search(PlaneGeometry opening) {
		double width = Math.min(opening.halfWidth(), REACH);
		double height = Math.min(opening.halfHeight(), REACH);
		// The axes are signed unit vectors, so the reach along each is taken as a size
		// rather than a direction: a box has no opinion about which way its edges point.
		Vec3 extent = new Vec3(
				Math.abs(opening.right().x) * width + Math.abs(opening.up().x) * height
						+ Math.abs(opening.normal().x) * SEARCH,
				Math.abs(opening.right().y) * width + Math.abs(opening.up().y) * height
						+ Math.abs(opening.normal().y) * SEARCH,
				Math.abs(opening.right().z) * width + Math.abs(opening.up().z) * height
						+ Math.abs(opening.normal().z) * SEARCH);

		return new AABB(opening.centre().subtract(extent), opening.centre().add(extent))
				.inflate(SEARCH);
	}

	/** Whether the entity's own box crosses the surface, within the opening's edges. */
	private static boolean straddles(PlaneGeometry opening, EntityRenderState state) {
		Vec3 middle = new Vec3(state.x, state.y + state.boundingBoxHeight / 2.0, state.z);
		Vec3 offset = middle.subtract(opening.centre());

		if (Math.abs(offset.dot(opening.normal())) >= half(state, opening.normal())) {
			return false;
		}

		return Math.abs(offset.dot(opening.right())) < opening.halfWidth() + half(state, opening.right())
				&& Math.abs(offset.dot(opening.up())) < opening.halfHeight() + half(state, opening.up());
	}

	/**
	 * Half the entity's size along one of the plane's axes. They are always axis-aligned
	 * unit vectors, so this is height for the vertical one and width for the other two.
	 */
	private static double half(EntityRenderState state, Vec3 axis) {
		return axis.y != 0.0 ? state.boundingBoxHeight / 2.0 : state.boundingBoxWidth / 2.0;
	}
}
