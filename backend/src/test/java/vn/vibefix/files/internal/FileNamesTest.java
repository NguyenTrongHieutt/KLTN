package vn.vibefix.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** AD-21: stored names are cleaned, and served per RFC 6266. */
class FileNamesTest {

	@Test
	void keepsOnlyTheLastPathPartWithTheStoredExtension() {
		assertThat(FileNames.normalize("C:\\Users\\lan\\Ảnh thẻ.HEIC", "jpg")).isEqualTo("Ảnh thẻ.jpg");
		assertThat(FileNames.normalize("../../etc/passwd", "txt")).isEqualTo("passwd.txt");
		assertThat(FileNames.normalize("the.sinh.vien.png", "png")).isEqualTo("the.sinh.vien.png");
	}

	@Test
	void removesControlCharactersAndCollapsesWhitespace() {
		assertThat(FileNames.normalize("thẻ\u0000\u202E  sinh\tviên.pdf", "pdf")).isEqualTo("thẻ sinh viên.pdf");
	}

	@Test
	void composesToNfc() {
		String decomposed = "The\u0309.png";
		assertThat(FileNames.normalize(decomposed, "png")).isEqualTo("Thẻ.png");
	}

	@Test
	void anEmptyNameFallsBack() {
		assertThat(FileNames.normalize("", "pdf")).isEqualTo("tep.pdf");
		assertThat(FileNames.normalize("...", "pdf")).isEqualTo("tep.pdf");
		// A dot file has no extension: its whole name is the base.
		assertThat(FileNames.normalize(".png", "png")).isEqualTo("png.png");
	}

	@Test
	void longNamesAreCutToTheLimit() {
		String name = FileNames.normalize("ả".repeat(300) + ".png", "png");

		assertThat(name.codePointCount(0, name.length())).isEqualTo(FileNames.MAX_LENGTH);
		assertThat(name).endsWith(".png");
	}

	@Test
	void contentDispositionCarriesAnAsciiNameAndTheUtf8Name() {
		assertThat(FileNames.contentDisposition("Thẻ \"đỏ\".png")).isEqualTo(
				"attachment; filename=\"The _do_.png\"; filename*=UTF-8''Th%E1%BA%BB%20%22%C4%91%E1%BB%8F%22.png");
	}

}
