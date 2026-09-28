package codx.planeshift;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The one file the mod's settings live in, read and written a few keys at a time.
 *
 * <p>Two halves write to it: what this machine will spend on drawing the far side of a
 * plane, which is the client's business, and what the world allows through one, which is
 * the server's. On a dedicated server only the second half exists, and on a client joining
 * one only the first half is used — but in single player both run, and neither should
 * remove the other's settings by writing the file as it understands it.
 *
 * <p>So nothing is ever written wholesale. Whatever is in the file is kept, and a half
 * that finds its own keys missing adds them with their defaults, which is also how a
 * setting added in a later version appears in a file written by an earlier one.
 */
public final class ConfigFile {
	private static final String NAME = "planeshift.json";

	private ConfigFile() {
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(NAME);
	}

	/** What the file holds, or an empty object if there is no file or it cannot be read. */
	public static JsonObject read() {
		Path path = path();

		if (!Files.exists(path)) {
			return new JsonObject();
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			// A hand-edited file with a typo in it should not stop the game starting.
			Planeshift.LOGGER.warn("Could not read {}; keeping the defaults", path, e);
			return new JsonObject();
		}
	}

	/** Writes it back, pretty-printed, so it stays worth editing by hand. */
	public static void write(JsonObject root) {
		Path path = path();

		try (Writer writer = Files.newBufferedWriter(path)) {
			new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
		} catch (IOException e) {
			Planeshift.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
