package codx.planeshift.client.mixin;

import java.nio.ByteBuffer;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

/**
 * Makes the section buffers safe to touch from two renderers at once.
 *
 * <p>{@link UberGpuBuffer} is process-wide and not thread-safe. With only the player's
 * own {@link net.minecraft.client.renderer.LevelRenderer} it is only ever driven by one
 * set of compile workers, so nothing ever noticed. A second renderer meshing the
 * destination adds another set, and the two corrupt the allocator between them.
 *
 * <p>Locked on the <em>staging buffer</em>, not on the uber buffer. Several uber buffers
 * — vertices and indices — are built around one staging buffer, so a lock per uber buffer
 * still lets two threads write the same staging area through different ones. What that
 * looks like is a byte copy against an offset another thread has already moved: SIGBUS
 * with an alignment code, on a worker, some way from the race that caused it.
 */
@Mixin(UberGpuBuffer.class)
public class UberGpuBufferMixin {
	@Shadow
	@Final
	private StagingBuffer stagingBuffer;

	@WrapMethod(method = "addAllocation")
	private <U> boolean planeshift$lockAdd(U value, UberGpuBuffer.UploadCallback<U> callback, ByteBuffer buffer,
			Operation<Boolean> original) {
		synchronized (this.stagingBuffer) {
			return original.call(value, callback, buffer);
		}
	}

	@WrapMethod(method = "uploadStagedAllocations")
	private boolean planeshift$lockUpload(GpuDevice device, StagingBuffer.Uploader uploader,
			Operation<Boolean> original) {
		synchronized (this.stagingBuffer) {
			return original.call(device, uploader);
		}
	}

	@WrapMethod(method = "removeAllocation")
	private void planeshift$lockRemove(Object value, Operation<Void> original) {
		synchronized (this.stagingBuffer) {
			original.call(value);
		}
	}
}
