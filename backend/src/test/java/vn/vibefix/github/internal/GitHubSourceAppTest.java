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
