package vn.vibefix.delivery.internal;

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
 * R28 (FR-25, Story 23.8b): the N-33 version the handover form sends, which {@code vi.json} mirrors, is the one
 * the server accepts and records with the handover, and each version names one text: its title and its Mentor
 * checkbox are pinned by digest, so a reworded N-33 fails here until its version is raised and the new digest
 * pinned. {@code SelfConfirmationNoticeVersionTest} does the same for N-32.
 */
class TeamCodeNoticeVersionTest {

	private static final Path VI = Path.of("..", "frontend", "src", "i18n", "vi.json");

	private static final Path APPLICATION = Path.of("src", "main", "resources", "application.yaml");

	/** The authorization matrix's "Gửi bàn giao" body, which names N-33's version literally. */
	private static final Path HANDOVER_BODY = Path.of("src", "test", "resources", "authz", "bodies", "handover.json");

	/** Each N-33 version's text: the SHA-256 of its title, a line feed, and its checkbox, as {@code vi.json} holds them. */
	private static final Map<String, String> TEXTS = Map.of("1.0",
			"e60f3f273b80b76ba8c18d1bfbc424227a00c240f810c8fcdafe94dbd8912160");

	@Test
	@SuppressWarnings("unchecked")
	void theFormsVersionIsTheOneTheServerAcceptsAndNamesItsText() throws IOException, NoSuchAlgorithmException {
		JsonNode notice = JsonMapper.builder().build().readTree(VI.toFile()).path("notice").path("teamCode");
		String shown = notice.path("version").asString();
		Map<String, Object> yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(Files.readString(APPLICATION, StandardCharsets.UTF_8));
		Map<String, Object> delivery = (Map<String, Object>) ((Map<String, Object>) yaml.get("vibefix")).get("delivery");
		String text = notice.path("title").asString() + "\n" + notice.path("checkbox").asString();
		String digest = HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));

		assertThat(shown).matches(Handovers.NOTICE_VERSION_PATTERN)
			.isEqualTo(String.valueOf(delivery.get("team-code-notice-version")));
		assertThat(TEXTS).as("N-33 %s is pinned to its text", shown).containsKey(shown);
		assertThat(digest).as("N-33's text changed: raise its version in vi.json and application.yaml, and pin the digest")
			.isEqualTo(TEXTS.get(shown));
	}

	/** The Customer's N-33 line, which is shown and never acknowledged, names the Mentor it quotes. */
	@Test
	void theCustomersLineNamesTheMentor() throws IOException {
		JsonNode notice = JsonMapper.builder().build().readTree(VI.toFile()).path("notice").path("teamCode");

		assertThat(notice.path("customer").asString()).contains("{mentor}", "vibefix không quét mã của nhóm");
	}

	/**
	 * The authorization matrix's "Gửi bàn giao" body names the version literally, so without this a raised N-33
	 * would break {@code AuthorizationMatrixIT} with a failure pointing nowhere near its cause.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void theAuthorizationMatrixsBodyNamesTheSameVersion() throws IOException {
		Map<String, Object> yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(Files.readString(APPLICATION, StandardCharsets.UTF_8));
		Map<String, Object> delivery = (Map<String, Object>) ((Map<String, Object>) yaml.get("vibefix")).get("delivery");
		JsonNode body = JsonMapper.builder().build().readTree(HANDOVER_BODY.toFile());

		assertThat(body.path("teamCodeNoticeVersion").asString())
			.as("authz/bodies/handover.json names N-33's version: raise it with application.yaml and vi.json")
			.isEqualTo(String.valueOf(delivery.get("team-code-notice-version")));
	}

}
