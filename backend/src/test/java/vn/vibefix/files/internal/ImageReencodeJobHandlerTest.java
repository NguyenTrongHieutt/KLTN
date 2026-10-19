package vn.vibefix.files.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.support.TestFiles;

/** AD-21: images come back as pixels only, and huge or broken ones are refused. */
class ImageReencodeJobHandlerTest {

	private final ImageReencodeJobHandler handler = new ImageReencodeJobHandler();

	@Test
	void aPngLosesItsTextChunk() throws IOException {
		byte[] input = TestFiles.pngWithText();
		assertThat(TestFiles.holdsMarker(input)).isTrue();

		JsonNode output = this.handler.handle(input(input, "png"));

		byte[] reencoded = content(output);
		assertThat(output.path("mediaType").asString()).isEqualTo("image/png");
		assertThat(output.path("extension").asString()).isEqualTo("png");
		assertThat(TestFiles.holdsMarker(reencoded)).isFalse();
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(reencoded));
		assertThat(image.getWidth()).isEqualTo(8);
		assertThat(image.getHeight()).isEqualTo(6);
	}

	@Test
	void aJpegLosesItsExifAndStaysAJpeg() throws IOException {
		byte[] input = TestFiles.jpegWithExif();
		assertThat(TestFiles.holdsMarker(input)).isTrue();

		JsonNode output = this.handler.handle(input(input, "jpeg"));

		byte[] reencoded = content(output);
		assertThat(output.path("mediaType").asString()).isEqualTo("image/jpeg");
		assertThat(output.path("extension").asString()).isEqualTo("jpg");
		assertThat(TestFiles.holdsMarker(reencoded)).isFalse();
		assertThat(ImageIO.read(new ByteArrayInputStream(reencoded)).getWidth()).isEqualTo(8);
	}

	@Test
	void gifAndBmpBecomePngs() {
		assertThat(this.handler.handle(input(TestFiles.gif(3, 3), "gif")).path("mediaType").asString())
			.isEqualTo("image/png");
		assertThat(this.handler.handle(input(TestFiles.bmp(3, 3), "bmp")).path("extension").asString())
			.isEqualTo("png");
	}

	@Test
	void moreThanFortyMegapixelsIsRefusedFromTheHeader() {
		// 8000 × 6000 = 48 MP; the pixel data is a single byte, so only the header can say so.
		JsonNode output = this.handler.handle(input(TestFiles.pngHeaderOnly(8000, 6000), "png"));

		assertThat(output.path("refused").asString()).isEqualTo(ImageReencodeJobHandler.IMAGE_TOO_LARGE);
		assertThat(output.has("content")).isFalse();
	}

	@Test
	void contentThatIsNotThatImageIsUnreadable() {
		assertThat(this.handler.handle(input(TestFiles.pdf(), "png")).path("refused").asString())
			.isEqualTo(ImageReencodeJobHandler.UNREADABLE);
		assertThat(this.handler.handle(input(TestFiles.pngHeaderOnly(10, 10), "png")).path("refused").asString())
			.isEqualTo(ImageReencodeJobHandler.UNREADABLE);
	}

	@Test
	void invalidInputIsRefused() {
		assertThatThrownBy(() -> this.handler.handle(JsonNodeFactory.instance.objectNode()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.handler.handle(input(TestFiles.png(2, 2), "svg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.handler
			.handle(JsonNodeFactory.instance.objectNode().put("content", "not base64!").put("format", "png")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static JsonNode input(byte[] content, String format) {
		return JsonNodeFactory.instance.objectNode()
			.put("content", Base64.getEncoder().encodeToString(content))
			.put("format", format);
	}

	private static byte[] content(JsonNode output) {
		return Base64.getDecoder().decode(output.path("content").asString());
	}

}
