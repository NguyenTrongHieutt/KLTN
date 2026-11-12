package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import vn.vibefix.github.internal.AcceptedWorkflows.Verdict;
import vn.vibefix.github.internal.GitHubProperties.AcceptedBlob;
import vn.vibefix.github.internal.GitHubProperties.Exchange;

/**
 * Story 23.4 (AD-45, deferred-work 638): {@code application.yaml} accepts the published workflow files as they
 * are now, each blob computed with CRLF read as LF; a file is accepted by GitHub's blob or by its content's, until
 * its end date (Vietnam time); and the exchange's properties refuse a tunnel that is no https origin.
 */
class AcceptedBlobsTest {

	private static final Path APPLICATION = Path.of("src", "main", "resources", "application.yaml");

	private static final String CUSTOMER_PATH = ".github/workflows/vibefix.yml";

	private static final String RUNNER_PATH = ".github/workflows/s6-copy.yml";

	@Test
	void applicationYamlAcceptsThePublishedFilesAsTheyAreNow() throws IOException {
		Map<String, String> accepted = new LinkedHashMap<>();
		for (Map<String, Object> blob : acceptedBlobs()) {
			accepted.put(String.valueOf(blob.get("blob")), String.valueOf(blob.get("path")));
		}
		String customer = AcceptedWorkflows.blobSha(GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		String runner = AcceptedWorkflows.blobSha(GitHubStub.published(GitHubStub.RUNNER_WORKFLOW));

		assertThat(accepted).as("deploy/customer/vibefix.yml changed: list its new blob").containsEntry(customer, CUSTOMER_PATH);
		assertThat(accepted).as("deploy/runner/s6-copy.yml changed: list its new blob").containsEntry(runner, RUNNER_PATH);
		assertThat(AcceptedWorkflows.blobSha(Files.readString(GitHubStub.RUNNER_WORKFLOW, StandardCharsets.UTF_8)
			.replace("\n", "\r\n"))).as("a CRLF copy is the same blob").isEqualTo(runner);
	}

	@Test
	void theBlobIsGitHashObjectWithCrlfReadAsLf() {
		assertThat(AcceptedWorkflows.blobSha("hello\n")).isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
		assertThat(AcceptedWorkflows.blobSha("hello\r\n")).isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
		assertThat(AcceptedWorkflows.blobSha("name: x\n")).isEqualTo(OidcExchange.blobSha("name: x\r\n"));
	}

	@Test
	void aFileIsAcceptedByItsBlobOrItsLfContentUntilItsEndDate() {
		String published = "name: vibefix\non:\n  workflow_dispatch:\n";
		String blob = AcceptedWorkflows.blobSha(published);
		AcceptedWorkflows workflows = workflows(List.of(new AcceptedBlob(CUSTOMER_PATH, blob, LocalDate.parse("2026-11-30"))),
				"2026-11-30T16:59:00Z");

		assertThat(workflows.check(CUSTOMER_PATH, contents(blob, published)).verdict()).isEqualTo(Verdict.ACCEPTED);
		assertThat(workflows.check(CUSTOMER_PATH, contents("ab".repeat(20), published.replace("\n", "\r\n"))).verdict())
			.as("committed with CRLF, read as LF").isEqualTo(Verdict.ACCEPTED);
		assertThat(workflows.check(RUNNER_PATH, contents(blob, published)).verdict()).as("another path")
			.isEqualTo(Verdict.MISMATCH);
		assertThat(workflows.check(CUSTOMER_PATH, contents("cd".repeat(20), published + "# tampered\n")))
			.satisfies(checked -> {
				assertThat(checked.verdict()).isEqualTo(Verdict.MISMATCH);
				assertThat(checked.blob()).isEqualTo(AcceptedWorkflows.blobSha(published + "# tampered\n"));
			});
		assertThat(workflows.check(CUSTOMER_PATH, new GitHubHttp.Answer(404, GitHubHttp.JSON.createObjectNode())).verdict())
			.isEqualTo(Verdict.MISSING);
		for (int failed : new int[] { 0, 403, 502 }) {
			assertThatThrownBy(() -> workflows.check(CUSTOMER_PATH, new GitHubHttp.Answer(failed, GitHubHttp.JSON.createObjectNode())))
				.as("a read that failed is no missing file: HTTP %d", failed)
				.isInstanceOfSatisfying(vn.vibefix.github.GitHubException.class,
						refused -> assertThat(refused.status()).isEqualTo(failed));
		}
		assertThat(workflows(List.of(new AcceptedBlob(CUSTOMER_PATH, blob, LocalDate.parse("2026-11-30"))),
				"2026-11-30T17:00:00Z").check(CUSTOMER_PATH, contents(blob, published)).verdict())
			.as("past its last day, Vietnam time").isEqualTo(Verdict.MISMATCH);
	}

	@Test
	void theTunnelIsAnHttpsOriginAndTheIssuerHttpsOrLoopback() {
		assertThat(new Exchange(null, "https://words-here.trycloudflare.com/", null, null).publicUrl())
			.isEqualTo("https://words-here.trycloudflare.com");
		assertThat(new Exchange(null, "  ", null, null).publicUrl()).isNull();
		assertThat(Exchange.NONE.devPort()).isEqualTo(8098);
		assertThat(Exchange.NONE.issuer()).isEqualTo("https://token.actions.githubusercontent.com");
		assertThat(new Exchange(null, null, null, " https://token.actions.githubusercontent.com// ").issuer())
			.as("as a token's iss names it, which EnvironmentGuard's trailing slash would otherwise refuse")
			.isEqualTo("https://token.actions.githubusercontent.com");
		assertThat(Exchange.NONE.jwks()).isEqualTo(URI.create("https://token.actions.githubusercontent.com/.well-known/jwks"));
		for (String refused : List.of("http://words.trycloudflare.com", "https://words.trycloudflare.com/exchange",
				"https://user@words.trycloudflare.com", "https://words.trycloudflare.com?x=1", "words.trycloudflare.com")) {
			assertThatThrownBy(() -> new Exchange(null, refused, null, null)).as(refused)
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(new Exchange(null, null, null, "http://127.0.0.1:4000").jwks())
			.isEqualTo(URI.create("http://127.0.0.1:4000/.well-known/jwks"));
		assertThatThrownBy(() -> new Exchange(null, null, null, "http://issuer.example.test"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AcceptedBlob("vibefix.yml", "ab".repeat(20), null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AcceptedBlob(CUSTOMER_PATH, "not-a-blob", null)).isInstanceOf(IllegalArgumentException.class);
	}

	private static AcceptedWorkflows workflows(List<AcceptedBlob> accepted, String now) {
		GitHubProperties properties = new GitHubProperties(URI.create("https://api.github.com"), null, "vibefix-dev", null, null,
				null, null, new Exchange(accepted, null, null, null));
		return new AcceptedWorkflows(properties, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
	}

	/** The contents API's answer for a file: GitHub's blob of the committed bytes, and their base64. */
	private static GitHubHttp.Answer contents(String sha, String content) {
		return new GitHubHttp.Answer(200, GitHubHttp.JSON.valueToTree(Map.of("type", "file", "sha", sha, "encoding", "base64",
				"content", Base64.getMimeEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)))));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> acceptedBlobs() throws IOException {
		Map<String, Object> yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
			.load(Files.readString(APPLICATION, StandardCharsets.UTF_8));
		Object node = yaml;
		for (String key : List.of("vibefix", "github", "exchange", "accepted-blobs")) {
			node = ((Map<String, Object>) node).get(key);
		}
		return new ArrayList<>((List<Map<String, Object>>) node);
	}

}
