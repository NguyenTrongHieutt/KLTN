package vn.vibefix.github;

import java.time.Duration;
import java.time.Instant;
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
 * The Platform App is the only dispatching App so far (the runner's handover copy); Story 23.5 wires the
 * Source App for the Customer's {@code vibefix.yml}.
 * @throws GitHubException from {@link #dispatch} when GitHub refuses or cannot be reached
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
	 * @param targetId the numeric ID of the repository the workflow runs in, such as the runner
	 * @param workflow the workflow file in its {@code .github/workflows/}, for example {@code s6-copy.yml}
	 * @param inputs the workflow's inputs; the dispatch adds {@code correlation} itself (and, on the dev stack,
	 * {@code exchange_url})
	 * @param grant the repositories the exchanged token is narrowed to; never the target itself, whose contents and
	 * workflow file the token would then write
	 * @param confirmedHeadSha for {@code copy}, the head the Customer confirmed (Story 23.6): a run at another
	 * head is refused {@code sha}
	 */
	record Dispatch(Operation operation, long targetId, String workflow, Map<String, String> inputs, Set<Long> grant,
			@Nullable String confirmedHeadSha) {

		private static final Pattern WORKFLOW = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.ya?ml");

		private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

		public Dispatch {
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
				throw new IllegalArgumentException("A grant never names the repository the run is in");
			}
			if (confirmedHeadSha != null && (operation != Operation.COPY || !SHA.matcher(confirmedHeadSha).matches())) {
				throw new IllegalArgumentException("A confirmed head is a 40-hex SHA, for copy only");
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
	 */
	record Correlation(UUID id, Operation operation, String repository, long repositoryId, String workflowPath,
			Set<Long> grant, @Nullable String confirmedHeadSha, Status status, @Nullable ExchangeReason refusal,
			@Nullable Long runId, Instant createdAt, Instant expiresAt, @Nullable Instant issuedAt,
			@Nullable Instant revokedAt) {

		public Correlation {
			grant = Set.copyOf(grant);
		}

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

}
