package codx.planeshift.client.render;

import org.jspecify.annotations.Nullable;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.EndFlashState;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * The far side's own lightmap.
 *
 * <p>Vanilla builds one 16×16 lightmap a frame from the player's camera, and every world
 * draw samples it through {@code GameRenderer.lightmap()}. Without this, the nether seen
 * from the overworld is lit by the overworld's curve — no block light to speak of and a
 * sky factor for a sky it does not have — so netherrack comes out black while lava, which
 * carries its own light, still glows. That is the whole of why the far side looks dark.
 *
 * <p>A second {@link Lightmap} rather than a re-render of vanilla's: that one's texture
 * is already being sampled by this world's draws submitted earlier in the frame, and
 * overwriting it partway through would relight them.
 *
 * <p>The player's half of the state carries over untouched — gamma, night vision,
 * darkness, the boss-bar dimming all belong to the viewer, not to the world being looked
 * at. Only the fields that come from where the camera stands are re-read, from the
 * destination camera's own environment probe.
 */
public final class DestinationLight {
	private static final LightmapRenderState STATE = new LightmapRenderState();

	private static @Nullable Lightmap lightmap;

	/** Handed out in place of the frame's own, for the length of the far side's render. */
	public static @Nullable GpuTextureView override;

	private DestinationLight() {
	}

	/** Builds the far side's lightmap from {@code camera}'s surroundings. */
	static void update(LightmapRenderState main, Camera camera, ClientLevel level, float partial) {
		if (main.blockLightTint == null) {
			// Vanilla has not extracted yet this frame, so there is no player half to keep.
			return;
		}

		fill(main, camera, level, partial);

		if (lightmap == null) {
			lightmap = new Lightmap();
		}

		lightmap.render(STATE);
	}

	private static void fill(LightmapRenderState main, Camera camera, ClientLevel level, float partial) {
		STATE.needsUpdate = true;
		STATE.blockFactor = main.blockFactor;
		STATE.brightness = main.brightness;
		STATE.darknessEffectScale = main.darknessEffectScale;
		STATE.nightVisionEffectIntensity = main.nightVisionEffectIntensity;
		STATE.bossOverlayWorldDarkening = main.bossOverlayWorldDarkening;

		// The same fields, in the same order, as LightmapRenderStateExtractor reads for
		// the frame's own camera — asked of a probe standing on the far side instead.
		EnvironmentAttributeProbe probe = camera.attributeProbe();
		STATE.blockLightTint = probe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, partial);
		STATE.skyFactor = probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partial);
		STATE.skyLightColor = probe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, partial);
		STATE.ambientColor = probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, partial);
		STATE.nightVisionColor = probe.getValue(EnvironmentAttributes.NIGHT_VISION_COLOR, partial);

		Minecraft client = Minecraft.getInstance();
		EndFlashState flash = level.endFlashState();

		if (flash != null && !client.options.hideLightningFlash().get()) {
			float intensity = flash.getIntensity(partial);
			STATE.skyFactor += client.gui.hud.getBossOverlay().shouldCreateWorldFog()
					? intensity / 3.0F
					: intensity;
		}
	}

	/** The far side's lightmap, or null before the first {@link #update}. */
	static @Nullable GpuTextureView texture() {
		return lightmap == null ? null : lightmap.getTextureView();
	}

	static void close() {
		override = null;

		if (lightmap != null) {
			lightmap.close();
			lightmap = null;
		}
	}
}
