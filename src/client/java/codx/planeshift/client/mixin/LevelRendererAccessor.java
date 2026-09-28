package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Lets a second renderer be given its own render state.
 *
 * <p>{@link LevelRenderer}'s constructor takes the state from the game renderer, which
 * the player's own extractor overwrites every frame. A destination renderer sharing
 * that would have its contents replaced before it ever drew.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	@Accessor("levelRenderState")
	LevelRenderState planeshift$levelRenderState();

	@Mutable
	@Accessor("levelRenderState")
	void planeshift$setLevelRenderState(LevelRenderState state);
}
