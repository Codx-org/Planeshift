package codx.planeshift.client;

import org.jspecify.annotations.Nullable;

import codx.planeshift.Planeshift;
import codx.planeshift.client.mixin.EntityAccessor;
import codx.planeshift.client.mixin.LevelExtractorAccessor;
import codx.planeshift.client.mixin.LevelRendererAccessor;
import codx.planeshift.client.mixin.MinecraftAccessor;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Carries the player through a plane without leaving the frame.
 *
 * <p>Nothing here waits for the server. The client moves its own player into the
 * destination it has been streaming, adopts that world's renderer whole, and keeps the
 * one it came from as the view back. The server is told afterwards and follows.
 *
 * <p>Two things make it a step rather than a load. The renderer, extractor and level
 * move together, so every compiled section survives — pointing the game's own renderer
 * at a new level throws them all away. And the player's <em>interpolation start</em>
 * crosses with it, so the part of the step between last tick and the plane is drawn on
 * the far side, exactly as it would have looked through the opening.
 *
 * <p>Render thread only.
 */
public final class DoorwaySwap {
	private DoorwaySwap() {
	}

	/**
	 * Moves the player and the engines across. The caller has already decided that the
	 * eye is past the plane and that {@code world} is the far side of it.
	 */
	public static void swap(Minecraft client, Plane near, DestinationWorld world) {
		LocalPlayer player = client.player;
		ClientLevel from = client.level;

		if (player == null || from == null || world.renderer() == null || world.extractor() == null) {
			return;
		}

		PlaneTransform transform = near.transform().orElse(null);

		if (transform == null) {
			return;
		}

		// A kept level still holds everything that was in it when the player walked out,
		// their own player among them; the server sends the lot again on arrival and
		// the client refuses each as a duplicate. A streamed level has none, so this
		// costs nothing there.
		world.clearEntities();

		Vec3 anchor = near.anchor();
		LevelRenderer replacedRenderer = client.levelRenderer;
		LevelExtractor replacedExtractor = client.levelExtractor;

		move(player, from, world.level(),
				transform.position(player.position(), anchor),
				// The interpolation start goes through too: the frame being drawn sits
				// between these two, and drawing it from the near side would rewind the
				// player back through the plane for one frame.
				transform.position(new Vec3(player.xo, player.yo, player.zo), anchor),
				transform.yaw(player.getYRot()) - player.getYRot(),
				transform.velocity(player.getDeltaMovement()));

		LevelRenderState spare = install(client, world);

		// The level just left becomes the way back, whole and already built: turning
		// round shows it as it was rather than an empty world filling in. It keeps the
		// render state its renderer was moved onto, so it can still be drawn.
		DestinationInbox.keepBehind(from, replacedRenderer, replacedExtractor, spare);
		Planeshift.LOGGER.info("Stepped from {} into {}",
				from.dimension().identifier(), world.dimension().identifier());
		codx.planeshift.debug.TraceLog.line("        client STEPPED " + from.dimension().identifier()
				+ " -> " + world.dimension().identifier());
	}

	/**
	 * Steps back out of a crossing the server would not follow.
	 *
	 * <p>The level walked out of was kept whole, so this is the same move again in the
	 * other direction: it is still built, still meshed, and still where the server
	 * believes the player is.
	 */
	public static void revert(Minecraft client, DestinationWorld stepped) {
		DestinationWorld back = DestinationInbox.takeBehind();

		if (back == null || back.renderer() == null || back.extractor() == null) {
			// Nothing to go back to; the server's own correction will sort it out.
			Planeshift.LOGGER.warn("A crossing was refused with nothing to step back into");
			return;
		}

		LocalPlayer player = client.player;
		ClientLevel from = client.level;

		if (player == null || from == null) {
			return;
		}

		// The move that got here, undone: the same transform the other way about the
		// far side's anchor, which is what PlaneTransform.inverse is for.
		move(player, from, back.level(), player.position(),
				new Vec3(player.xo, player.yo, player.zo), 0.0F, player.getDeltaMovement());
		install(client, back);
		DestinationInbox.keepAsWatched(stepped);
		Planeshift.LOGGER.info("Stepped back into {}", back.dimension().identifier());
		codx.planeshift.debug.TraceLog.line("        client STEPPED BACK into "
				+ back.dimension().identifier());
	}

