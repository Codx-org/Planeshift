package codx.planeshift.client.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.GameRenderer;

/** The pool a level render allocates its intermediate targets from, shared with the frame. */
@Mixin(GameRenderer.class)
public interface GameRendererResourceAccessor {
	/**
	 * The uniform block every shader reads the camera's position from.
	 *
	 * <p>Terrain is drawn as sections at absolute coordinates, made camera-relative in
	 * the shader by subtracting this. Left holding the player's own position, a far side
	 * drawn from somewhere else entirely lands that far from where it belongs — off the
	 * screen, for a plane that joins two dimensions at different heights.
	 */
	@org.spongepowered.asm.mixin.gen.Accessor("globalSettingsUniform")
	net.minecraft.client.renderer.GlobalSettingsUniform planeshift$globalSettingsUniform();

	@Accessor("resourcePool")
	CrossFrameResourcePool planeshift$resourcePool();
}
