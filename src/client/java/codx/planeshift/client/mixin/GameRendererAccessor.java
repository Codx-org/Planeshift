package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.renderer.GameRenderer;

/**
 * Lets the destination be drawn somewhere other than the screen.
 *
 * <p>A {@code LevelRenderer} draws into whatever the game renderer calls its main
 * target. Rendering the far side of a plane means pointing that at an offscreen
 * texture for the length of the call and putting it back afterwards — the renderer
 * itself needs no changing, which is the whole appeal.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Mutable
	@Accessor("mainRenderTarget")
	void planeshift$setMainRenderTarget(RenderTarget target);
}
