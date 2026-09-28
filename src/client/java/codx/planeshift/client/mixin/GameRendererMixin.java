package codx.planeshift.client.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;

import codx.planeshift.client.render.DestinationLight;
import codx.planeshift.client.render.SeeThrough;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.renderer.GameRenderer;

/**
 * Straddles the frame's own level render with the see-through pass.
 *
 * <p>Before it, because drawing the far side swaps the main render target out from
 * under the game — better done and undone before anything else in the frame relies on
 * it. After it, because showing that image through the opening means reading the depth
 * this world was just drawn with.
 *
 * <p>Both take the local projection rather than the camera state's own, because this is
 * where view bob and the nausea spin have been folded in: it is the matrix the frame is
 * actually drawn with, and the composite reconstructs eye rays from its inverse.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	private static final String LEVEL_RENDER =
			"Lnet/minecraft/client/renderer/LevelRenderer;render("
			+ "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Z"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
			+ "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V";

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = LEVEL_RENDER))
	private void planeshift$drawDestination(CallbackInfo info, @Local Matrix4f projection) {
		SeeThrough.beforeLevel((GameRenderer) (Object) this, projection);
	}

	@Inject(method = "renderLevel",
			at = @At(value = "INVOKE", target = LEVEL_RENDER, shift = At.Shift.AFTER))
	private void planeshift$showDestination(CallbackInfo info, @Local Matrix4f projection) {
		SeeThrough.afterLevel((GameRenderer) (Object) this, projection);
	}

	/**
	 * Every world draw asks for the lightmap here, so this is the one place the far side
	 * can be lit by its own sky and its own block light rather than by the viewer's.
	 */
	@Inject(method = "lightmap", at = @At("HEAD"), cancellable = true)
	private void planeshift$destinationLightmap(CallbackInfoReturnable<GpuTextureView> info) {
		if (DestinationLight.override != null) {
			info.setReturnValue(DestinationLight.override);
		}
	}
}
