package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;

/**
 * Lets an adopted level be pointed at the extractor that is actually drawing it.
 *
 * <p>A {@link ClientLevel} keeps the extractor it was constructed with, and tells that
 * one when its chunks change. A level built for a destination therefore goes on
 * reporting to the destination's extractor even after the game has adopted it and is
 * rendering from its own — so every update after the swap lands somewhere nobody draws.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
	@Mutable
	@Accessor("levelExtractor")
	void planeshift$setLevelExtractor(LevelExtractor extractor);
}
