package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;

/**
 * Lets a crossing adopt the destination's renderer and extractor as the player's.
 *
 * <p>Swapping the objects keeps every compiled section. Pointing the game's own
 * renderer at the new level instead throws all of them away and meshes the world again
 * from nothing, which is the pause — and, when the level and the extractor drawing it
 * disagree about who owns whom, the void.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Mutable
	@Accessor("levelRenderer")
	void planeshift$setLevelRenderer(LevelRenderer renderer);

	@Mutable
	@Accessor("levelExtractor")
	void planeshift$setLevelExtractor(LevelExtractor extractor);

	/**
	 * The crosshair pick, re-run at a swap: it is taken once a tick, so until the next
	 * one the block outline would be drawn around a block in the level just left.
	 */
	@Invoker("pick")
	void planeshift$pick(float partialTick);
}
