package codx.planeshift.client.mixin;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/**
 * Lets a camera be placed by hand, rather than following an entity.
 *
 * <p>Drawing the far side of a plane means looking from where the player's eye would
 * be if they had already stepped through — a place no entity stands. Everything vanilla
 * does when it places the frame's own camera has to be done here instead, including
 * preparing the cull frustum: without that, nothing is drawn at all.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
	@Invoker("setPosition")
	void planeshift$setPosition(Vec3 position);

	@Invoker("setRotation")
	void planeshift$setRotation(float yRot, float xRot);

	@Invoker("setupPerspective")
	void planeshift$setupPerspective(float near, float far, float fov, float width, float height);

	@Invoker("prepareCullFrustum")
	void planeshift$prepareCullFrustum(Matrix4fc viewRotation, Matrix4f projection, Vec3 position);

	@Invoker("createProjectionMatrixForCulling")
	Matrix4f planeshift$createProjectionMatrixForCulling();

	@Accessor("fov")
	void planeshift$setFov(float fov);

	@Accessor("hudFov")
	void planeshift$setHudFov(float fov);

	@Accessor("depthFar")
	void planeshift$setDepthFar(float far);

	@Accessor("initialized")
	void planeshift$setInitialized(boolean initialized);
}
