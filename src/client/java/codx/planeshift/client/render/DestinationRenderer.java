package codx.planeshift.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import codx.planeshift.Planeshift;
import codx.planeshift.client.DestinationWorld;
import codx.planeshift.client.interact.FarOutline;
import org.jspecify.annotations.Nullable;

import codx.planeshift.client.mixin.CameraAccessor;
import codx.planeshift.client.mixin.GameRendererAccessor;
import codx.planeshift.client.mixin.GameRendererResourceAccessor;
import codx.planeshift.plane.Plane;
import codx.planeshift.plane.PlaneTransform;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the far side of a plane into an offscreen image, from where the player's eye
 * would be if they had already stepped through.
 *
 * <p>The destination's own {@code LevelRenderer} does the drawing, unmodified.
 * Everything it reads from outside itself is swapped for the call and put back: the
 * main render target, and the projection. Its render state, camera and fog are its own
 * from the start, which is most of why this can be done at all.
 *
 * <p>The projection is the frame's own, view bob and all, with its near plane turned
 * onto the far plane — so nothing between the destination eye and the opening is drawn,
 * only what lies beyond. Sharing the frame's projection is what lets
 * {@link PlaneComposite} read the image in screen space: a pixel of it is the same pixel
 * of the frame, seen from the other side.
 *
 * <p>Light and sky still come from the player's own world. That is the next thing wrong
 * with the view, and it is a separate piece of work.
 */
public final class DestinationRenderer {
	/**
	 * How far on the eye's side of the far plane the clip plane sits, in blocks. Exactly
	 * on it, a surface lying in the plane — the floor an infinite plane runs along —
	 * falls on the wrong side of a comparison that has no exact answer, and blinks.
	 */
	private static final float CLIP_MARGIN = 0.02F;

	private static ProjectionMatrixBuffer projection;

	/** How long the first failure waits before the far sides are tried again. */
	private static final long FIRST_QUIET = 1_000L;

	/** And the longest it ever waits, once failures have proved to be constant. */
	private static final long LONGEST_QUIET = 30_000L;

	/**
	 * When drawing far sides may be tried again.
	 *
	 * <p>A failure used to stop them for the rest of the session, which is too final for
	 * something that is usually a resource being rebuilt underneath a frame. It backs off
	 * instead: quiet quickly if failures keep coming, a moment if one was a one-off.
	 */
	private static long quietUntil;

	private static long quietFor = FIRST_QUIET;

	/**
	 * The image each far side was last drawn into.
	 *
	 * <p>By world, because the machinery that goes stale belongs to the world's renderer, not
	 * to the image. Keyed by slot instead, a world moving from one slot to another was never
	 * told — the slot it arrived at had not changed, so nothing was rebuilt, and it went on
	 * drawing into an image whose texture had been freed.
	 *
	 * <p>Weak, so letting go of a world is enough to let go of this too.
	 */
	/** The image a far side was last drawn into, and how big it was at the time. */
	private record Drawn(TextureTarget target, int width, int height) {
	}

	private static final java.util.Map<DestinationWorld, Drawn> drawnInto =
			new java.util.WeakHashMap<>();

	private DestinationRenderer() {
	}

	/**
	 * Draws {@code world} as seen through {@code plane}, into the offscreen target.
	 *
	 * @param frameProjection the projection this frame is being drawn with
	 * @return the image, or null if there was nothing to draw or drawing failed
	 */
	public static TextureTarget draw(Minecraft client, DeltaTracker delta, Plane plane,
			DestinationWorld world, Matrix4f frameProjection, int index, float detail) {
		if (System.currentTimeMillis() < quietUntil || world.renderer() == null
				|| world.extractor() == null || world.state() == null) {
			return null;
		}

		GameRenderer renderer = client.gameRenderer;
		CameraRenderState main = renderer.gameRenderState().levelRenderState.cameraRenderState;
		PlaneTransform transform = plane.transform().orElse(null);

		if (transform == null || !main.initialized) {
			return null;
		}

		try {
			return drawInto(client, renderer, delta, main, plane, transform, world, frameProjection,
					index, detail);
		} catch (RuntimeException e) {
			// The frame this threw in is already lost — worse, the level renderer leaves its
			// own prepared frame open when it throws, so the very next draw dies on that
			// instead and takes the game with it. Waiting is what lets the one after that
			// find things in order again.
			Planeshift.LOGGER.error("Drawing the far side of a plane failed; waiting {}ms before"
					+ " trying again", quietFor, e);
			letGoOfTheFrame(client);
			quietUntil = System.currentTimeMillis() + quietFor;
			quietFor = Math.min(LONGEST_QUIET, quietFor * 4);
			return null;
		}
	}