	/** Hands the player from one level to the other, keeping the same entity. */
	private static void move(LocalPlayer player, ClientLevel from, ClientLevel to,
			Vec3 position, Vec3 previous, float yaw, Vec3 velocity) {
		from.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);

		EntityAccessor access = (EntityAccessor) player;
		// Leaving marked it removed, and a removed entity is not ticked by the level it
		// arrives in. Vanilla never undoes this because it builds a new player.
		access.planeshift$setRemovalReason(null);
		access.planeshift$setLevel(to);

		player.setPos(position);
		player.xo = player.xOld = previous.x;
		player.yo = player.yOld = previous.y;
		player.zo = player.zOld = previous.z;

		// Every rotation the body interpolates between, or the player spins on arrival.
		player.setYRot(player.getYRot() + yaw);
		player.yRotO += yaw;
		player.yBodyRot += yaw;
		player.yBodyRotO += yaw;
		player.yHeadRot += yaw;
		player.yHeadRotO += yaw;

		player.setDeltaMovement(velocity);
		to.addEntity(player);
	}

	/**
	 * Makes the destination's level and engines the game's own.
	 *
	 * @return the render state the outgoing pair was moved onto
	 */
	private static LevelRenderState install(Minecraft client, DestinationWorld world) {
		LevelRenderState main = client.gameRenderer.gameRenderState().levelRenderState;
		LevelRenderState spare = ((LevelRendererAccessor) world.renderer()).planeshift$levelRenderState();

		// The arriving pair draws into the state the game reads; the leaving pair takes
		// the spare, so it keeps working as the view back without touching this frame.
		((LevelRendererAccessor) world.renderer()).planeshift$setLevelRenderState(main);
		((LevelExtractorAccessor) world.extractor()).planeshift$setLevelRenderState(main);
		((LevelRendererAccessor) client.levelRenderer).planeshift$setLevelRenderState(spare);
		((LevelExtractorAccessor) client.levelExtractor).planeshift$setLevelRenderState(spare);

		// Both renderers are about to draw into a different image than they have been, and
		// a sky renderer is built once against whatever the main target was at the time and
		// then never asked again. The arriving one holds one of the offscreen images, which
		// is thrown away the next time the window changes size — and then the game's own sky
		// pass draws into a target whose texture has been freed, which is a crash with
		// nothing of this mod anywhere in it.
		//
		// Asked of the extractors, not of the states. Each extractor keeps its own copy of
		// this and writes it over the state's on every extract, so a flag set on the state
		// is gone before the renderer ever looks at it.
		((LevelExtractorAccessor) world.extractor()).planeshift$setShouldResetSkyRenderer(true);
		((LevelExtractorAccessor) client.levelExtractor).planeshift$setShouldResetSkyRenderer(true);
		// And on the states themselves, for the one frame that can slip between here and the
		// next extract. An extract writes its own copy over the state's, so the flag has to
		// live in both places: on the extractor for every frame after this one, and on the
		// state for the frame that may be drawn before any extract runs. Missing that single
		// frame means the arriving renderer draws its sky into the offscreen image it was
		// built against — which by then has been freed, and that is a null colour attachment
		// and a crash in vanilla's own sky pass, with nothing of this mod in the stack.
		main.shouldResetSkyRenderer = true;
		spare.shouldResetSkyRenderer = true;

		// Assigned, not setLevel: that would run updateLevelInEngines, which is exactly
		// what throws the compiled sections away.
		client.level = world.level();

		MinecraftAccessor engines = (MinecraftAccessor) client;
		engines.planeshift$setLevelRenderer(world.renderer());
		engines.planeshift$setLevelExtractor(world.extractor());

		client.crosshairPickEntity = null;
		client.particleEngine.setLevel(world.level());
		client.gameRenderer.setLevel(world.level());
		// Deliberately not stopping sounds: what was playing during the step keeps playing.

		if (client.player != null) {
			// Picked once a tick; until the next one the block outline would be drawn
			// around a block in the level just left.
			engines.planeshift$pick(1.0F);
		}

		return spare;
	}
}
