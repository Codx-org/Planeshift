package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/**
 * Reaches the one prepared frame the whole game shares.
 *
 * <p>Only so that a far side which failed halfway through can let go of it. A frame is begun
 * per level render and must be closed before the next one begins; a render that throws never
 * gets to its own close, and every frame after it dies on "PreparedFrame already in use" —
 * which is a crash, not a dropped frame.
 */
@Mixin(FeatureRenderDispatcher.class)
public interface FeatureFrameAccessor {
	@Accessor("preparedFrame")
	FeatureRenderDispatcher.PreparedFrame planeshift$preparedFrame();
}
