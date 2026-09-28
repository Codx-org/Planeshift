package codx.planeshift.debug;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import codx.planeshift.Planeshift;
import codx.planeshift.plane.Plane;

import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The hand-placed planes for one dimension, saved with that dimension.
 *
 * <p>Planes derived from world state are deliberately never saved — see
 * {@link codx.planeshift.registry.PlaneStore}. These are the exception: nothing
 * regenerates them, so clearing them on shutdown would just discard your work.
 *
 * <p>A malformed entry will throw out of {@link Plane}'s constructor while decoding
 * rather than failing softly, which would take the level's saved data down with it.
 * That is survivable for debug scaffolding — delete {@code data/planeshift_debug.dat}
 * in the dimension folder — and it is a reason not to extend persistence beyond here
 * without real {@link DataFixTypes} handling.
 */
public final class DebugPlaneData extends SavedData {
	static final Codec<DebugPlaneData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Plane.CODEC.listOf().fieldOf("planes").forGetter(data -> List.copyOf(data.planes))
	).apply(instance, DebugPlaneData::new));

	static final SavedDataType<DebugPlaneData> TYPE = new SavedDataType<>(
			Planeshift.id("debug_planes"),
			DebugPlaneData::new,
			CODEC,
			DataFixTypes.LEVEL);

	private final List<Plane> planes;

	DebugPlaneData() {
		this(List.of());
	}

	private DebugPlaneData(List<Plane> planes) {
		this.planes = new ArrayList<>(planes);
	}

	List<Plane> planes() {
		return planes;
	}

	void add(Plane plane) {
		planes.add(plane);
		setDirty();
	}

	int clear() {
		int removed = planes.size();
		planes.clear();
		setDirty();
		return removed;
	}
}
