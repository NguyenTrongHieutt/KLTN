package vn.vibefix.github;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * GitHub Actions runs that get their token from vibefix's OIDC exchange (AD-44, AD-45, AD-46). Before each
 * dispatch, the target's workflow file on its default branch must be an accepted blob
 * ({@code vibefix.github.exchange.accepted-blobs}); then {@code github} records an exchange correlation, a
 * random UUID that goes into the run's title through the {@code correlation} input, and dispatches the run.
 * The run trades its ID token for one token narrowed to the correlation's grant, with exactly its
 * operation's permissions, at {@code POST /github/actions/exchange}, and that token is revoked when the run
 * completes, or {@link #RUN_TIMEOUT} after it was issued. Nothing here holds a token or a JWT.
 * <p>
 * Two Apps dispatch runs. The Platform App dispatches the runner's handover copy. The Source App dispatches the
 * published {@code vibefix.yml} in a Customer's Source Repository, through that Customer's own installation (Story
 * 23.6a): the target and its workflow file are read, the run is dispatched, found, read back by the exchange and
 * polled with tokens from that installation, each narrowed to the Source Repository and revoked after use. Whatever
 * dispatched the run, the token it gets is minted by the Platform App, since every grant names platform repositories
 * (the Intermediary, the Working Repository, the runner): no Source App token ever reaches a platform repository, and
 * no Platform token a Customer's.
 * @throws GitHubException from {@link #dispatch} and {@link #run} when GitHub refuses or cannot be reached
 */
public interface ActionsRuns {

	/** AD-46: how long a dispatched run may take to show under its correlation. */
	Duration FIND_WINDOW = Duration.ofMinutes(2);

	/** AD-46: a run's own timeout; its token is revoked this long after issue at the latest. */
	Duration RUN_TIMEOUT = Duration.ofMinutes(15);

	/** What a run does with its token, and exactly the permissions that token gets (AD-44). */
	enum Operation {

		/** J7: the Customer's head copied to the new Intermediary and Working Repositories. */
		COPY("copy", Map.of("contents", "write", "workflows", "write")),

		/** The Final Merge brought back from the Intermediary Repository: it reads only. */
		FINAL_MERGE("final-merge", Map.of("contents", "read")),

		/** A Handover Commit copied from Working to {@code handover/{id}} of the Intermediary, on the runner. */
		HANDOVER_COPY("handover-copy", Map.of("contents", "write", "workflows", "write"));

		private final String code;

		private final Map<String, String> permissions;

		Operation(String code, Map<String, String> permissions) {
			this.code = code;
			this.permissions = Map.copyOf(permissions);
		}

		/** The code the table and the audit keep, for example {@code handover-copy}. */
		public String code() {
			return this.code;
		}

		/** The permissions the exchanged token gets, and no other. */
		public Map<String, String> permissions() {
			return this.permissions;
		}

		public static Operation of(String code) {
			for (Operation operation : values()) {
				if (operation.code.equals(code)) {
					return operation;
				}
			}
			throw new IllegalArgumentException("No operation " + code);
		}

	}

	/** Where a correlation stands. */
	enum Status {

		/** Recorded before its dispatch: its run may exchange its ID token once, until the expiry. */
		OPEN,

		/** Its run holds a token, which lives only in memory until it is revoked. */
		EXCHANGED,

		/** A check after verification refused the run's ID token, with its reason. */
		REFUSED,

		/** The token was revoked once the run completed, or {@link #RUN_TIMEOUT} after issue. */
		REVOKED,

		/**
		 * vibefix restarted while the token was out, so nothing can revoke it any more: GitHub expires it
		 * within the hour.
		 */
		REVOKE_UNKNOWN,

		/** No token was ever issued, and the expiry passed. */
		EXPIRED

	}

	/** Why nothing was dispatched: the target's workflow file on its default branch is not an accepted blob. */
	enum WorkflowRefusal {

		/** The file is not on the default branch. */
		WORKFLOW_MISSING,

		/** The file's blob is not in the accepted set, or is past its end date. */
		WORKFLOW_MISMATCH

	}

	/** The target's workflow file refused before any dispatch (deferred-work 638). */
	final class WorkflowRefused extends GitHubException {

		private final WorkflowRefusal refusal;

		public WorkflowRefused(WorkflowRefusal refusal, String message) {
			super(0, message);
			this.refusal = refusal;
		}

		public WorkflowRefusal refusal() {
			return this.refusal;
		}

	}

	/**
	 * One run to dispatch.
	 * @param app the App that dispatches it, whose tokens read the target and find and read back the run:
	 * {@link GitHubApps.App#PLATFORM} for the runner, {@link GitHubApps.App#SOURCE} for a Customer's Source
	 * Repository; never the Merge App
	 * @param sourceInstallationId for {@link GitHubApps.App#SOURCE} only, and then required: the Customer's installation,
	 * as {@code product}'s binding names it (Story 23.6a)
	 * @param targetId the numeric ID of the repository the workflow runs in, such as the runner
	 * @param workflow the workflow file in its {@code .github/workflows/}, for example {@code s6-copy.yml}
	 * @param inputs the workflow's inputs; the dispatch adds {@code correlation} itself (and, on the dev stack,
	 * {@code exchange_url})
	 * @param grant the repositories the exchanged token is narrowed to; never the target itself, whose contents and
	 * workflow file the token would then write (for {@link GitHubApps.App#SOURCE}, the Customer's own repository)
	 * @param confirmedHeadSha for {@code copy}, and required there: the head the Customer confirmed (Story 23.6), so a
	 * run at another head is refused {@code sha}; none for any other operation
	 */
	record Dispatch(GitHubApps.App app, @Nullable Long sourceInstallationId, Operation operation, long targetId,
			String workflow, Map<String, String> inputs, Set<Long> grant, @Nullable String confirmedHeadSha) {

		private static final Pattern WORKFLOW = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.ya?ml");

		private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

		public Dispatch {
			Objects.requireNonNull(app, "app");
			if (app == GitHubApps.App.MERGE) {
				throw new IllegalArgumentException("Only the Platform App and the Source App dispatch a run");
			}
			if ((app == GitHubApps.App.SOURCE) != (sourceInstallationId != null)
					|| (sourceInstallationId != null && sourceInstallationId <= 0)) {
				throw new IllegalArgumentException("A run the Source App dispatches names its installation, and only such a run");
			}
			Objects.requireNonNull(operation, "operation");
			if (targetId <= 0) {
				throw new IllegalArgumentException("A target is a repository ID");
			}
			if (workflow == null || !WORKFLOW.matcher(workflow).matches()) {
				throw new IllegalArgumentException("A workflow is a file name in .github/workflows/");
			}
			inputs = Map.copyOf(inputs);
			if (inputs.containsKey("correlation") || inputs.containsKey("exchange_url")) {
				throw new IllegalArgumentException("The dispatch sets correlation and exchange_url itself");
			}
			grant = Set.copyOf(grant);
			if (grant.isEmpty() || grant.size() > GitHubApps.MAX_REPOSITORIES || grant.stream().anyMatch(id -> id <= 0)) {
				throw new IllegalArgumentException("A grant names 1 to " + GitHubApps.MAX_REPOSITORIES + " repository IDs");
			}
			if (grant.contains(targetId)) {
				// Never the repository the run is in: for the Source App, the Customer's own.
				throw new IllegalArgumentException("A grant never names the repository the run is in");
			}
			if ((operation == Operation.COPY) != (confirmedHeadSha != null)
					|| (confirmedHeadSha != null && !SHA.matcher(confirmedHeadSha).matches())) {
				throw new IllegalArgumentException("A copy names the 40-hex head the Customer confirmed, and only a copy");
			}
		}

		/** The workflow's path, as a run reports it. */
		public String workflowPath() {
			return ".github/workflows/" + this.workflow;
		}

	}

	/**
	 * A dispatched run.
	 * @param runId the run GitHub gave it, or 0 when none showed under the correlation within {@link #FIND_WINDOW}
	 */
	record Dispatched(UUID correlation, long runId) {
	}

	/**
	 * One exchange correlation as {@code github} keeps it: internal IDs, codes and times, never a token.
	 * @param repository the target's {@code owner/name}
	 * @param refusal the reason the run's ID token was refused, for {@link Status#REFUSED}
	 * @param runId the run, once known
	 * @param app the App that dispatched the run
	 * @param runHtmlUrl the run's page on GitHub, once it is found
	 * @param runConclusion GitHub's conclusion of the run's first attempt, once a read ({@link #run}) found it completed
	 */
	record Correlation(UUID id, Operation operation, String repository, long repositoryId, String workflowPath,
			Set<Long> grant, @Nullable String confirmedHeadSha, Status status, @Nullable ExchangeReason refusal,
			@Nullable Long runId, Instant createdAt, Instant expiresAt, @Nullable Instant issuedAt,
			@Nullable Instant revokedAt, GitHubApps.App app, @Nullable String runHtmlUrl, @Nullable String runConclusion) {

		public Correlation {
			grant = Set.copyOf(grant);
		}

	}

	/** Where a run stands, as GitHub shows it. */
	enum RunStatus {

		/** Not started yet: GitHub's {@code queued}, and its {@code requested}, {@code waiting} and {@code pending}. */
		QUEUED,

		/** {@code in_progress}. */
		IN_PROGRESS,

		/** {@code completed}, with a conclusion. */
		COMPLETED;

		/** GitHub's {@code status} of a workflow run; none, or one not known here, is {@link #QUEUED}. */
		public static RunStatus of(@Nullable String status) {
			if (status == null) {
				return QUEUED;
			}
			return switch (status) {
				case "completed" -> COMPLETED;
				case "in_progress" -> IN_PROGRESS;
				default -> QUEUED;
			};
		}

	}

	/** What a run's conclusion alone does not say. */
	enum RunMarker {

		/**
		 * The run concluded {@code failure} and none of its jobs started: every job shows no steps and no runner, as GitHub
		 * ends a run its account's Actions minutes or spending limit stopped (Story 23.6's {@code ACTIONS_QUOTA})
		 * {@code [ASSUMPTION; Story 23.12 confirms it on real GitHub]}. A failed run that lists no job is no such run.
		 */
		ACTIONS_QUOTA

	}

	/**
	 * A run's first attempt as its dispatching App reads it (Story 23.6a): a Customer's re-run changes nothing here.
	 * @param id the run GitHub gave it
	 * @param htmlUrl its page on GitHub; none when GitHub showed none
	 * @param status where its first attempt stands
	 * @param conclusion GitHub's conclusion once it completed ({@code success}, {@code failure}, {@code cancelled}…);
	 * none before
	 * @param marker {@link RunMarker#ACTIONS_QUOTA} for a failure none of whose jobs started; none otherwise, and none
	 * when its jobs could not be read
	 */
	record Run(long id, @Nullable String htmlUrl, RunStatus status, @Nullable String conclusion,
			@Nullable RunMarker marker) {
	}

	/**
	 * Checks the target's workflow file, records the correlation, dispatches the run on the target's default
	 * branch, and looks for its run for up to {@link #FIND_WINDOW} when GitHub does not name it at once.
	 * @throws WorkflowRefused when the workflow file is missing or not accepted: nothing is recorded or dispatched
	 * @throws GitHubException when GitHub refuses the dispatch (the correlation then expires unused)
	 */
	Dispatched dispatch(Dispatch dispatch);

	/** The correlation, if it exists: a waiting operation reads a refusal here as {@code EXCHANGE_REFUSED}. */
	Optional<Correlation> correlation(UUID id);

	/**
	 * The correlation's run, its first attempt, as its dispatching App reads it now with a token narrowed to the target
	 * (Story 23.6a). A correlation whose run is not known yet looks once at the workflow's runs dispatched since the
	 * correlation was recorded for the one whose title holds it and whose triggering actor is the App's bot, and records
	 * it. The run's page, and its conclusion and marker once it completed, are recorded on the correlation, best-effort;
	 * a run whose conclusion is recorded is answered from the correlation, with no GitHub call. A failed run is marked
	 * {@link RunMarker#ACTIONS_QUOTA} when none of its jobs started; when its jobs cannot be read it is not marked.
	 * @return empty for no such correlation, or while no run shows under it yet
	 * @throws GitHubException when GitHub refuses or cannot be reached
	 */
	Optional<Run> run(UUID correlation);

	/**
	 * What a {@code handover-copy} run reported of its range before it pushed (AD-11, FR-32, Story 23.8a): every path
	 * it changed, with rename detection off, and whether {@code .github/workflows} differs over the range. The list is
	 * never written to a platform repository and never logged.
	 * @param paths the changed paths, in the order {@code git diff} listed them, each once; always {@code count}
	 * of them, since a list that no longer reads back as the run wrote it is answered as no report at all
	 * @param count how many paths the run reported
	 * @param reportedAt when vibefix took the report
	 */
	record Report(UUID correlation, boolean workflowsChanged, List<String> paths, int count, Instant reportedAt) {

		public Report {
			paths = List.copyOf(paths);
			if (paths.size() != count) {
				throw new IllegalArgumentException("A report's list holds exactly the paths it counted");
			}
		}

	}

	/**
	 * The correlation's path report, once its run posted one and it still reads back whole: empty while none was
	 * taken, after the correlation is purged, and for stored bytes that are no longer the list the run counted, so
	 * that no caller ever sees a part of a range (FR-32).
	 */
	Optional<Report> report(UUID correlation);

	/**
	 * Closes a correlation whose run the caller gave up on, such as one still out after {@link #RUN_TIMEOUT}, so that it
	 * never trades for a token beside the run that replaces it (Story 23.6b): an {@link Status#OPEN} correlation becomes
	 * {@link Status#EXPIRED}, which the exchange refuses, and its run, once known, is cancelled best-effort through its
	 * dispatching App with Actions write, narrowed to the target and revoked after use. A cancel GitHub refuses or does
	 * not answer is logged, never thrown; a correlation already exchanged keeps its token until the revocation.
	 */
	void close(UUID correlation);

}
