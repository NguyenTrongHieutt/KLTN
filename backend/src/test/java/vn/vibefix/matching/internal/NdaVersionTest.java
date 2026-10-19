package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** FR-15: the NDA version the SPA shows is the one the server records, so a signature names its text. */
class NdaVersionTest {

	private static final Path VI = Path.of("..", "frontend", "src", "i18n", "vi.json");

	@Test
	void theShownVersionIsTheRecordedOne() {
		String shown = JsonMapper.builder().build().readTree(VI.toFile()).path("screen").path("nda").path("version").asString();

		assertThat(shown).isEqualTo(NdaSignatures.NDA_VERSION);
	}

}
