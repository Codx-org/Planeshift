package codx.planeshift.client.render;

import java.util.Optional;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

import codx.planeshift.Planeshift;
import codx.planeshift.plane.Plane;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Shows the far side through the opening, once this world has been drawn.
 *
 * <p>Blaze3D has had no stencil since the Vulkan rewrite, so there is no mask to stamp
 * the opening with. Instead one full-screen pass decides per pixel, in
 * {@code plane_composite.fsh}: cast the eye ray, meet the plane, and keep the pixel if
 * the meeting point is inside the opening with nothing of this world in front of it.
 *
 * <p>A ray cast rather than a drawn quad, and that is the point. A quad shrinks to a
 * sliver as the eye reaches the surface of the plane; a ray cast opens out to fill the
 * view, which is what stepping through a doorway looks like.
 *
 * <p>There is no geometry at all: {@code core/screenquad} makes its three vertices out
 * of {@code gl_VertexIndex}, so the whole pass is one draw call with no buffer behind it.
 */
final class PlaneComposite {
	/**
	 * How far past the plane a surface of this world may be and still be drawn over, in
	 * blocks. Depth read back from a texture is not exact, and a surface lying in the
	 * plane itself — the floor an infinite plane runs along — would otherwise flicker
	 * between covering the opening and not.
	 */
	private static final float GUARD = 0.02F;

	private static RenderPipeline pipeline;
	private static TextureTarget depth;

	private PlaneComposite() {
	}

	/**
	 * @param main the frame's own target, drawn into
	 * @param image the far side, already drawn
	 * @param proj the projection this frame was drawn with, view bob and all
	 * @param reach how far a pixel's view through the opening may be before it has faded to
	 *              nothing, in blocks. Judged where the ray meets the plane rather than at the
	 *              plane as a whole: an edgeless plane is under your feet and also out at the
	 *              horizon, and the part of it at the horizon is thousands of blocks away
	 *              through nothing but sky — which is a dark band painted over it
	 */
	static void draw(RenderTarget main, TextureTarget image, CameraRenderState camera,
			Matrix4f proj, Plane plane, float reach) {
		TextureTarget copied = depth(main.width, main.height);
		// The frame's own depth texture is an attachment while the frame is being drawn,
		// and an attachment cannot also be sampled. A copy can.
		copied.copyDepthFrom(main);

		Matrix4f inverse = new Matrix4f(proj).mul(camera.viewRotationMatrix).invert();
		PlaneGeometry opening = PlaneGeometry.of(plane, camera.pos);
		// Camera-relative, because the shader works in float and world coordinates are
		// far too large to subtract two of accurately there.
		Vec3 centre = opening.centre().subtract(camera.pos);

		Matrix4f packed = new Matrix4f();
		packed.setColumn(0, column(centre, 0.0F));
		packed.setColumn(1, column(opening.right(), 0.0F));
		packed.setColumn(2, column(opening.up(), 0.0F));
		packed.setColumn(3, new Vector4f(opening.halfWidth(), opening.halfHeight(), GUARD, reach));

		// How to read the depth texture back, measured from the projection that wrote it.
		// A depth texture always holds zero to one; what that means in clip depth does not
		// follow. Here it is reverse-Z over minus one to one, so the stored half has to be
		// doubled and shifted; elsewhere it is reverse-Z over zero to one, where the stored
		// value is already the answer and doubling it puts every surface of this world at
		// twice its distance. That reads as the wall behind an opening standing in front of
		// it, and the opening shows the wall rather than the way through.
		float floor = Math.min(depthAt(proj, Camera.PROJECTION_Z_NEAR), depthAt(proj, camera.depthFar));
		float ceiling = Math.max(depthAt(proj, Camera.PROJECTION_Z_NEAR), depthAt(proj, camera.depthFar));

		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(inverse,
				column(opening.normal(), floor),
				new Vector3f(main.width, main.height, ceiling - floor),
				packed);
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "planeshift plane composite", main.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline()));
			pass.setUniform("DynamicTransforms", transforms);
			pass.setUniform("Sampler0", image.getColorTextureView(), sampler);
			pass.setUniform("Sampler1", copied.getDepthTextureView(), sampler);
			// Three vertices, one instance: screenquad builds a triangle covering the
			// screen out of nothing but their indices.
			pass.draw(3, 1, 0, 0);
		}
	}

	/**
	 * Where this projection puts a point {@code depth} blocks down the view axis, in clip
	 * depth. The same reading {@code DestinationRenderer} takes, for the same reason.
	 */
	private static float depthAt(Matrix4f projection, float depth) {
		float z = -depth;
		float clip = projection.m22() * z + projection.m32();
		float w = projection.m23() * z + projection.m33();

		return w == 0.0F ? clip : clip / w;
	}

	private static Vector4f column(Vec3 value, float w) {
		return new Vector4f((float) value.x, (float) value.y, (float) value.z, w);
	}

	private static RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath(
							Planeshift.MOD_ID, "pipeline/plane_composite"))
					// Vanilla's own full-screen vertex shader; nothing of ours to add.
					.withVertexShader("core/screenquad")
					.withFragmentShader(Identifier.fromNamespaceAndPath(
							Planeshift.MOD_ID, "core/plane_composite"))
					.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
					.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
					// Blended rather than opaque: a kept pixel is mixed into what this world
					// drew there, by however much of the far side is meant to be showing.
					// At full strength that is the same as replacing it.
					.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
							GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.withCull(false)
					.build();
		}

		return pipeline;
	}

	private static TextureTarget depth(int width, int height) {
		if (depth == null || depth.width != width || depth.height != height) {
			close();
			depth = new TextureTarget("planeshift scene depth", width, height,
					GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		}

		return depth;
	}

	static void close() {
		if (depth != null) {
			depth.destroyBuffers();
			depth = null;
		}
	}
}
