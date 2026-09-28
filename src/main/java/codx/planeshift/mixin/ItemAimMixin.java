package codx.planeshift.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import codx.planeshift.interact.FarAim;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Aims an item through a plane.
 *
 * <p>This is how every item that is used by right-clicking into space finds what it is
 * pointing at — a bucket emptying or filling, above all. It is handed a level, but it takes
 * the ray itself from the player, and through a plane the player is in the wrong world.
 *
 * <p>Left alone, a bucket emptied through a plane pours into the dimension the player is
 * standing in, at coordinates they cannot see, while their own client shows it landing on
 * the far side. The fluid appears and then vanishes, which is the server and the client
 * having done two different things.
 */
@Mixin(Item.class)
public class ItemAimMixin {
	@WrapMethod(method = "getPlayerPOVHitResult")
	private static BlockHitResult planeshift$aimThroughPlane(Level level, Player player,
			ClipContext.Fluid fluidMode, Operation<BlockHitResult> original) {
		FarAim aim = FarAim.of(player.getUUID());

		if (aim == null) {
			return original.call(level, player, fluidMode);
		}

		Vec3 from = aim.eye();
		Vec3 to = from.add(aim.view().scale(aim.reach()));
		return aim.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, fluidMode, player));
	}
}
