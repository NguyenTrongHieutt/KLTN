package vn.vibefix.files.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobHandler;

/**
 * {@value #TYPE} (AD-21, AD-32): decodes an uploaded image and encodes it again, so nothing but the
 * pixels survives: no EXIF, location, comments, or embedded profiles. The width and height are read
 * from the header first, and an image above {@value #MAX_PIXELS} pixels is refused before decoding.
 * A JPEG stays a JPEG; every other format becomes a PNG.
 * <p>
 * Input: {@code content} (Base64) and {@code format} ({@code jpeg}, {@code png}, {@code gif} or
 * {@code bmp}, as {@code files} detected it). Output: {@code content}, {@code mediaType} and
 * {@code extension}; or {@code refused}, {@value #IMAGE_TOO_LARGE} or {@value #UNREADABLE}, for
 * content that is not an image this handler will encode. Like every handler, a plain class that
 * only the sandbox worker (or, on the dev stack, the in-process stand-in) instantiates.
 */
final class ImageReencodeJobHandler implements SandboxJobHandler {

	static final String TYPE = "IMAGE_REENCODE";

	static final long MAX_PIXELS = 40_000_000L;

	static final String IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE";

	static final String UNREADABLE = "UNREADABLE";

	private static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;

	private static final Set<String> FORMATS = Set.of("jpeg", "png", "gif", "bmp");

	private static final float JPEG_QUALITY = 0.9f;

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public JsonNode handle(JsonNode input) {
		JsonNode content = input.path("content");
		JsonNode format = input.path("format");
		if (!content.isString() || !format.isString() || !FORMATS.contains(format.stringValue())) {
			throw new IllegalArgumentException("content must be Base64 and format one of " + FORMATS);
		}
		byte[] bytes = Base64.getDecoder().decode(content.stringValue());
		if (bytes.length == 0 || bytes.length > MAX_INPUT_BYTES) {
			throw new IllegalArgumentException("content must hold 1 byte to 10 MB");
		}
		// No cache files: the sandbox's root filesystem is read-only, and nothing needs one.
		ImageIO.setUseCache(false);
		BufferedImage image;
		try {
			image = decode(bytes, format.stringValue());
		}
		catch (TooLarge ex) {
			return refused(IMAGE_TOO_LARGE);
		}
		if (image == null) {
			return refused(UNREADABLE);
		}
		boolean jpeg = format.stringValue().equals("jpeg");
		ObjectNode output = JsonNodeFactory.instance.objectNode();
		output.put("content", Base64.getEncoder().encodeToString(jpeg ? jpeg(image) : png(image)));
		output.put("mediaType", jpeg ? "image/jpeg" : "image/png");
		output.put("extension", jpeg ? "jpg" : "png");
		return output;
	}

	/** The first frame, or {@code null} when it cannot be read. */
	private static @Nullable BufferedImage decode(byte[] bytes, String format) {
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
			if (in == null || !readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
				if (pixels > MAX_PIXELS) {
					throw new TooLarge();
				}
				return reader.read(0);
			}
			finally {
				reader.dispose();
			}
		}
		catch (TooLarge ex) {
			throw ex;
		}
		catch (IOException | RuntimeException ex) {
			// A truncated, corrupt, or unsupported variant (for example a CMYK JPEG).
			return null;
		}
	}

	private static byte[] jpeg(BufferedImage image) {
		// JPEG has no alpha channel: transparent pixels become white.
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = rgb.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
			graphics.drawImage(image, 0, 0, null);
		}
		finally {
			graphics.dispose();
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			writer.write(null, new IIOImage(rgb, null, null), param);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static byte[] png(BufferedImage image) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			if (!ImageIO.write(image, "png", bytes)) {
				BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = argb.createGraphics();
				try {
					graphics.drawImage(image, 0, 0, null);
				}
				finally {
					graphics.dispose();
				}
				bytes.reset();
				ImageIO.write(argb, "png", bytes);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	private static ObjectNode refused(String reason) {
		return JsonNodeFactory.instance.objectNode().put("refused", reason);
	}

	/** The header says more than {@value #MAX_PIXELS} pixels. */
	private static final class TooLarge extends RuntimeException {

		TooLarge() {
			super(null, null, false, false);
		}

	}

}
