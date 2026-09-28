package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.client.interact.PlaneTargeting;

import net.minecraft.client.Minecraft;

/**
 * Lets the crosshair reach through a plane.
 *
 * <p>After the pick, not instead of it: the near side's answer is what decides whether the
 * ray got as far as the opening, so it has to be worked out first and is then replaced if
 * the far side won.
 */
@Mixin(Minecraft.class)
public class MinecraftPickMixin {
	@Inject(method = "pick(F)V", at = @At("TAIL"))
	private void planeshift$pickThrough(float partialTick, CallbackInfo info) {
		PlaneTargeting.afterPick((Minecraft) (Object) this, partialTick);
	}
}
