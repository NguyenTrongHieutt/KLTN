package vn.vibefix.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/**
 * Upload contents for tests (AD-21): real images, built in memory, with metadata spliced in where a
 * test proves it is stripped, and the shapes of the types that must be refused.
 */
public final class TestFiles {

	/** A marker the metadata of {@link #pngWithText} and {@link #jpegWithExif} carries. */
	public static final String METADATA_MARKER = "canary-gps-21.0285-105.8542";

	private TestFiles() {
	}

	public static byte[] png(int width, int height) {
		return encode(image(width, height), "png");
	}

	public static byte[] jpeg(int width, int height) {
		return encode(image(width, height), "jpeg");
	}

	public static byte[] gif(int width, int height) {
		return encode(image(width, height), "gif");
	}

	public static byte[] bmp(int width, int height) {
		return encode(image(width, height), "bmp");
	}

	/** A PNG with a {@code tEXt} chunk holding {@link #METADATA_MARKER}, right after its header. */
	public static byte[] pngWithText() {
		byte[] png = png(8, 6);
		byte[] text = ("Comment\0" + METADATA_MARKER).getBytes(StandardCharsets.ISO_8859_1);
		// The signature (8 bytes) and the IHDR chunk (4 + 4 + 13 + 4 bytes) come first.
		int afterHeader = 8 + 25;
		return concat(slice(png, 0, afterHeader), chunk("tEXt", text), slice(png, afterHeader, png.length));
	}

	/** A JPEG with an EXIF segment holding {@link #METADATA_MARKER}, right after its start marker. */
	public static byte[] jpegWithExif() {
		byte[] jpeg = jpeg(8, 6);
		byte[] payload = concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1),
				METADATA_MARKER.getBytes(StandardCharsets.ISO_8859_1));
		int length = payload.length + 2;
		byte[] segment = concat(new byte[] { (byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length }, payload);
		return concat(slice(jpeg, 0, 2), segment, slice(jpeg, 2, jpeg.length));
	}

	/** A valid PNG whose header claims {@code width} × {@code height}, with almost no pixel data. */
	public static byte[] pngHeaderOnly(int width, int height) {
		ByteBuffer header = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
			.put((byte) 0).put((byte) 0).put((byte) 0);
		Deflater deflater = new Deflater();
		deflater.setInput(new byte[] { 0 });
		deflater.finish();
		byte[] compressed = new byte[64];
		int size = deflater.deflate(compressed);
		deflater.end();
		return concat(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' }, chunk("IHDR", header.array()),
				chunk("IDAT", slice(compressed, 0, size)), chunk("IEND", new byte[0]));
	}

	public static byte[] pdf() {
		return """
				%PDF-1.4
				1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
				2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj
				trailer << /Root 1 0 R >>
				%%EOF
				""".getBytes(StandardCharsets.US_ASCII);
	}

	public static byte[] text() {
		return "Thẻ sinh viên số 20201234\nTrường Đại học Thử nghiệm\n".getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] svg() {
		return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><script>alert(1)</script></svg>"
			.getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] svgWithProlog() {
		return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + new String(svg(), StandardCharsets.UTF_8))
			.getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] html() {
		return "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);
	}

	/** Markup without a doctype, which a plain-text check alone might take for text. */
	public static byte[] bareHtml() {
		return "<html><body onload=\"alert(1)\">hi</body></html>".getBytes(StandardCharsets.UTF_8);
	}

	/** The RIFF/WEBP header of a WebP image, which the JDK cannot re-encode. */
	public static byte[] webp() {
		return concat("RIFF".getBytes(StandardCharsets.US_ASCII), new byte[] { 26, 0, 0, 0 },
				"WEBPVP8 ".getBytes(StandardCharsets.US_ASCII), new byte[18]);
	}

	public static byte[] zip() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("readme.txt"));
			zip.write("hello".getBytes(StandardCharsets.US_ASCII));
			zip.closeEntry();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	/** Whether {@code content} holds {@link #METADATA_MARKER} anywhere. */
	public static boolean holdsMarker(byte[] content) {
		return new String(content, StandardCharsets.ISO_8859_1).contains(METADATA_MARKER);
	}

	private static BufferedImage image(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(new Color(0x2F6FED));
			graphics.fillRect(0, 0, width, height);
		}
		finally {
			graphics.dispose();
		}
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			if (!ImageIO.write(image, format, bytes)) {
				throw new IllegalStateException("No ImageIO writer for " + format);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	private static byte[] chunk(String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		return ByteBuffer.allocate(12 + data.length)
			.putInt(data.length)
			.put(typeBytes)
			.put(data)
			.putInt((int) crc.getValue())
			.array();
	}

	private static byte[] slice(byte[] bytes, int from, int to) {
		byte[] part = new byte[to - from];
		System.arraycopy(bytes, from, part, 0, part.length);
		return part;
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			bytes.writeBytes(part);
		}
		return bytes.toByteArray();
	}

}
