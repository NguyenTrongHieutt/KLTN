package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
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

import vn.vibefix.github.SourceApp;
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

	/**
	 * R12 (Story 23.6c, deferred-work 650): the published {@code vibefix.yml} has no end date, and every earlier version
	 * still accepted has one, so a Product that added it gets the end-date notice; the 23.5 version, which dev Products
	 * added before the copy's fsck ignore list, ends on 2026-12-14 [ASSUMPTION]. Each (path, blob) is listed once, so no
	 * listing overwrites another's end date.
	 */
	@Test
	void everyEarlierVibefixYmlEndsAndThePublishedOneDoesNot() throws IOException {
		String published = AcceptedWorkflows.blobSha(GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		List<String> listings = new ArrayList<>();
		Map<String, Object> endsOn = new LinkedHashMap<>();
		for (Map<String, Object> blob : acceptedBlobs()) {
			listings.add(blob.get("path") + " " + blob.get("blob"));
			if (CUSTOMER_PATH.equals(blob.get("path"))) {
				endsOn.put(String.valueOf(blob.get("blob")), blob.get("ends-on"));
			}
		}

		assertThat(listings).as("one listing per path and blob").doesNotHaveDuplicates();
		assertThat(endsOn).as("the published version, with no end").containsEntry(published, null);
		assertThat(endsOn).as("the 23.5 version, from before the fsck ignore list")
			.containsEntry("db448cf8fe316ef7876e5a6b0556a554ae74c976", "2026-12-14");
		endsOn.forEach((blob, last) -> {
			if (!blob.equals(published)) {
				assertThat(last).as("%s is an earlier version, so it ends", blob).isInstanceOf(String.class);
				assertThat(LocalDate.parse(String.valueOf(last))).as("%s ends on a day", blob).isNotNull();
			}
		});
	}

	/**
	 * Story 23.5: the Source App serves the published {@code vibefix.yml} from its classpath copy, which is
	 * {@code deploy/customer/vibefix.yml} byte for byte, with the blob {@code application.yaml} accepts.
	 */
	@Test
	void theSourceAppsPublishedWorkflowIsTheDeployFileByteForByteAndItsBlobIsAccepted() throws IOException {
		byte[] classpath;
		try (InputStream in = AcceptedBlobsTest.class.getClassLoader().getResourceAsStream(GitHubSourceApp.RESOURCE)) {
			assertThat(in).as(GitHubSourceApp.RESOURCE + " is on the classpath").isNotNull();
			classpath = in.readAllBytes();
		}
		assertThat(classpath).as("deploy/customer/vibefix.yml changed: copy it to src/main/resources/"
				+ GitHubSourceApp.RESOURCE).isEqualTo(Files.readAllBytes(GitHubStub.CUSTOMER_WORKFLOW));

		List<AcceptedBlob> accepted = new ArrayList<>();
		for (Map<String, Object> blob : acceptedBlobs()) {
			accepted.add(new AcceptedBlob(String.valueOf(blob.get("path")), String.valueOf(blob.get("blob")), null));
		}
		GitHubProperties properties = new GitHubProperties(URI.create("https://api.github.com"), null, "vibefix-dev", null,
				null, null, new Exchange(accepted, null, null, null));
		Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC);
		SourceApp.PublishedWorkflow published = new GitHubSourceApp(new GitHubAppsClient(properties, clock), clock)
			.publishedWorkflow();

		assertThat(published.path()).isEqualTo(CUSTOMER_PATH);
		assertThat(published.content()).isEqualTo(GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		assertThat(published.blob()).isEqualTo(AcceptedWorkflows.blobSha(published.content()));
		assertThat(new AcceptedWorkflows(properties, clock).accepts(CUSTOMER_PATH, published.blob()))
			.as("the blob served is accepted").isTrue();
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
				null, new Exchange(accepted, null, null, null));
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
