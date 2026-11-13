package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * R4 (Story 23.6c): the N-32 version the SHA confirmation card sends, which {@code vi.json} mirrors, is the one the server
 * accepts and records, and each version names one text: its title and checkbox are pinned by digest, so a reworded N-32
 * fails here until its version is raised and the new digest pinned.
 */
class SelfConfirmationNoticeVersionTest {

	private static final Path VI = Path.of("..", "frontend", "src", "i18n", "vi.json");

	private static final Path APPLICATION = Path.of("src", "main", "resources", "application.yaml");

	/** Each N-32 version's text: the SHA-256 of its title, a line feed, and its checkbox, as {@code vi.json} holds them. */
	private static final Map<String, String> TEXTS = Map.of("1.0",
			"72fb3c756e3860d132a12f9ad08dd763543d8a4cfdba0a8a312c4de7eaec8816");

	@Test
	@SuppressWarnings("unchecked")
	void theCardsVersionIsTheOneTheServerAcceptsAndNamesItsText() throws IOException, NoSuchAlgorithmException {
		JsonNode notice = JsonMapper.builder().build().readTree(VI.toFile()).path("notice").path("selfConfirmation");
		String shown = notice.path("version").asString();
		Map<String, Object> yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(Files.readString(APPLICATION, StandardCharsets.UTF_8));
		Map<String, Object> repo = (Map<String, Object>) ((Map<String, Object>) yaml.get("vibefix")).get("repo");
		String text = notice.path("title").asString() + "\n" + notice.path("checkbox").asString();
		String digest = HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));

		assertThat(shown).matches(JobSetups.NOTICE_VERSION_PATTERN)
			.isEqualTo(String.valueOf(repo.get("self-confirmation-notice-version")));
		assertThat(TEXTS).as("N-32 %s is pinned to its text", shown).containsKey(shown);
		assertThat(digest).as("N-32's text changed: raise its version in vi.json and application.yaml, and pin the digest")
			.isEqualTo(TEXTS.get(shown));
	}

}
