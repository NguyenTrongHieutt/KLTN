package vn.vibefix.repo.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.Comparison;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.github.PlatformRepositories.PullRequestState;
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.internal.JobSetups.Setup;

/**
 * {@link HandoverBranches} over the Job's done Setup (AD-11): the Working head is read live from GitHub on
 * every call, never recorded (D9), and its range comes from GitHub's compare, whose 300-file list is the form's
 * hint only (Story 23.8a). Both repositories are private, so the head read uses a token narrowed to the Working
 * Repository with contents read. Nothing here clones or pushes: the Handover Commit reaches
 * {@code handover/{handoverId}} on the runner's Actions. The merge's 409 is {@link HeadMoved}; the close after M3
 * or M4 marks its comment with the handover, so a retry posts it once. Nothing here runs in a transaction, so
 * nothing is audited here: {@code delivery} audits the handover it records.
 */
@Component
class JobHandoverBranches implements HandoverBranches {

	private static final Logger log = LoggerFactory.getLogger(JobHandoverBranches.class);

	private final JdbcClient jdbc;

	private final JobSetups setups;

	private final PlatformRepositories repositories;

	JobHandoverBranches(JdbcClient jdbc, JobSetups setups, PlatformRepositories repositories) {
		this.jdbc = jdbc;
		this.setups = setups;
		this.repositories = repositories;
	}

	@Override
	public Optional<WorkingHead> working(UUID jobId) {
		Setup setup = done(jobId).orElse(null);
		if (setup == null) {
			return Optional.empty();
		}
		String head;
		try {
			// The default branch's ref alone, read with a token narrowed to the private Working Repository; a branch
			// GitHub does not show (404) is unreadable, never the commit Setup copied.
			head = this.repositories.branchHead(java.util.Objects.requireNonNull(setup.working()), setup.defaultBranch());
		}
		catch (GitHubException failed) {
			throw new Unavailable("The Working Repository of Job " + jobId + " was not read", failed);
		}
		return Optional.of(new WorkingHead(head, setup.approvedSha(), setup.defaultBranch()));
	}

	@Override
	public Range range(UUID jobId, String fromSha, String toSha) {
		Setup setup = done(jobId).orElseThrow(() -> new IllegalStateException("Job " + jobId + " has no done Setup"));
		Comparison comparison;
		try {
			comparison = this.repositories.compare(setup.working(), fromSha, toSha);
		}
		catch (GitHubException failed) {
			throw new Unavailable("The range of Job " + jobId + " was not read", failed);
		}
		return new Range(fromSha, toSha,
				comparison.commits().stream().map(commit -> new Commit(commit.sha(), commit.message(), commit.authorId())).toList(),
				comparison.files(), comparison.truncated());
	}

	@Override
	public void closeAfterRefusal(UUID jobId, int pullNumber) {
		done(jobId).ifPresent(setup -> {
			try {
				this.repositories.closePullRequest(setup.intermediary(), pullNumber);
			}
			catch (GitHubException failed) {
				log.warn("Pull request {} of Job {} was not closed after its handover was refused: {}", pullNumber, jobId,
						failed.getMessage());
			}
		});
	}

	@Override
	public boolean merge(UUID jobId, int pullNumber, String sha, String title) {
		Setup setup = done(jobId).orElseThrow(() -> new IllegalStateException("Job " + jobId + " has no done Setup"));
		try {
			try {
				this.repositories.mergePullRequest(setup.intermediary(), pullNumber, sha, title);
			}
			catch (GitHubException refused) {
				if (refused.status() == 409) {
					// FR-33, D9: GitHub's sha precondition; only this answer of this call means the head moved.
					throw new HeadMoved("Pull request " + pullNumber + " of Job " + jobId + " no longer holds " + sha, refused);
				}
				throw refused;
			}
			boolean merged = this.repositories.pullRequestMerged(setup.intermediary(), pullNumber);
			if (merged) {
				recordHead(setup, pullNumber, sha);
			}
			return merged;
		}
		catch (GitHubException failed) {
			throw new Unavailable("Pull request " + pullNumber + " of Job " + jobId + " was not merged", failed);
		}
	}

	@Override
	public boolean merged(UUID jobId, int pullNumber) {
		Setup setup = done(jobId).orElseThrow(() -> new IllegalStateException("Job " + jobId + " has no done Setup"));
		try {
			return this.repositories.pullRequestMerged(setup.intermediary(), pullNumber);
		}
		catch (GitHubException failed) {
			throw new Unavailable("Pull request " + pullNumber + " of Job " + jobId + " was not read", failed);
		}
	}

	@Override
	public boolean close(UUID jobId, UUID handoverId, int pullNumber, int round, String comment) {
		Setup setup = done(jobId).orElseThrow(() -> new IllegalStateException("Job " + jobId + " has no done Setup"));
		PlatformRepository intermediary = java.util.Objects.requireNonNull(setup.intermediary());
		String marker = closeMarker(handoverId);
		try {
			PullRequestState pull = this.repositories.pullRequest(intermediary, pullNumber);
			if (pull.merged()) {
				// GitHub shows it merged: it is left as GitHub shows it.
				return false;
			}
			String suffix = " (lần " + round + ")";
			boolean renamed = pull.title().endsWith(suffix);
			if ("closed".equals(pull.state()) && renamed) {
				return true;
			}
			boolean commented = this.repositories.pullRequestComments(intermediary, pullNumber)
				.stream()
				.anyMatch(body -> body.contains(marker));
			if (!commented) {
				this.repositories.commentOnPullRequest(intermediary, pullNumber, comment + "\n\n" + marker);
			}
			this.repositories.renameAndClosePullRequest(intermediary, pullNumber, renamed ? pull.title() : pull.title() + suffix);
			return true;
		}
		catch (GitHubException failed) {
			throw new Unavailable("Pull request " + pullNumber + " of Job " + jobId + " was not closed", failed);
		}
	}

	/** The hidden line that marks the close comment of one handover, so a retry finds it and posts none. */
	static String closeMarker(UUID handoverId) {
		return "<!-- vibefix:handover-closed:" + handoverId + " -->";
	}

	/**
	 * SG-9: the Intermediary head as GitHub shows it after this handover's merge, which J9's guard compares
	 * the head with before the Final Merge. Recording it twice changes nothing.
	 */
	private void recordHead(Setup setup, int pullNumber, String sha) {
		String head = this.repositories.refs(java.util.Objects.requireNonNull(setup.intermediary()))
			.get("refs/heads/" + setup.defaultBranch());
		if (head == null) {
			throw new GitHubException(0, "The Intermediary default branch of Job " + setup.jobId() + " was not read back");
		}
		this.jdbc.sql("""
				INSERT INTO repo.handover_merge (job_id, pull_number, handover_sha, head_after, merged_at)
				VALUES (:job, :pull, :sha, :head, now())
				ON CONFLICT DO NOTHING
				""").param("job", setup.jobId()).param("pull", pullNumber).param("sha", sha).param("head", head).update();
	}

	private Optional<Setup> done(UUID jobId) {
		return this.setups.latest(jobId)
			.filter(setup -> "DONE".equals(setup.status()) && setup.working() != null && setup.intermediary() != null
					&& setup.approvedSha() != null && setup.defaultBranch() != null);
	}

}
