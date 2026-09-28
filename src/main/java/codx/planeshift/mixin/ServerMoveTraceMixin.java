package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import codx.planeshift.debug.TraceLog;

import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a move the client sent was taken or thrown away, and what the server thought at
 * the time.
 *
 * <p>From outside, a rejected move and a move never sent look identical: the server's
 * position simply stops changing. This says which, and prints the state the handler
 * decides on, so the branch that drops it can be named rather than guessed at.
 *
 * <p>Debug scaffolding: it goes when crossings are reliable.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMoveTraceMixin {
	/**
	 * Every teleport the server decides to impose, and the call that decided it.
	 *
	 * <p>The snap back is always a teleport; what has never been established is who asks
	 * for one. Vanilla tolerates a lagging client without moving it, so something is
	 * choosing to, and its own stack says what.
	 */
	@WrapMethod(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V")
	private void planeshift$traceTeleport(net.minecraft.world.entity.PositionMoveRotation where,
			java.util.Set<net.minecraft.world.entity.Relative> relatives, Operation<Void> original) {
		StringBuilder from = new StringBuilder();

		for (StackTraceElement frame : new Throwable().getStackTrace()) {
			String name = frame.getClassName();

			if (name.contains("ServerMoveTraceMixin") || name.startsWith("java.")) {
				continue;
			}

			from.append("\n            <- ").append(name.substring(name.lastIndexOf('.') + 1))
					.append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());

			if (from.length() > 400) {
				break;
			}
		}

		TraceLog.line("        server TELEPORTING to " + where.position() + from);
		original.call(where, relatives);
	}

	/** Every acknowledgement the client sends, and whether its id is the one being waited on. */
	@WrapMethod(method = "handleAcceptTeleportPacket")
	private void planeshift$traceAck(ServerboundAcceptTeleportationPacket packet, Operation<Void> original) {
		int wanted = ((ServerGamePacketListenerAccessor) this).planeshift$awaitingTeleport();
		TraceLog.line("        server ACK from client id " + packet.id() + ", waiting on id " + wanted
				+ (packet.id() == wanted ? " (match)" : " (IGNORED)"));
		original.call(packet);
	}

	@WrapMethod(method = "handleMovePlayer")
	private void planeshift$traceMove(ServerboundMovePlayerPacket packet, Operation<Void> original) {
		ServerPlayer player = ((ServerGamePacketListenerAccessor) this).planeshift$player();
		Vec3 before = player.position();
		original.call(packet);
		Vec3 after = player.position();

		if (before.equals(after) && packet.hasPosition()) {
			Vec3 holding = ((ServerGamePacketListenerAccessor) this)
					.planeshift$awaitingPositionFromClient();
			TraceLog.line(String.format(
					"        server DROPPED move to %.3f %.3f %.3f (held at %.3f); awaiting %s id %d",
					packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0), before.y,
					holding == null ? "no" : holding.toString(),
					((ServerGamePacketListenerAccessor) this).planeshift$awaitingTeleport()));
		}
	}
}
