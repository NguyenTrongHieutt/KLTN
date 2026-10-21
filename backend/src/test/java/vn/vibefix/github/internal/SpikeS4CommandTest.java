package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/** Story 2.1, S4: the probe's checks against a stand-in GitHub, with no token ever printed. */
class SpikeS4CommandTest {

	@TempDir
	Path directory;

	private GitHubStub github;

	private GitHubAppsClient apps;

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@BeforeEach
	void aStubGitHubWithASourceRepository() throws Exception {
		this.github = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		this.github.source("octo", "quan-may");
		Path key = Files.writeString(this.directory.resolve("github-platform-app-key"), TestKeys.pkcs1());
		this.apps = new GitHubAppsClient(new GitHubProperties(this.github.apiUrl(), this.github.gitUrl(), GitHubStub.ORGANIZATION,
				new AppProperties(123456L, GitHubStub.INSTALLATION, key), null),
				Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC));
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
	void everyCheckRunsWithItsOwnNarrowedTokenAndNoTokenIsPrinted() throws Exception {
		int exit = run("dev,cli", "--source=octo/quan-may", "--collaborator=minhtq", "--forks=2", "--prefix=s4-test");

		String output = this.out.toString(StandardCharsets.UTF_8);
		assertThat(exit).as(output + this.err.toString(StandardCharsets.UTF_8)).isZero();
		assertThat(output).contains("organization settings", "two_factor_requirement_enabled=true",
				"default_repository_permission=none", "repository_selection=selected", "vibefix-dev/s4-test-intermediary",
				"workflow files=1",
				"s4-default-branch[deletion, non_fast_forward, update] bypass=app, hidden from a read-only token",
				"s4-handover[creation, update, deletion] bypass=app, hidden from a read-only token",
				"vibefix-dev/s4-test-working-1 ready",
				"vibefix-dev/s4-test-working-2 ready", "invitations for minhtq=1 permissions=read",
				"Spike S4: every check passed or was skipped");
		assertThat(output.lines().filter(line -> line.contains(" FAIL "))).isEmpty();
		assertThat(output).contains("member refused by the ruleset", "SKIPPED");
		for (String token : this.github.tokens) {
			assertThat(output).doesNotContain(token);
			assertThat(this.err.toString(StandardCharsets.UTF_8)).doesNotContain(token);
		}
		assertThat(this.github.revoked).as("every token is revoked after its check").hasValue(this.github.tokens.size());

		long intermediary = this.github.repositoryId("s4-test-intermediary");
		assertThat(this.github.seen.stream()
			.filter(request -> request.path().endsWith("/access_tokens"))
			.filter(request -> request.body().path("permissions").has("workflows"))
			.map(request -> request.body().path("repository_ids").toString())).containsExactly("[" + intermediary + "]");
		assertThat(this.github.hasFile(GitHubStub.ORGANIZATION, "s4-test-intermediary", "main", ".github/workflows/ci.yml"))
			.isTrue();
		try (Repository repository = this.github.repository(GitHubStub.ORGANIZATION, "s4-test-intermediary")) {
			assertThat(repository.exactRef("refs/heads/handover/s4")).isNotNull();
		}
	}

	@Test
	void theMemberRunCountsRefusalsOnlyAfterAControlPushIsAccepted() throws Exception {
		assertThat(run("dev,cli", "--source=octo/quan-may", "--collaborator=minhtq", "--forks=1", "--prefix=s4-test")).isZero();
		Path token = Files.writeString(this.directory.resolve("s4-member-token"), "github_pat_stub_member\n");
		this.out.reset();

		// The stand-in enforces no ruleset, so the control push and every guarded push go through.
		int exit = run("dev,cli", "--intermediary=s4-test-intermediary", "--member-token-file=" + token);

		String output = this.out.toString(StandardCharsets.UTF_8);
		assertThat(exit).as(output).isOne();
		assertThat(output).contains("control push=accepted, direct push=ACCEPTED, force push=ACCEPTED, deletion=ACCEPTED,"
				+ " handover push=ACCEPTED");
		assertThat(output).doesNotContain("github_pat_stub_member");
		try (Repository repository = this.github.repository(GitHubStub.ORGANIZATION, "s4-test-intermediary")) {
			assertThat(repository.exactRef("refs/heads/s4-member-control")).isNotNull();
		}
	}

