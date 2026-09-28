package codx.planeshift.client.interact;

import org.jspecify.annotations.Nullable;

import codx.planeshift.client.DestinationWorld;
import codx.planeshift.plane.Plane;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The block outline for something on the far side of a plane.
 *
 * <p>The destination's extract fills its render state from {@code Minecraft.hitResult},
 * which is the near side's — a miss whenever a far side has won the pick. So the outline
 * over there is built here instead and written into that state before the draw.
 *
 * <p>This follows {@code LevelExtractor.extractBlockOutline}, against the far level: the
 * high-contrast option needs the extra shapes, plain outlines need only the one.
 */
public final class FarOutline {
	private FarOutline() {
	}

	/** The outline to draw in {@code world}, or null if the target is not in it. */
	public static @Nullable BlockOutlineRenderState of(Minecraft client, Plane plane,
			DestinationWorld world, Camera camera) {
		FarHit far = PlaneTargeting.current();

		if (far == null || far.plane() != plane || far.world() != world) {
			return null;
		}

		BlockPos pos = far.hit().getBlockPos();
		BlockState state = world.level().getBlockState(pos);

		if (state.isAir() || !world.level().getWorldBorder().isWithinBounds(pos)) {
			return null;
		}

		BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(state);
		boolean translucent = model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
		boolean highContrast = client.options.highContrastBlockOutline().get();
		CollisionContext context = CollisionContext.of(camera.entity());

		if (highContrast) {
			return new BlockOutlineRenderState(pos, translucent, true,
					state.getShape(world.level(), pos, context),
					state.getCollisionShape(world.level(), pos, context),
					state.getOcclusionShape(),
					state.getInteractionShape(world.level(), pos));
		}

		return new BlockOutlineRenderState(pos, translucent, false,
				state.getShape(world.level(), pos, context));
	}
}
