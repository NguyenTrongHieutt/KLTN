package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.SourceApp.Installation;
import vn.vibefix.github.SourceApp.SelectedRepository;
import vn.vibefix.github.SourceApp.WorkflowCheck;
import vn.vibefix.github.SourceApp.WorkflowVerdict;
import vn.vibefix.github.internal.GitHubProperties.AcceptedBlob;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;
import vn.vibefix.github.internal.GitHubProperties.Exchange;

/**
 * Story 23.5 (AD-10, AD-45): the Source App finds the installation that reaches a repository with its JWT and reads
 * its selection with a Metadata-read token revoked after the read; every other token is narrowed to one repository;
 * "Kiểm tra" compares {@code vibefix.yml} with the accepted set, CRLF read as LF, and warns about line endings on a
 * mismatch; and its links name the App's slug and the installation's account.
 */
class GitHubSourceAppTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC);

	private static final String PATH = ".github/workflows/vibefix.yml";

	@TempDir
	Path directory;

	private GitHubStub github;

	private GitHubSourceApp app;

	private String published;

	@BeforeEach
	void aConfiguredSourceApp() throws Exception {
		this.github = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		Path key = Files.writeString(this.directory.resolve("source-app-key"), TestKeys.pkcs1());
		this.published = GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW);
		GitHubProperties properties = new GitHubProperties(this.github.apiUrl(), URI.create("https://github.com"),
				"vibefix-dev", null, null, null, null,
				new Exchange(List.of(new AcceptedBlob(PATH, AcceptedWorkflows.blobSha(this.published), null)), null, null, null),
				new AppProperties(GitHubStub.SOURCE_APP, null, key));
		this.app = new GitHubSourceApp(new GitHubAppsClient(properties, CLOCK), CLOCK);
	}

	/** A Source App whose JWT names another App ID, as a call signed with another App's key would. */
	private GitHubSourceApp signedAsAnotherApp() throws Exception {
		Path key = Files.writeString(this.directory.resolve("other-app-key"), TestKeys.pkcs1());
		GitHubProperties properties = new GitHubProperties(this.github.apiUrl(), URI.create("https://github.com"),
				"vibefix-dev", null, null, null, null, null, new AppProperties(123456L, null, key));
		return new GitHubSourceApp(new GitHubAppsClient(properties, CLOCK), CLOCK);
	}

	@AfterEach
	void stop() {
		this.github.close();
		// JGit keeps pack files open in its window cache; Windows cannot delete the temporary directory
		// until they are released.
		RepositoryCache.clear();
		WindowCache.reconfigure(new WindowCacheConfig());
	}

	@Test
	void theInstallationOnARepositoryIsReadWithItsSelectionAndTheSelectionTokenIsRevoked() {
		long installation = this.github.installSource("lan-nguyen", "shop-gom", 4001, 77);
		this.github.mergeInstallations.get(installation).repository(4002, "lan-nguyen/shop-2", 77);

		Installation found = this.app.installationOn("lan-nguyen", "shop-gom").orElseThrow();

		assertThat(found.id()).isEqualTo(installation);
		assertThat(found.accountLogin()).isEqualTo("lan-nguyen");
		assertThat(found.accountType()).isEqualTo("User");
		assertThat(found.all()).isFalse();
		assertThat(found.suspended()).isFalse();
		assertThat(found.accepted()).isTrue();
		assertThat(found.repositories()).containsExactly(new SelectedRepository(4001, "lan-nguyen/shop-gom"),
				new SelectedRepository(4002, "lan-nguyen/shop-2"));
		assertThat(this.github.seen).filteredOn(request -> request.path().equals("/repos/lan-nguyen/shop-gom/installation"))
			.as("read with the Source App's own JWT")
			.isNotEmpty()
			.allSatisfy(request -> assertThat(GitHubStub.issuerOf(request.authorization())).isEqualTo(GitHubStub.SOURCE_APP));
		assertThat(this.github.revokedTokens).as("the selection read's token").containsAll(this.github.tokensOf(installation));
		assertThat(this.github.grantOf(this.github.tokensOf(installation).getFirst()).path("permissions").toString())
			.isEqualTo("{\"metadata\":\"read\"}");
		assertThat(this.app.installationOn("lan-nguyen", "nothing-here")).isEmpty();
		assertThat(this.app.installation(installation)).contains(found);
		assertThat(this.app.installation(999_999)).isEmpty();
	}

	@Test
	void aWholeAccountASuspensionOrAPermissionNotAcceptedIsReadWithoutItsSelection() {
		long installation = this.github.installSource("lan-nguyen", "shop-gom", 4001, 77);
		var installed = this.github.mergeInstallations.get(installation);
		installed.all = true;
		assertThat(this.app.installation(installation).orElseThrow()).satisfies(found -> {
			assertThat(found.all()).isTrue();
			assertThat(found.repositories()).isEmpty();
		});
		installed.all = false;
		installed.suspended = true;
		assertThat(this.app.installation(installation).orElseThrow().suspended()).isTrue();
		installed.suspended = false;
		installed.permissions.put("actions", "read");
		assertThat(this.app.installation(installation).orElseThrow().accepted()).isFalse();
		installed.permissions.put("contents", "write");
		installed.permissions.put("actions", "write");
		assertThat(this.app.installation(installation).orElseThrow().accepted()).as("write covers read").isTrue();
	}

	@Test
	void theWorkflowFileMatchesIsMissingOrDiffersWithTheLineEndingWarning() {
		long installation = this.github.installSource("lan-nguyen", "shop-gom", 4001, 77);
		InstallationToken token = this.app.token(installation, 4001, Map.of("contents", "read"));
		assertThat(this.github.grantOf(token.value()).path("repository_ids").toString()).isEqualTo("[4001]");

		assertThat(this.app.workflow(token, "lan-nguyen/shop-gom", "main"))
			.isEqualTo(new WorkflowCheck(WorkflowVerdict.MISSING, false));
		this.github.sourceFiles.put("lan-nguyen/shop-gom:" + PATH, this.published);
		assertThat(this.app.workflow(token, "lan-nguyen/shop-gom", "main"))
			.isEqualTo(new WorkflowCheck(WorkflowVerdict.MATCHES, false));
		this.github.sourceFiles.put("lan-nguyen/shop-gom:" + PATH, this.published.replace("\n", "\r\n"));
		assertThat(this.app.workflow(token, "lan-nguyen/shop-gom", "main")).as("CRLF read as LF")
			.isEqualTo(new WorkflowCheck(WorkflowVerdict.MATCHES, false));
		this.github.sourceFiles.put("lan-nguyen/shop-gom:" + PATH, this.published + "# changed\n");
		assertThat(this.app.workflow(token, "lan-nguyen/shop-gom", "main"))
			.isEqualTo(new WorkflowCheck(WorkflowVerdict.MISMATCH, false));
		this.github.sourceFiles.put("lan-nguyen/shop-gom:" + PATH, (this.published + "# changed\n").replace("\n", "\r\n"));
		assertThat(this.app.workflow(token, "lan-nguyen/shop-gom", "main"))
			.isEqualTo(new WorkflowCheck(WorkflowVerdict.MISMATCH, true));

		this.github.unavailable.add("GET /repos/lan-nguyen/shop-gom/contents/");
		assertThatThrownBy(() -> this.app.workflow(token, "lan-nguyen/shop-gom", "main")).as("a failed read proves nothing")
			.isInstanceOf(GitHubException.class);
		this.app.revoke(token);
		assertThat(this.github.revokedTokens).contains(token.value());
	}

	/**
	 * Story 23.5b: a repository renamed, or transferred to an account with the App, is found by its old name: GitHub's
	 * one 301 to {@code /repositories/{id}/installation} is followed, with the App's JWT.
	 */
	@Test
	void aMovedRepositoryIsFoundByItsOldNameThroughOneRedirect() throws Exception {
		this.github.publish("lan-nguyen", "shop-gom", 4001, 77, false, true);
		this.github.source("lan-nguyen", "shop-gom");
		long old = this.github.installSource("lan-nguyen", "shop-gom", 4001, 77);
		this.github.mergeInstallations.get(old).deselect(4001);
		this.github.rename(4001, "cong-ty", "shop-gom");
		long moved = this.github.installSource("cong-ty", "shop-gom", 4001, 88);

		Installation found = this.app.installationOn("lan-nguyen", "shop-gom").orElseThrow();

		assertThat(found.id()).isEqualTo(moved);
		assertThat(found.repositories()).containsExactly(new SelectedRepository(4001, "cong-ty/shop-gom"));
		assertThat(this.github.seen).filteredOn(request -> request.path().equals("/repositories/4001/installation"))
			.as("followed once, with the Source App's own JWT")
			.singleElement()
			.satisfies(request -> assertThat(GitHubStub.issuerOf(request.authorization())).isEqualTo(GitHubStub.SOURCE_APP));
	}

	/** Story 23.5b: a 307 is followed the same way; a second redirect, another code, or a place off the API is refused. */
	@Test
	void onlyOneRedirectUnderTheApiIsFollowed() {
		long installation = this.github.installSource("lan-nguyen", "shop-gom", 4001, 77);
		String api = this.github.apiUrl().toString();
		java.util.Map<String, String> redirects = new java.util.concurrent.ConcurrentHashMap<>();
		this.github.extra = (exchange, request, query) -> {
			String answer = redirects.get(request.path());
			if (answer == null) {
				return false;
			}
			GitHubStub.send(exchange, Integer.parseInt(answer.substring(0, 3)), answer.substring(4));
			return true;
		};
		redirects.put("/repos/lan-nguyen/old-name/installation",
				"307 {\"message\":\"Temporary Redirect\",\"url\":\"" + api + "/repos/lan-nguyen/shop-gom/installation\"}");
		assertThat(this.app.installationOn("lan-nguyen", "old-name")).map(Installation::id).contains(installation);

		redirects.put("/repos/lan-nguyen/twice/installation",
				"301 {\"message\":\"Moved Permanently\",\"url\":\"" + api + "/repos/lan-nguyen/old-name/installation\"}");
		assertThatThrownBy(() -> this.app.installationOn("lan-nguyen", "twice")).as("a second redirect")
			.isInstanceOf(GitHubException.class);

		redirects.put("/repos/lan-nguyen/found/installation",
				"302 {\"message\":\"Found\",\"url\":\"" + api + "/repos/lan-nguyen/shop-gom/installation\"}");
		assertThatThrownBy(() -> this.app.installationOn("lan-nguyen", "found")).as("another code")
			.isInstanceOf(GitHubException.class);

		for (String outside : List.of("https://evil.example/repositories/4001/installation",
				api + ".evil.example/repositories/4001/installation", api + "/repositories/../../x", "/repositories/4001/installation",
				"")) {
			redirects.put("/repos/lan-nguyen/away/installation",
					"301 {\"message\":\"Moved Permanently\",\"url\":\"" + outside + "\"}");
			int sent = this.github.seen.size();
			// Refused before any request: a failed connection would not say "outside its API".
			assertThatThrownBy(() -> this.app.installationOn("lan-nguyen", "away")).as(outside)
				.isInstanceOf(GitHubException.class)
				.hasMessageContaining("outside its API");
			assertThat(this.github.seen).as("only the redirect itself reached the stub: %s", outside).hasSize(sent + 1);
		}
	}

	@Test
	void anotherAppsJwtDoesNotReachTheSourceAppsInstallation() throws Exception {
		this.github.installSource("lan-nguyen", "shop-gom", 4001, 77);

		assertThat(signedAsAnotherApp().installationOn("lan-nguyen", "shop-gom")).isEmpty();
		assertThat(this.app.installationOn("lan-nguyen", "shop-gom")).isPresent();
	}

	@Test
	void itsLinksNameTheAppsSlugAndTheInstallationsAccount() {
		assertThat(this.app.installUrl("a b")).isEqualTo("https://github.com/apps/vibefix-dev-source/installations/new?state=a+b");
		assertThat(this.app.botLogin()).isEqualTo("vibefix-dev-source[bot]");
		assertThat(this.github.seen).filteredOn(request -> request.path().equals("/app"))
			.as("the registration is read with the Source App's own JWT")
			.isNotEmpty()
			.allSatisfy(request -> assertThat(GitHubStub.issuerOf(request.authorization())).isEqualTo(GitHubStub.SOURCE_APP));
		assertThat(this.app.settingsUrl("User", "lan-nguyen", 21)).isEqualTo("https://github.com/settings/installations/21");
		assertThat(this.app.settingsUrl("Organization", "cong-ty", 21))
			.isEqualTo("https://github.com/organizations/cong-ty/settings/installations/21");
		assertThat(this.app.publishedWorkflow().path()).isEqualTo(PATH);
		assertThat(this.app.configured()).isTrue();
	}

}