	@Test
	void aRefusedControlPushMeansTheRefusalsProveNothing() throws Exception {
		assertThat(run("dev,cli", "--source=octo/quan-may", "--collaborator=minhtq", "--forks=1", "--prefix=s4-test")).isZero();
		try (Repository repository = this.github.repository(GitHubStub.ORGANIZATION, "s4-test-intermediary");
				ObjectInserter inserter = repository.newObjectInserter()) {
			// An unrelated commit already on the control branch makes the member's push a non-fast-forward.
			CommitBuilder unrelated = new CommitBuilder();
			unrelated.setTreeId(inserter.insert(new TreeFormatter()));
			PersonIdent someone = new PersonIdent("Lan", "lan@example.test");
			unrelated.setAuthor(someone);
			unrelated.setCommitter(someone);
			unrelated.setMessage("Unrelated");
			ObjectId id = inserter.insert(unrelated);
			inserter.flush();
			RefUpdate update = repository.updateRef("refs/heads/s4-member-control");
			update.setNewObjectId(id);
			assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW);
		}
		Path token = Files.writeString(this.directory.resolve("s4-member-token"), "github_pat_stub_member\n");
		this.out.reset();

		int exit = run("dev,cli", "--intermediary=s4-test-intermediary", "--member-token-file=" + token);

		String output = this.out.toString(StandardCharsets.UTF_8);
		assertThat(exit).as(output).isOne();
		assertThat(output).contains("control push=REFUSED", "the token cannot write here, so its refusals prove nothing");
	}

	@Test
	void anAcceptedCollaboratorIsRaisedToWriteWithNoNewInvitation() throws Exception {
		assertThat(run("dev,cli", "--source=octo/quan-may", "--collaborator=minhtq", "--forks=1", "--prefix=s4-test")).isZero();
		this.out.reset();

		assertThat(run("dev,cli", "--intermediary=s4-test-intermediary", "--collaborator=minhtq")).isOne();
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("raise after acceptance", "FAIL",
				"minhtq has not accepted the invitation yet");

		this.github.accept("minhtq");
		this.out.reset();
		int exit = run("dev,cli", "--intermediary=s4-test-intermediary", "--collaborator=minhtq");

		String output = this.out.toString(StandardCharsets.UTF_8);
		assertThat(exit).as(output).isZero();
		assertThat(output).contains("raise after acceptance", "raise HTTP 204", "invitations for minhtq=0",
				"read back with a read-only token: permission=write role_name=write");
		long intermediary = this.github.repositoryId("s4-test-intermediary");
		assertThat(this.github.seen.stream()
			.filter(request -> request.path().endsWith("/access_tokens"))
			.filter(request -> request.body().path("permissions").has("administration"))
			.map(request -> request.body().path("repository_ids").toString())).last().isEqualTo("[" + intermediary + "]");
	}

	@Test
	void itRefusesProductionAndAWrongInvocation() throws Exception {
		assertThat(run("prod,cli", "--source=octo/quan-may", "--collaborator=minhtq")).isOne();
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("dev stack only");
		assertThat(this.github.seen).isEmpty();

		assertThat(run("dev,cli", "--source=not a repository", "--collaborator=minhtq")).isEqualTo(2);
		assertThat(run("dev,cli", "--intermediary=s4-x-intermediary")).as("the member run needs its token file").isEqualTo(2);
	}

	private int run(String profiles, String... args) throws Exception {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles.split(","));
		environment.setProperty("vibefix.cli.command", SpikeS4Command.COMMAND);
		SpikeS4Command command = new SpikeS4Command(this.apps, environment,
				Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC), Duration.ofMillis(10),
				Duration.ofSeconds(2), new PrintStream(this.out, true, StandardCharsets.UTF_8),
				new PrintStream(this.err, true, StandardCharsets.UTF_8));
		command.run(new DefaultApplicationArguments(args));
		return command.getExitCode();
	}

}
