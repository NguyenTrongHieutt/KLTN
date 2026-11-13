package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Story 23.5d (R31, AD-10): the GitHub linking token keeps identity only. It is used once in the callback and never
 * stored, so nothing live names the stored token ({@code customer_token}, {@code storeCustomerToken}, the
 * {@code UnusedTokenSweep}, {@code keepsRepositoryToken}, {@code forgetRepositoryToken}), the re-link prompt
 * ({@code TokenRenewals}, the {@code identity.relink_github} to-do and notification, ACC-2's {@code relinkNeeded}), the
 * {@code RELINK_GITHUB} reason or block, alone or inside a key such as {@code blockedRELINK_GITHUB}, or the GitHub
 * gate's {@code needsRepositoryToken} and {@code repositoryTokenKept}, in {@code backend/src/main} outside its Flyway
 * history, in {@code frontend/src} outside its tests, or in {@code deploy}. Tests may name them (a migration test seeds
 * them), and so may {@code .backups} copies.
 */
class RetiredLinkingTokenTest {

	static final Pattern RETIRED = Pattern.compile("RELINK_GITHUB|relink_github|customer_token|TokenRenewals|needsRepositoryToken"
			+ "|relinkNeeded|repositoryTokenKept|keepsRepositoryToken|forgetRepositoryToken|UnusedTokenSweep|storeCustomerToken");

	@Test
	void nothingLiveNamesTheStoredLinkingTokenOrTheReLink() {
		List<Path> files = new ArrayList<>(RetiredScanningTest.liveFiles(RetiredScanningTest.BACKEND_MAIN));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.FRONTEND_SRC));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.DEPLOY));
		files.removeIf(RetiredLinkingTokenTest::isBackup);

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "i18n", "vi.json")))
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "features", "identity", "GitHubGate.tsx")))
			.anyMatch(file -> file.endsWith(Path.of("identity", "internal", "GitHubLinksImpl.java")))
			.anyMatch(file -> file.endsWith(Path.of("deploy", "README.md")));
		assertThat(files.stream().flatMap(file -> violations(file, read(file)).stream()).toList()).isEmpty();
	}

	@Test
	void theRuleFindsEachRetiredNameAndLeavesWhatStaysAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("Block.RELINK_GITHUB", "\"blockedRELINK_GITHUB\": \"…\"", "todo('identity.relink_github')",
				"DELETE FROM identity.customer_token", "private final TokenRenewals renewals;",
				"needsRepositoryToken = false,", "data.relinkNeeded", "repositoryTokenKept: linked,",
				"default boolean keepsRepositoryToken(UUID accountId) {", "this.gitHubLinks.forgetRepositoryToken(customerId);",
				"class UnusedTokenSweep {", "this.accounts.storeCustomerToken(accountId, sealed, now);")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("identity.link_github", "GITHUB_NOT_LINKED", "identity.github_relinked", "Outcome.RELINKED",
				"OAuthGrantRevocation.TYPE", "TokenCipher.Sealed", "GitHubAccountIds")) {
			assertThat(violations(file, text)).as(text).isEmpty();
		}
		assertThat(isBackup(Path.of("frontend", "src", ".backups", "GitHubGate.tsx"))).isTrue();
		assertThat(isBackup(Path.of("frontend", "src", "features", "identity", "GitHubGate.tsx"))).isFalse();
	}

	/** A copy under a {@code .backups} folder, which no build reads. */
	static boolean isBackup(Path file) {
		for (Path part : file) {
			if (part.toString().equals(".backups")) {
				return true;
			}
		}
		return false;
	}

	private static List<String> violations(Path file, String text) {
		List<String> violations = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int number = 0; number < lines.length; number++) {
			if (RETIRED.matcher(lines[number]).find()) {
				violations.add(file + ":" + (number + 1) + ": " + lines[number].strip());
			}
		}
		return violations;
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
