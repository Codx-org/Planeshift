package codx.planeshift.client.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import codx.planeshift.client.interact.FarHit;
import codx.planeshift.client.interact.PlaneTargeting;

import net.minecraft.client.gui.components.debug.DebugEntryLookingAt;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.HitResult;

/**
 * Makes the debug screen say what is being pointed at when that is in another dimension.
 *
 * <p>These entries do not read the game's pick. They cast their own twenty-block ray, in
 * the level the camera is in, so through a plane they describe whatever sits behind the
 * opening on this side: a block the player is not pointing at, or nothing at all.
 *
 * <p>Two values are replaced and everything else is left alone — the ray's answer, and the
 * level the answer is looked up in. The block entry, the fluid entry and both tag lists
 * then all read the far side without knowing anything about planes, and a line naming the
 * dimension is added so it is clear which world the coordinates belong to.
 */
@Mixin(DebugEntryLookingAt.class)
public abstract class LookingAtThroughPlaneMixin {
	@Shadow
	@Final
	private static Identifier BLOCK_GROUP;

	@Shadow
	public abstract Identifier group();

	@ModifyExpressionValue(method = "display",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/debug/DebugEntryLookingAt;"
							+ "getHitResult(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;"))
	private HitResult planeshift$hitThroughPlane(HitResult original) {
		FarHit far = PlaneTargeting.current();
		return far == null ? original : far.hit();
	}

	@ModifyExpressionValue(method = "display",
			at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.GETFIELD,
					target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
	private ClientLevel planeshift$levelBeyondPlane(ClientLevel original) {
		FarHit far = PlaneTargeting.current();
		return far == null ? original : far.world().level();
	}

	/**
	 * Says which world those coordinates are in.
	 *
	 * <p>Once only, on the block group, rather than on every group that happens to be
	 * reading the far side — the dimension is the same for all of them.
	 */
	@ModifyArg(method = "display",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;"
							+ "addToGroup(Lnet/minecraft/resources/Identifier;Ljava/util/Collection;)V"),
			index = 1)
	private Collection<String> planeshift$sayWhichDimension(Collection<String> lines) {
		FarHit far = PlaneTargeting.current();

		if (far == null || lines.isEmpty() || !group().equals(BLOCK_GROUP)) {
			return lines;
		}

		List<String> said = new ArrayList<>(lines.size() + 1);
		said.add(lines.iterator().next());
		said.add("through plane #" + far.planeId() + " in " + far.world().dimension().identifier());
		lines.stream().skip(1).forEach(said::add);
		return said;
	}
}
