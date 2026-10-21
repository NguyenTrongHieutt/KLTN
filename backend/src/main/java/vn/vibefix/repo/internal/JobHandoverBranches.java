package vn.vibefix.repo.internal;

import java.sql.Timestamp;
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
import vn.vibefix.repo.HandoverBranches;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.scanning.ScanStatus;
import vn.vibefix.scanning.ScanSummary;
import vn.vibefix.scanning.Scans;

/**
 * {@link HandoverBranches} over the Job's done Setup (AD-11): the head comes from the push-scan heads
 * after reading them back ({@link PushScans#refresh}), "scanned" means a push scan requested since that
 * head was recorded succeeded, and the pin reuses the copy into the Intermediary Repository with the
 * Working default branch as its source. Nothing here runs in a transaction, so nothing is audited here:
 * {@code delivery} audits the handover it records.
 */
@Component
class JobHandoverBranches implements HandoverBranches {

	private static final Logger log = LoggerFactory.getLogger(JobHandoverBranches.class);

	private final JdbcClient jdbc;

	private final JobSetups setups;

	private final PushScans pushScans;

	private final PlatformRepositories repositories;

	private final Scans scans;

	JobHandoverBranches(JdbcClient jdbc, JobSetups setups, PushScans pushScans, PlatformRepositories repositories, Scans scans) {
		this.jdbc = jdbc;
		this.setups = setups;
		this.pushScans = pushScans;
		this.repositories = repositories;
		this.scans = scans;
	}

	@Override
	public Optional<WorkingHead> working(UUID jobId) {
		Setup setup = done(jobId).orElse(null);
		if (setup == null) {
			return Optional.empty();
		}
		try {
			this.pushScans.refresh(jobId);
		}
		catch (GitHubException failed) {
			throw new Unavailable("The Working Repository of Job " + jobId + " was not read back", failed);
		}
		PlatformRepository working = setup.working();
		record Head(String sha, Timestamp updatedAt) {
		}
		Head head = this.jdbc.sql("SELECT sha, updated_at FROM repo.branch_head WHERE repository_github_id = :id AND ref = :ref")
			.param("id", working.id())
			.param("ref", "refs/heads/" + setup.defaultBranch())
			.query((rs, n) -> new Head(rs.getString("sha"), rs.getTimestamp("updated_at")))
			.optional()
			.orElse(new Head(setup.approvedSha(), Timestamp.from(setup.startedAt())));
		boolean scanned = head.sha().equals(setup.approvedSha()) || this.jdbc.sql("""
				SELECT scan_run_id FROM repo.push_scan
				WHERE job_id = :job AND repository_github_id = :repository AND requested_at >= :since
				ORDER BY requested_at DESC
				""")
			.param("job", jobId)
			.param("repository", working.id())
			.param("since", head.updatedAt())
			.query(UUID.class)
			.list()
			.stream()
			.map(this.scans::run)
			.flatMap(Optional::stream)
			.map(ScanSummary::status)
			.anyMatch(status -> status == ScanStatus.SUCCEEDED);
		return Optional.of(new WorkingHead(head.sha(), setup.approvedSha(), setup.defaultBranch(), scanned));
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
	public int pin(UUID jobId, UUID handoverId, String sha, String title) {
		Setup setup = done(jobId).orElseThrow(() -> new IllegalStateException("Job " + jobId + " has no done Setup"));
		String branch = "handover/" + handoverId;
		int number;
		try {
			this.repositories.copy(setup.working().fullName(), sha, setup.defaultBranch(), branch, setup.intermediary());
			number = this.repositories.openPullRequest(setup.intermediary(), branch, setup.defaultBranch(), title,
					"Bàn giao " + handoverId + " của vibefix: lần lưu " + sha + ".");
		}
		catch (GitHubException failed) {
			throw new Unavailable("The Handover Commit of Job " + jobId + " was not pinned", failed);
		}
		// delivery audits the handover in the transaction that records it.
		return number;
	}

	@Override
	public void unpin(UUID jobId, int pullNumber) {
		done(jobId).ifPresent(setup -> {
			try {
				this.repositories.closePullRequest(setup.intermediary(), pullNumber);
			}
			catch (GitHubException failed) {
				log.warn("Pull request {} of Job {} was not closed after its handover failed: {}", pullNumber, jobId,
						failed.getMessage());
			}
		});
	}

	private Optional<Setup> done(UUID jobId) {
		return this.setups.latest(jobId)
			.filter(setup -> "DONE".equals(setup.status()) && setup.working() != null && setup.intermediary() != null
					&& setup.approvedSha() != null && setup.defaultBranch() != null);
	}

}
