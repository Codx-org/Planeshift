package codx.planeshift.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import codx.planeshift.client.interact.FarHand;

import net.minecraft.client.Minecraft;

/**
 * Lets the three things the mouse buttons do happen on the other side of a plane.
 *
 * <p>Wrapped rather than replaced. Mining has a progress, a crack animation, a cooldown and
 * a prediction, and using has its own order of hands and results; none of that is worth
 * writing twice. All of it reads the level and the hit from the game, so pointing those at
 * the far side is the whole change.
 */
@Mixin(Minecraft.class)
public class MinecraftFarHandMixin {
	@WrapMethod(method = "startAttack")
	private boolean planeshift$attackThrough(Operation<Boolean> original) {
		java.util.function.Supplier<Boolean> body = original::call;
		return FarHand.reaching((Minecraft) (Object) this, body);
	}

	@WrapMethod(method = "continueAttack")
	private void planeshift$keepAttackingThrough(boolean leftDown, Operation<Void> original) {
		Runnable body = () -> original.call(leftDown);
		FarHand.reaching((Minecraft) (Object) this, body);
	}

	@WrapMethod(method = "startUseItem")
	private void planeshift$useThrough(Operation<Void> original) {
		Runnable body = original::call;
		FarHand.reaching((Minecraft) (Object) this, body);
	}
}
