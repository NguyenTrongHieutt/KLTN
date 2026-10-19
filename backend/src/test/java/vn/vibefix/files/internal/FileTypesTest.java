package vn.vibefix.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import vn.vibefix.files.FileKind;
import vn.vibefix.support.TestFiles;

/** AD-21: the type comes from the bytes alone, and only the listed types are known. */
class FileTypesTest {

	@Test
	void rasterImagesTheSandboxCanReencodeAreImages() {
		assertThat(FileTypes.detect(TestFiles.png(4, 4))).hasValueSatisfying(type -> {
			assertThat(type.kind()).isEqualTo(FileKind.IMAGE);
			assertThat(type.imageFormat()).isEqualTo("png");
		});
		assertThat(FileTypes.detect(TestFiles.jpeg(4, 4))).hasValueSatisfying(type -> {
			assertThat(type.mediaType()).isEqualTo("image/jpeg");
			assertThat(type.imageFormat()).isEqualTo("jpeg");
		});
		assertThat(FileTypes.detect(TestFiles.gif(4, 4))).map(FileTypes.Detected::imageFormat).hasValue("gif");
		assertThat(FileTypes.detect(TestFiles.bmp(4, 4))).map(FileTypes.Detected::imageFormat).hasValue("bmp");
	}

	@Test
	void pdfTextAndArchivesAreKnown() {
		assertThat(FileTypes.detect(TestFiles.pdf())).map(FileTypes.Detected::kind).hasValue(FileKind.PDF);
		assertThat(FileTypes.detect(TestFiles.text())).map(FileTypes.Detected::kind).hasValue(FileKind.TEXT);
		assertThat(FileTypes.detect(TestFiles.zip())).map(FileTypes.Detected::kind).hasValue(FileKind.ARCHIVE);
	}

	@Test
	void markupAndUnencodableImagesAreUnknown() {
		assertThat(FileTypes.detect(TestFiles.svg())).isEmpty();
		assertThat(FileTypes.detect(TestFiles.svgWithProlog())).isEmpty();
		assertThat(FileTypes.detect(TestFiles.html())).isEmpty();
		assertThat(FileTypes.detect(TestFiles.bareHtml())).isEmpty();
		assertThat(FileTypes.detect(TestFiles.webp())).isEmpty();
		assertThat(FileTypes.detect(new byte[0])).isEmpty();
	}

}