	/**
	 * Closes the prepared frame a failed render left open.
	 *
	 * <p>The whole game shares one, and a level render that throws never reaches its own
	 * close. Every frame after it then dies on "PreparedFrame already in use", which turns
	 * one bad far side into a crash. Closing it here is what makes the backoff above mean
	 * anything: the frame that failed is lost, and the next one is not.
	 */
	private static void letGoOfTheFrame(Minecraft client) {
		try {
			((codx.planeshift.client.mixin.FeatureFrameAccessor) (Object)
					client.gameRenderer.featureRenderDispatcher()).planeshift$preparedFrame().close();
		} catch (RuntimeException ignored) {
			// It was not open, or closing it failed too. Either way there is nothing else
			// to try, and throwing from a handler would hide what actually went wrong.
		}
	}

	private static TextureTarget drawInto(Minecraft client, GameRenderer renderer, DeltaTracker delta,
			CameraRenderState main, Plane plane, PlaneTransform transform, DestinationWorld world,
			Matrix4f frameProjection, int index, float detail) {
		RenderTarget mainTarget = renderer.mainRenderTarget();

		// Smaller the further off the doorway is. The composite reads this image by where a
		// pixel is on screen, not by where it is in the image, so half the width lands in
		// exactly the same place — only softer, on a view that is a few hundred pixels
		// across by then anyway. This is nearly the whole cost of a far side.
		//
		// Decided by the caller, not here, because several planes can share one far side and
		// a world can only be one size at a time. Sizing it per plane means resizing it
		// twice a frame, and a renderer resized every frame never finishes working out what
		// is visible through it — which looks like a doorway that shows nothing at all.
		int width = Math.max(1, Math.round(mainTarget.width * detail));
		int height = Math.max(1, Math.round(mainTarget.height * detail));
		TextureTarget out = DestinationTarget.get(index, width, height);
		// Its own renderer was built at whatever size the window was when the watch
		// opened, which need not be the size the frame is now. Asked only on a change:
		// see DestinationWorld.resize for what asking every frame costs.
		world.resize(width, height);
		float partial = delta.getGameTimeDeltaPartialTick(false);

		// The frame's camera, carried through the plane by the same transform a crossing
		// would use, so the view lines up with where stepping through would put you.
		Vec3 anchor = plane.anchor();
		Vec3 eye = transform.position(main.pos, anchor);
		Camera camera = world.camera();
		CameraAccessor placed = (CameraAccessor) camera;
		Camera mainCamera = renderer.mainCamera();

		// Which level it is looking at, before anything asks it: extractRenderState
		// reads the level's tick rate to interpolate, and a camera that has never been
		// set up has none.
		camera.setLevel(world.level());
		camera.setEntity(client.getCameraEntity());
		placed.planeshift$setPosition(eye);
		// The probe the lightmap is read from samples where the camera stands, and this
		// camera is placed by hand rather than following an entity, so nothing else moves
		// it. Without this the far side is lit by wherever the camera was left last.
		camera.tick();
		placed.planeshift$setRotation(transform.yaw(main.yRot), main.xRot);
		placed.planeshift$setFov(mainCamera.getFov());
		placed.planeshift$setHudFov(main.hudFov);
		placed.planeshift$setDepthFar(main.depthFar);
		placed.planeshift$setupPerspective(Camera.PROJECTION_Z_NEAR, main.depthFar,
				mainCamera.getFov(), width, height);
		// Without a prepared frustum every section is culled and the image comes out empty.
		placed.planeshift$prepareCullFrustum(camera.getViewRotationMatrix(new Matrix4f()),
				placed.planeshift$createProjectionMatrixForCulling(), eye);
		placed.planeshift$setInitialized(true);

		LevelRenderState state = world.state();

		// Same reason as the swap in DoorwaySwap: a sky renderer keeps the target it was
		// built against, and these are rebuilt whenever the window changes size. Left alone
		// it would go on drawing into an image whose texture has been freed.
		//
		// The size as well as the target itself. A far side is drawn smaller the further off
		// it is, and growing one keeps the same target object and gives it new textures —
		// so comparing only the object says nothing has changed, and the sky goes on drawing
		// into a texture that was freed a moment ago. That is a null colour attachment, an
		// exception in the middle of a level render, and a game that takes the next frame
		// down with it.
		Drawn last = drawnInto.get(world);

		if (last == null || last.target() != out || last.width() != out.width
				|| last.height() != out.height) {
			drawnInto.put(world, new Drawn(out, out.width, out.height));
			((codx.planeshift.client.mixin.LevelExtractorAccessor) world.extractor())
					.planeshift$setShouldResetSkyRenderer(true);
		}

		CameraRenderState destination = state.cameraRenderState;
		camera.extractRenderState(destination, delta);
		// Culling by the section occlusion graph walks outward from the camera's own
		// section, which assumes the camera stands somewhere a player could. This one is
		// placed by hand, and for a plane at the floor of a dimension it can sit outside
		// the level altogether — below the overworld's lowest section, looking up from
		// the nether. The graph then reaches nothing and every section is culled, leaving
		// the far side's fog over an empty world.
		destination.smartCull = false;
		destination.fogType = camera.getFluidInCamera();
		destination.fogData = world.fog().setupFog(camera, client.options.getEffectiveRenderDistance(),
				delta, renderer.bossOverlayWorldDarkening(partial), world.level());

		if (hasNoFogOfItsOwn(destination.fogData.color)) {
			// The far world has no fog of its own to offer here. That happens when the
			// carried-through camera stands outside it — under the floor of the world above,
			// which is exactly where it stands when you have just fallen out of one — and
			// vanilla draws neither sky nor haze for a camera that is nowhere.
			//
			// It used to refuse to draw at all, which is why the world above vanished the
			// instant you left it: its bedrock underside was right there and worth seeing,
			// and instead you got your own sky. So the colour is borrowed from the world you
			// are standing in instead. What the far side does not cover then matches what it
			// is being shown against, and the two blend into each other rather than one
			// cutting a hole in the other.
			if (main.fogData != null) {
				destination.fogData.color.set(main.fogData.color);
			}
		}

		world.extractor().extract(delta, camera, partial);
		// Again: the extract reads the flag from the level the player is standing in.
		destination.smartCull = false;
		// After the extract, which fills this list, and before the draw that reads it:
		// anyone standing in the opening has a half that only this world can draw.
		PlaneGhosts.add(client.level, PlaneGeometry.of(plane, main.pos), transform, anchor,
				state.entityRenderStates, eye, partial);
		// And the player themselves, who is in no level but their own; see addSelf.
		PlaneGhosts.addSelf(world.level(), state.entityRenderStates, eye, partial);
		// That callback tells the player's own level it has loaded; this is not its frame.
		state.playerCompiledSectionCallback = null;
		// There is one particle engine for the whole client and it holds the particles of
		// the level the player is in, so the extract just filled this with theirs — placed
		// by the destination camera, lit by the destination's lightmap. A blaze in the
		// Nether ends up as black specks hanging over the sea.
		//
		// Emptied rather than corrected. The far side's own particles are not simulated at
		// all: only the level the player stands in has an engine running, so there is
		// nothing to put here instead. Showing none beats showing the wrong world's.
		state.particlesRenderState.reset();
		// The extract built this from the near side's hit, which is a miss whenever the
		// crosshair reaches through to here. What it is actually on is over here.
		state.blockOutlineRenderState = FarOutline.of(client, plane, world, camera);

		Matrix4f clipped = clipAtFarPlane(new Matrix4f(frameProjection), destination.viewRotationMatrix,
				plane, transform, eye, main.depthFar);
		FogRenderer fog = world.fog();
		RenderSystem.backupProjectionMatrix();

		try {
			RenderSystem.setProjectionMatrix(projection().getBuffer(clipped), ProjectionType.PERSPECTIVE);
			// GameRenderer clears the target before a level render; the level renderer
			// never does it itself.
			// Cleared to the far side's own fog, not to black. Whatever the far side does not
			// cover is what you see, and black is not a colour any world has: looking up out
			// of a dimension whose floor you are under, vanilla draws its dark fog and this
			// drew a void, which reads as the sky having failed rather than as distance.
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
					out.getColorTexture(), destination.fogData.color, out.getDepthTexture(), 0.0);
			((GameRendererAccessor) renderer).planeshift$setMainRenderTarget(out);
			// Where the far side is being looked at from. Terrain positions are made
			// camera-relative against this, so without it every section is drawn as far
			// from its true place as the two cameras are apart.
			globals(renderer, world.level().getGameTime(), partial, eye);
			DestinationLight.update(renderer.gameRenderState().lightmapRenderState,
					camera, world.level(), partial);
			DestinationLight.override = DestinationLight.texture();
			fog.updateBuffer(destination.fogData);
			// The last two as GameRenderer works them out for its own call, rather than
			// the pair of trues this started as. The first is whether to draw the sky,
			// which a boss fight's fog replaces. The second says the frame is headed for
			// a post-effect chain — this image is not, it is sampled through an opening.
			boolean bossFog = client.gui.hud.getBossOverlay().shouldCreateWorldFog();
			world.renderer().render(world.pool(), false, destination, fog.getBuffer(FogRenderer.FogMode.WORLD),
					destination.fogData.color, !bossFog, false);
		} finally {
			DestinationLight.override = null;
			((GameRendererAccessor) renderer).planeshift$setMainRenderTarget(mainTarget);
			globals(renderer, client.level == null ? 0L : client.level.getGameTime(), partial, main.pos);
			RenderSystem.restoreProjectionMatrix();
			// Shared with the player's own renderer, and extract pointed them here.
			client.getBlockEntityRenderDispatcher().prepare(mainCamera.position());
			client.getEntityRenderDispatcher().prepare(mainCamera, client.crosshairPickEntity);
		}

