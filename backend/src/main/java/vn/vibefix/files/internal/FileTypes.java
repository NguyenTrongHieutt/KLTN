package vn.vibefix.files.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.ParseContext;

import org.jspecify.annotations.Nullable;

import vn.vibefix.files.FileKind;

/**
 * What a file is, from its bytes alone (AD-21): tika-core's magic patterns with its plain-text
 * fallback, never the name the client sent and never a container parser. Only the types below are
 * known; everything else, SVG, HTML, XML, WebP, and HEIC included, is not.
 */
final class FileTypes {

	/**
	 * @param extension what a stored name ends with
	 * @param imageFormat the image format the sandbox reads, for an image only
	 */
	record Detected(FileKind kind, String mediaType, String extension, @Nullable String imageFormat) {
	}

	private static final Map<String, Detected> KNOWN = Map.ofEntries(
			image("image/jpeg", "jpg", "jpeg"), image("image/png", "png", "png"), image("image/gif", "gif", "gif"),
			image("image/bmp", "bmp", "bmp"), image("image/x-ms-bmp", "bmp", "bmp"),
			other(FileKind.PDF, "application/pdf", "pdf"), other(FileKind.TEXT, "text/plain", "txt"),
			other(FileKind.ARCHIVE, "application/zip", "zip"), other(FileKind.ARCHIVE, "application/x-7z-compressed", "7z"),
			other(FileKind.ARCHIVE, "application/x-rar-compressed", "rar"),
			other(FileKind.ARCHIVE, "application/vnd.rar", "rar"), other(FileKind.ARCHIVE, "application/gzip", "gz"),
			other(FileKind.ARCHIVE, "application/x-gzip", "gz"), other(FileKind.ARCHIVE, "application/x-tar", "tar"),
			other(FileKind.ARCHIVE, "application/x-gtar", "tar"), other(FileKind.ARCHIVE, "application/x-bzip2", "bz2"),
			other(FileKind.ARCHIVE, "application/x-xz", "xz"));

	private static final MimeTypes MAGIC = MimeTypes.getDefaultMimeTypes();

	private FileTypes() {
	}

	/** The known type of {@code content}, if it is one. */
	static Optional<Detected> detect(byte[] content) {
		if (content.length == 0) {
			return Optional.empty();
		}
		try (TikaInputStream stream = TikaInputStream.get(content)) {
			MediaType type = MAGIC.detect(stream, new Metadata(), new ParseContext());
			return Optional.ofNullable(KNOWN.get(type.getBaseType().toString()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Map.Entry<String, Detected> image(String mediaType, String extension, String format) {
		return Map.entry(mediaType, new Detected(FileKind.IMAGE, mediaType, extension, format));
	}

	private static Map.Entry<String, Detected> other(FileKind kind, String mediaType, String extension) {
		return Map.entry(mediaType, new Detected(kind, mediaType, extension, null));
	}

}
