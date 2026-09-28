package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Lets an extractor be moved onto the render state the game is drawing from.
 *
 * <p>A renderer and its extractor write into a render state, and the game draws one
 * particular state each frame. Adopting a renderer without moving it onto that state
 * leaves it filling in a buffer nobody reads.
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
	@Mutable
	@Accessor("levelRenderState")
	void planeshift$setLevelRenderState(LevelRenderState state);

	/**
	 * Asks for the sky renderer to be built again.
	 *
	 * <p>The flag on the render state is not the one to set. The extractor keeps its own and
	 * copies it over on every extract, clearing its own as it goes — so anything written
	 * onto the state is wiped by the next frame before the renderer ever reads it. This is
	 * where the answer has to be put.
	 */
	@Accessor("shouldResetSkyRenderer")
	void planeshift$setShouldResetSkyRenderer(boolean reset);
}
