package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** FR-85: the version of notice N-34 the SPA shows is the one J11 requires and the Job keeps. */
class SelfMentoredNoticeVersionTest {

	private static final Path VI = Path.of("..", "frontend", "src", "i18n", "vi.json");

	@Test
	void theShownVersionIsTheRecordedOne() {
		String shown = JsonMapper.builder().build().readTree(VI.toFile()).path("screen").path("job").path("form")
			.path("selfMentored").path("noticeVersion").asString();

		assertThat(shown).isEqualTo(J11PublishSelfMentored.NOTICE_VERSION);
	}

}
