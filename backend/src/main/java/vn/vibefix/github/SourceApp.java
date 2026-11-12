package vn.vibefix.github;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import vn.vibefix.github.GitHubApps.InstallationToken;

/**
 * The Source App (AD-10, AD-13, AD-45, Story 23.5): each Customer installs it on exactly their Source Repository,
 * and every read of a Source Repository goes through it. An installation is found by its repository with the
 * App's JWT ({@code GET /repos/{owner}/{name}/installation}), so an install an organization owner approves later is
 * found too; its selection is read with a Metadata-read token from that installation, minted and revoked here, so it
 * never leaves {@code github}. Every token a caller gets is narrowed to one repository; the caller holds the rule
 * that the installation may hold it ({@code product}'s binding). Each call throws {@link GitHubException} when GitHub
 * fails, or when the App is not configured ({@code vibefix.github.source-app.id} and {@code key-file}).
 */
public interface SourceApp {

	/** What each installation must have accepted (AD-10): Contents and Metadata read, Actions write. */
	Map<String, String> PERMISSIONS = Map.of("contents", "read", "metadata", "read", "actions", "write");

	/** One selected repository: its permanent numeric ID and its full name. */
	record SelectedRepository(long id, String fullName) {
	}

	/**
	 * An installation as the App's JWT reads it.
	 * @param accountType {@code User} or {@code Organization}, the account it is installed on
	 * @param all {@code repository_selection: all}, which the selection rule refuses; its repositories are not read
	 * @param suspended suspended by its account, so it cannot be used; its repositories are not read
	 * @param permissions what the installation has accepted, for example {@code actions: write}
	 * @param repositories its selection, empty when {@code all} or suspended
	 */
	record Installation(long id, long accountId, String accountLogin, String accountType, boolean all, boolean suspended,
			Map<String, String> permissions, List<SelectedRepository> repositories) {

		public Installation {
			permissions = Map.copyOf(permissions);
			repositories = List.copyOf(repositories);
		}

		/** Whether it accepted each of {@link #PERMISSIONS}, at that level or above. */
		public boolean accepted() {
			return PERMISSIONS.entrySet().stream().allMatch(needed -> {
				String held = this.permissions.get(needed.getKey());
				return held != null && (held.equals(needed.getValue()) || ("read".equals(needed.getValue()) && "write".equals(held)));
			});
		}

	}

	/** The published {@code vibefix.yml} (AD-45) a Customer adds: its path in the repository, its text, and its blob. */
	record PublishedWorkflow(String path, String content, String blob) {
	}

	/** What "Kiểm tra" found in a Source Repository's {@code .github/workflows/vibefix.yml}. */
	enum WorkflowVerdict {

		/** A published version, by its blob with CRLF read as LF (AD-45), not past its end date. */
		MATCHES,

		/** No such file on the branch. */
		MISSING,

		/** A file that is no accepted version. */
		MISMATCH

	}

	/**
	 * A workflow check.
	 * @param lineEndings a {@link WorkflowVerdict#MISMATCH} whose file holds a carriage return, so the Customer is
	 * warned about Windows line endings
	 * @param blob the accepted blob a {@link WorkflowVerdict#MATCHES} file matched (with CRLF read as LF), so its end
	 * date can be followed (R12, Story 23.5c); none for any other verdict
	 */
	record WorkflowCheck(WorkflowVerdict verdict, boolean lineEndings, @Nullable String blob) {
	}

	/** Whether the App is configured, so its installations can be read. */
	boolean configured();

	/**
	 * The installation that reaches {@code owner/name}, with its selection. A repository renamed, or transferred to an
	 * account with the App, is still found by its old name: GitHub's one 301 or 307 is followed when its JSON
	 * {@code url} lies under the configured {@code api-url}; any other redirect is a {@link GitHubException}.
	 * @return empty when GitHub answers 404: no installation reaches it, or there is no such repository
	 */
	Optional<Installation> installationOn(String owner, String name);

	/** @return empty when GitHub does not know the installation (404) */
	Optional<Installation> installation(long installationId);

	/** The install link, {@code {git-url}/apps/{slug}/installations/new?state=…}, carrying {@code state} to the Setup URL. */
	String installUrl(String state);

	/**
	 * GitHub's plain install page, {@code {git-url}/apps/{slug}/installations/new}, with no {@code state}: CUS-4's
	 * "Cài ứng dụng vibefix trên GitHub ↗" for a registered Product (Story 23.5c), whose return the Setup URL sends to
	 * CUS-2. The slug is read once from {@code GET /app}.
	 */
	String installPage();

	/** The App's bot, {@code {slug}[bot]}: the actor of every run it dispatches. */
	String botLogin();

	/**
	 * GitHub's settings page of an installation, where its account changes the selection or uninstalls it:
	 * {@code {git-url}/settings/installations/{id}} on a user, {@code {git-url}/organizations/{login}/settings/installations/{id}}
	 * on an organization. No GitHub call.
	 */
	String settingsUrl(String accountType, String accountLogin, long installationId);

	/** A token from {@code installationId}, narrowed to {@code repositoryId} and {@code permissions}. */
	InstallationToken token(long installationId, long repositoryId, Map<String, String> permissions);

	/** Revokes the token at once. */
	void revoke(InstallationToken token);

	/** Mints a token narrowed to the one repository, runs one operation with it, and revokes it whatever happened. */
	default <T> T withToken(long installationId, long repositoryId, Map<String, String> permissions,
			Function<InstallationToken, T> operation) {
		InstallationToken token = token(installationId, repositoryId, permissions);
		try {
			return operation.apply(token);
		}
		finally {
			revoke(token);
		}
	}

	/** The published {@code vibefix.yml}, read once from the classpath copy of {@code deploy/customer/vibefix.yml}. */
	PublishedWorkflow publishedWorkflow();

	/**
	 * Reads {@code .github/workflows/vibefix.yml} on {@code branch} of {@code fullName} with a Contents-read token
	 * and checks it against the accepted set ({@code vibefix.github.exchange.accepted-blobs}).
	 */
	WorkflowCheck workflow(InstallationToken token, String fullName, String branch);

	/**
	 * The last day, Vietnam time, an accepted {@code vibefix.yml} blob may be used (AD-45, R12, Story 23.5c), as the
	 * accepted set lists it; no GitHub call.
	 * @return empty for a blob with no end date, or one the accepted set does not list for the published path
	 */
	Optional<LocalDate> endsOn(String blob);

}
