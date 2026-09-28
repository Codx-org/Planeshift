package codx.planeshift.debug;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.jspecify.annotations.Nullable;

import codx.planeshift.Planeshift;

/**
 * A file of its own for the per-tick trace.
 *
 * <p>Not the game log. A client and a dedicated server started from the same directory
 * share {@code logs/}, and whichever opens it second truncates it — which is how one
 * side's account of a crossing goes missing precisely when both are wanted.
 *
 * <p>Debug scaffolding: it goes when crossings are reliable.
 */
public final class TraceLog {
	private static @Nullable Writer writer;

	private TraceLog() {
	}

	/** Opens the file fresh, so each trace is its own session. */
	public static void open(Path path) {
		close();

		try {
			writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE);
			Planeshift.LOGGER.info("Tracing into {}", path.toAbsolutePath());
		} catch (IOException e) {
			Planeshift.LOGGER.warn("Could not open {}", path, e);
		}
	}

	/**
	 * Whether anything is listening.
	 *
	 * <p>Worth asking before building a line. Java works out the message before the call,
	 * so a trace in a hot path costs its string on every pass whether or not it is written
	 * — and the hottest of these runs once per spreading fluid per tick, in every level.
	 */
	public static boolean on() {
		return writer != null;
	}

	/** Flushed per line: a trace that is being read to explain a crash has to survive one. */
	public static void line(String text) {
		if (writer == null) {
			return;
		}

		try {
			writer.write(text);
			writer.write('\n');
			writer.flush();
		} catch (IOException e) {
			close();
		}
	}

	public static void close() {
		if (writer == null) {
			return;
		}

		try {
			writer.close();
		} catch (IOException e) {
			// Nothing useful to do; the trace is over either way.
		}

		writer = null;
	}
}
