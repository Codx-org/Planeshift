package codx.planeshift.client.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;

import codx.planeshift.Planeshift;

/**
 * The offscreen images the far sides are drawn into, before they are shown through their
 * openings.
 *
 * <p>One per plane being seen through, grown as far as the player's setting allows and no
 * further.
 *
 * <p>Not necessarily the size of the frame. The composite samples these by where a pixel is
 * on screen rather than by where it is in the image, so a far side drawn at half the width
 * lands in exactly the same place — only softer. That is the cheapest thing there is to
 * spend on a doorway across the room: a quarter of the pixels, for a view that is a few
 * hundred across by the time it reaches the screen.
 *
 * <p>Sizes come in steps rather than sliding with distance. Changing one means building a
 * new image and throwing the old one away, and doing that as somebody walks slowly towards
 * a portal would cost far more than the sharpness it bought.
 */
public final class DestinationTarget {
	/** The sizes a far side is ever drawn at, as a fraction of the frame. */
	private static final float[] STEPS = {1.0F, 0.75F, 0.5F, 0.35F, 0.25F};

	private static final List<Held> targets = new ArrayList<>();

	private record Held(TextureTarget target, int width, int height) {
	}

	private DestinationTarget() {
	}

	/** The nearest size this far side may be drawn at, at or below what was asked for. */
	public static float step(float detail) {
		float chosen = STEPS[STEPS.length - 1];

		for (float step : STEPS) {
			if (detail >= step) {
				chosen = step;
				break;
			}
		}

		return chosen;
	}

	/** The image for the {@code index}-th plane of this frame, building it as needed. */
	public static TextureTarget get(int index, int wanted, int wantedHeight) {
		int width = Math.max(1, wanted);
		int height = Math.max(1, wantedHeight);

		while (targets.size() <= index) {
			targets.add(null);
		}

		Held held = targets.get(index);

		if (held != null && held.width() == width && held.height() == height) {
			return held.target();
		}

		if (held != null) {
			held.target().destroyBuffers();
		}

		TextureTarget built = new TextureTarget("planeshift destination " + index,
				width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		targets.set(index, new Held(built, width, height));
		Planeshift.LOGGER.info("Destination target {} at {}x{}", index, width, height);
		return built;
	}

	public static void close() {
		for (Held held : targets) {
			if (held != null) {
				held.target().destroyBuffers();
			}
		}

		targets.clear();
	}
}
