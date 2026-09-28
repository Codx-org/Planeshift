package codx.planeshift.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/**
 * Whether the server is holding a player still, waiting to be told they arrived.
 *
 * <p>While this is set, every move the client sends is discarded. From the outside that
 * is indistinguishable from a client that has stopped sending — and the two want opposite
 * fixes, so the trace has to be able to tell them apart.
 *
 * <p>Debug scaffolding: it goes when crossings are reliable.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
	@Accessor("awaitingPositionFromClient")
	Vec3 planeshift$awaitingPositionFromClient();

	@Accessor("awaitingTeleport")
	int planeshift$awaitingTeleport();

	@Accessor("player")
	net.minecraft.server.level.ServerPlayer planeshift$player();
}
