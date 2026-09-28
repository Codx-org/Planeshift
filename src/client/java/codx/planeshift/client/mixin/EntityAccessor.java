package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Lets the player be carried from one level to another without being respawned.
 *
 * <p>Leaving a level marks an entity removed, and an entity that is removed will not be
 * ticked by the level it arrives in. Vanilla never needs to undo that because it builds
 * a new player; a crossing keeps the same one, so it has to.
 */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("removalReason")
	void planeshift$setRemovalReason(Entity.@org.jspecify.annotations.Nullable RemovalReason reason);

	@Invoker("setLevel")
	void planeshift$setLevel(Level level);
}
