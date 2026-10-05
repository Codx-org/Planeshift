package codx.planeshift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codx.planeshift.client.DestinationInbox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * A world you are only looking at does not make any noise.
 *
 * <p>A far side is a whole live level on the client: its entities are ticked so that they
 * move, and an entity that is ticked plays its own sounds. Nothing says where those sounds
 * are relative to the player, so they arrive at full volume by their coordinates — and in
 * a stack of worlds the coordinates line up, which is how you end up hearing the ender
 * dragon beating its wings at the same spot in every dimension, at any height.
 *
 * <p>Asked of the level the player is really in, not of {@code Minecraft.level}: that field
 * is lent to a far side while its packets are handled, and a sound made during that window
 * is one of this world's that would otherwise be swallowed.
 */
@Mixin(ClientLevel.class)
public abstract class FarSideSilenceMixin {
	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;"
			+ "Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), cancellable = true)
	private void planeshift$notFromOverThere(Entity except, double x, double y, double z,
			Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
			CallbackInfo info) {
		if (elsewhere()) {
			info.cancel();
		}
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;"
			+ "Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
			at = @At("HEAD"), cancellable = true)
	private void planeshift$notFromOverThereEither(Entity except, Entity at,
			Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
			CallbackInfo info) {
		if (elsewhere()) {
			info.cancel();
		}
	}

	@Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;"
			+ "Lnet/minecraft/sounds/SoundSource;FFZ)V", at = @At("HEAD"), cancellable = true)
	private void planeshift$noLocalNoiseFromAfar(double x, double y, double z, SoundEvent sound,
			SoundSource source, float volume, float pitch, boolean distance, CallbackInfo info) {
		if (elsewhere()) {
			info.cancel();
		}
	}

	/** Whether this level is one the player is looking into rather than standing in. */
	private boolean elsewhere() {
		Minecraft client = Minecraft.getInstance();
		return (Object) this != DestinationInbox.playersOwnLevel(client);
	}
}