		fog.endFrame();
		world.pool().endFrame();
		world.renderer().endFrame();

		return out;
	}

	/**
	 * Whether this fog colour means the far side has nothing to show.
	 *
	 * <p>No dimension's fog is truly black — the Nether's is dark red, the End's dark
	 * purple, the Overworld's the colour of its sky. Black is what is left when a world
	 * decides the camera is too far outside it to see anything at all, which is a different
	 * statement from "it is dark here".
	 */
	private static boolean hasNoFogOfItsOwn(Vector4f fog) {
		return Math.max(fog.x, Math.max(fog.y, fog.z)) < 0.005F;
	}

	/**
	 * Turns {@code projection}'s near plane onto the far side's plane, so the far side is
	 * drawn from the plane outwards rather than from the eye.
	 *
	 * <p>Without it the destination eye, which stands some way back from the far plane,
	 * draws whatever is between it and the opening: the far side of a wall, the ground a
	 * horizontal plane is sunk into. Through the opening that would read as the view
	 * being blocked by nothing.
	 *
	 * <p>The far plane is not looked up. A transform maps the near anchor onto the far
	 * one and the near facing onto the far facing, which is the whole plane.
	 */
	private static Matrix4f clipAtFarPlane(Matrix4f projection, Matrix4fc viewRotation,
			Plane plane, PlaneTransform transform, Vec3 eye, float depthFar) {
		Vec3 centre = transform.position(plane.anchor(), plane.anchor());
		Direction facing = transform.facing(plane.facing());
		Vec3 normal = facing.getUnitVec3();
		double side = eye.subtract(centre).dot(normal);

		// Standing all but in the plane there is no room between the eye and it to clip
		// against, and the maths for it degenerates.
		if (Math.abs(side) <= CLIP_MARGIN + Camera.PROJECTION_Z_NEAR) {
			return projection;
		}

		Vec3 away = normal.scale(-Math.signum(side));
		// The point of the far plane nearest the eye, not its anchor. Both name the same
		// plane, but an edgeless one anchors at the world origin, which may be thousands
		// of blocks away: the clip then works from a camera-relative point whose large
		// tangent components cancel almost exactly when the view rotation mixes them, and
		// what should be a distance of a few blocks is left as rounding error. Measured
		// along the normal instead, it stays small and stays exact.
		Vec3 point = normal.scale(-side).subtract(away.scale(CLIP_MARGIN));


		obliqueNearPlane(projection, viewRotation, point, away, depthFar);
		return projection;
	}

	/**
	 * Replaces the near plane of a perspective projection with the plane through
	 * {@code point} (camera-relative, world axes) whose {@code normal} points away from
	 * the camera: Lengyel's oblique clip.
	 *
	 * <p>Which clip depth this projection puts its near and far planes at is read out of the
	 * matrix rather than assumed. The whole method turns on those two numbers — they are
	 * what the new near plane is made to land on, and what the rest of the depth range is
	 * stretched to fill — and they are not the same everywhere: reverse-Z over minus one to
	 * one here, zero to one in 26.2, and whatever a machine that cannot do float depth falls
	 * back to. Assume them and the clip keeps precisely what it was asked to throw away,
	 * which shows up as a doorway full of the wall its far end is mounted on, on one
	 * player's machine and not another's, with nothing in either log to say so.
	 */
	static void obliqueNearPlane(Matrix4f projection, Matrix4fc viewRotation, Vec3 point, Vec3 normal,
			float depthFar) {
		Vector4f origin = new Vector4f((float) point.x, (float) point.y, (float) point.z, 1.0F);
		viewRotation.transform(origin);

		Vector4f direction = new Vector4f((float) normal.x, (float) normal.y, (float) normal.z, 0.0F);
		viewRotation.transform(direction);

		Vector3f unit = new Vector3f(direction.x, direction.y, direction.z).normalize();
		float distance = -(unit.x * origin.x + unit.y * origin.y + unit.z * origin.z);
		float cornerX = (Math.signum(unit.x) + projection.m20()) / projection.m00();
		float cornerY = (Math.signum(unit.y) + projection.m21()) / projection.m11();
		float near = depthAt(projection, Camera.PROJECTION_Z_NEAR);
		float far = depthAt(projection, depthFar);
		measuredNear = near;
		measuredFar = far;
		float cornerW = (far + projection.m22()) / projection.m32();
		float dot = unit.x * cornerX + unit.y * cornerY - unit.z + distance * cornerW;

		if (Math.abs(dot) < 1.0E-6F) {
			return;
		}

		float scale = (far - near) / dot;
		projection.m02(unit.x * scale);
		projection.m12(unit.y * scale);
		projection.m22(unit.z * scale - near);
		projection.m32(distance * scale);
	}

	/**
	 * Where this projection puts a point {@code depth} blocks down the view axis, in clip
	 * depth: the number the near plane is at when {@code depth} is the near distance.
	 *
	 * <p>Read out of the matrix, so it is right whatever range the game is using.
	 */
	private static float depthAt(Matrix4f projection, float depth) {
		// View space looks down negative z.
		float z = -depth;
		float clip = projection.m22() * z + projection.m32();
		float w = projection.m23() * z + projection.m33();

		return w == 0.0F ? clip : clip / w;
	}

	private static float measuredNear = Float.NaN;

	private static float measuredFar = Float.NaN;

	/**
	 * What the last far side's projection turned out to put its near and far planes at, for
	 * {@code /planeshiftwhy}. Null until a far side has been drawn.
	 */
	public static @Nullable String depthRange() {
		return Float.isNaN(measuredNear) ? null
				: String.format(java.util.Locale.ROOT, "near %.2f, far %.2f", measuredNear, measuredFar);
	}

	/** Lets go of what is held between worlds, and lets a failed pass be tried again. */
	public static void forget() {
		DestinationTarget.close();
		DestinationLight.close();
		quietUntil = 0L;
		quietFor = FIRST_QUIET;
		drawnInto.clear();
	}

	/** {@code GameRenderer.render}'s own call, for another camera and another clock. */
	private static void globals(GameRenderer renderer, long gameTime, float partial, Vec3 camera) {
		net.minecraft.client.renderer.state.GameRenderState game = renderer.gameRenderState();
		((GameRendererResourceAccessor) renderer).planeshift$globalSettingsUniform().update(
				game.windowRenderState.width, game.windowRenderState.height,
				game.optionsRenderState.glintStrength, gameTime, partial,
				game.optionsRenderState.menuBackgroundBlurriness, camera,
				game.optionsRenderState.textureFiltering
						== net.minecraft.client.TextureFilteringMethod.RGSS);
	}

	private static ProjectionMatrixBuffer projection() {
		if (projection == null) {
			projection = new ProjectionMatrixBuffer("planeshift destination");
		}

		return projection;
	}
}
