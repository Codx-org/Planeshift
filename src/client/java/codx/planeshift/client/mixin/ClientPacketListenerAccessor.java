package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Lets the connection be pointed at a level the client swapped to by itself.
 *
 * <p>The connection keeps writing arriving packets into its own level until it is told
 * otherwise. After a crossing that is the level the player has already left.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
	@Accessor("level")
	void planeshift$setLevel(ClientLevel level);

	@Accessor("levelData")
	void planeshift$setLevelData(ClientLevel.ClientLevelData data);

	/** The server's own view distance; a watch was streamed at its smaller one. */
	@Accessor("serverChunkRadius")
	int planeshift$serverChunkRadius();
}
