package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobRules;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.Registered;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * A Code Job's closing choice (FR-37, FR-38, FR-67, D10), one row per Job and Source Repository: its identity, and
 * the anchor of the FR-37 deadline. It opens {@code REQUESTED} at the first closure proposal or at J8, with the
 * to-do {@value #TODO}; J8 sets the FR-37 deadline from the Job's copied {@code FINAL_MERGE_AUTHORIZATION_DAYS},
 * schedules it, and schedules {@link ReceiveCodeReminder} two days before it. It becomes {@code USED} once the
 * choice is spent -- the run's branch read back (Story 23.9a), or the Customer's own code receipt (Story 23.10a) --
 * and {@code ENDED} when its proposal ends without J8, at J10, or at the deadline.
 * <p>
 * Story 23.9a retired the Merge App (R1, D7): there is no app to install at the end, so the {@code BOUND} state, the
 * install link's single-use {@code state}, the installation, the organization's install request and its refusal are
 * all gone, and Story 23.9b dropped the installation table and {@code bound_at} with the App itself. Story 23.10a
 * renamed the to-do after the choice it carries: nothing is authorized any more, and the Customer picks between the
 * Final Merge and "Tôi đã tự tải / clone từ GitHub". Story 23.10c deleted the closure fallback and the event that
 * started it, so a Final Merge that cannot run no longer ends this row: it stays {@code REQUESTED} under its
 * deadline, which is the Job's only way out of {@code CLOSING} when the Customer confirms nothing.
 */
@Component
class FinalMergeAuthorizations {

	static final String TODO = "repo.receive_code";

	static final String RULE = "FINAL_MERGE_AUTHORIZATION_DAYS";

	static final int DEFAULT_DAYS = 7;

	private static final String COLUMNS = """
			id, job_id, product_id, customer_account_id, repository_github_id, repository_name, status, deadline_at,
			requested_at, ended_at, end_reason
			""";

	/** One Final Merge row. */
	record Authorization(UUID id, UUID jobId, UUID productId, @Nullable UUID customerId, long repositoryId,
			String repositoryName, String status, @Nullable Instant deadlineAt, Instant requestedAt,
			@Nullable Instant endedAt, @Nullable String endReason) {

		boolean open() {
			return "REQUESTED".equals(this.status);
		}

	}

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final ProductSources sources;

	private final JobRules rules;

	private final Todos todos;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	FinalMergeAuthorizations(JdbcClient jdbc, JobBusy busy, ProductSources sources, JobRules rules, Todos todos,
			Deadlines deadlines, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.sources = sources;
		this.rules = rules;
		this.todos = todos;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Opens the Job's row {@code REQUESTED}, unless one is open: a Code Job that is Active or Closing, whose Source
	 * Repository has no other open row (AD-13 keeps one live Product per repository).
	 */
	@Transactional
	Optional<Authorization> open(UUID jobId) {
		Optional<Authorization> existing = openOf(jobId);
		if (existing.isPresent()) {
			return existing;
		}
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (job == null || !job.code() || !("ACTIVE".equals(job.state()) || "CLOSING".equals(job.state()))) {
			return Optional.empty();
		}
		Registered source = this.sources.registered(job.productId()).orElse(null);
		if (source == null) {
			return Optional.empty();
		}
		UUID id = this.ids.newId();
		int inserted = this.jdbc.sql("""
				INSERT INTO repo.final_merge_authorization (id, job_id, product_id, customer_account_id, repository_github_id,
				    repository_name, status, requested_at)
				VALUES (:id, :job, :product, :customer, :repository, :name, 'REQUESTED', :now)
				ON CONFLICT DO NOTHING
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", job.productId())
			.param("customer", job.customerId())
			.param("repository", source.repositoryGithubId())
			.param("name", source.repositoryFullName())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		if (inserted == 0) {
			return openOf(jobId);
		}
		this.todos.open(new TodoRequest(TODO, job.customerId(), Audience.CUSTOMER, id, Map.of("job", job.title()),
				link(jobId), null, jobId));
		audit("repo.final_merge_requested", id, jobId);
		return find(id);
	}

	/**
	 * J8: the FR-37 deadline runs from now. The deadline and the reminder two days before it (AD-7) are scheduled
	 * whatever else this transaction can read, because they decide how the Job ends; a deadline already inside those
	 * two days gets no reminder. The to-do names the Job, so it waits for a title to name it with.
	 */
	@Transactional
	void closing(UUID jobId) {
		Authorization authorization = open(jobId).orElse(null);
		if (authorization == null || authorization.deadlineAt() != null) {
			return;
		}
		Instant deadline = this.clock.instant()
			.plus(Duration.ofDays(this.rules.value(jobId, RULE, DEFAULT_DAYS)));
		this.jdbc.sql("UPDATE repo.final_merge_authorization SET deadline_at = :at WHERE id = :id")
			.param("at", Timestamp.from(deadline))
			.param("id", authorization.id())
			.update();
		UUID customer = authorization.customerId();
		String title = this.busy.job(jobId).map(BusyJob::title).orElse(null);
		if (customer != null && title != null) {
			this.todos.closeAll(TODO, authorization.id());
			this.todos.open(new TodoRequest(TODO, customer, Audience.CUSTOMER, authorization.id(), Map.of("job", title),
					link(jobId), deadline, jobId));
		}
		this.deadlines.schedule(new DeadlineRequest(FinalMergeDeadline.KEY, authorization.id(), authorization.id().toString(),
				jobId, deadline));
		Instant remindAt = deadline.minus(ReceiveCodeReminder.BEFORE);
		if (remindAt.isAfter(this.clock.instant())) {
			this.deadlines.schedule(new DeadlineRequest(ReceiveCodeReminder.KEY, authorization.id(),
					authorization.id().toString(), jobId, remindAt));
		}
	}

	/** Ends the Job's open row, if any. */
	@Transactional
	void endOf(UUID jobId, String reason) {
		openOf(jobId).ifPresent(authorization -> end(authorization.id(), reason));
	}

	/**
	 * Ends an open row ({@code PROPOSAL_ENDED}, {@code CANCELLED}, {@code DEADLINE}): the to-do closes. Nothing is
	 * published: Story 23.10c deleted the event and its only listener, and a Job in {@code CLOSING} whose row ends
	 * has already been closed by J9 (Story 23.10b).
	 * @return whether it ended it
	 */
	@Transactional
	boolean end(UUID id, String reason) {
		// The migration of Story 23.10c weakened the CHECK pairing ENDED with a reason, so that the rows of the
		// retired paths could lose theirs; live code must still name one.
		Objects.requireNonNull(reason, "reason");
		Authorization authorization = find(id).orElse(null);
		int ended = this.jdbc.sql("""
				UPDATE repo.final_merge_authorization SET status = 'ENDED', end_reason = :reason, ended_at = :now
				WHERE id = :id AND status = 'REQUESTED'
				""").param("reason", reason).param("now", Timestamp.from(this.clock.instant())).param("id", id).update();
		if (ended == 0 || authorization == null) {
			return false;
		}
		this.todos.closeAll(TODO, id);
		audit("repo.final_merge_ended", id, authorization.jobId());
		return true;
	}

	/**
	 * The choice is spent and the to-do is done: the run's branch was read back (Story 23.9a), or the Customer
	 * confirmed they have the code themselves (Story 23.10a). Either way nothing is left to choose, so the row
	 * never ends.
	 */
	@Transactional
	void used(UUID id) {
		this.jdbc.sql("""
				UPDATE repo.final_merge_authorization SET status = 'USED', ended_at = :now
				WHERE id = :id AND status = 'REQUESTED'
				""").param("now", Timestamp.from(this.clock.instant())).param("id", id).update();
		this.todos.closeAll(TODO, id);
	}

	@Transactional(readOnly = true)
	Optional<Authorization> find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.final_merge_authorization WHERE id = :id")
			.param("id", id)
			.query(FinalMergeAuthorizations::authorization)
			.optional();
	}

	@Transactional(readOnly = true)
	Optional<Authorization> openOf(UUID jobId) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM repo.final_merge_authorization WHERE job_id = :job AND status = 'REQUESTED'")
			.param("job", jobId)
			.query(FinalMergeAuthorizations::authorization)
			.optional();
	}

	/**
	 * The Job's open row, locked until the caller's transaction ends (Story 24.5b). J8's {@link FinalMerges#prepare}
	 * and the read-back of the team's last grant both take this lock before they read, so whichever commits second
	 * sees what the other committed and queues the Final Merge check: neither can miss the other.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Optional<Authorization> lockOpen(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM repo.final_merge_authorization WHERE job_id = :job AND status = 'REQUESTED' FOR UPDATE")
			.param("job", jobId)
			.query(FinalMergeAuthorizations::authorization)
			.optional();
	}

	/** The Job's open row, else its latest. */
	@Transactional(readOnly = true)
	Optional<Authorization> latest(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM repo.final_merge_authorization WHERE job_id = :job
				ORDER BY (status = 'REQUESTED') DESC, requested_at DESC LIMIT 1
				""")
			.param("job", jobId)
			.query(FinalMergeAuthorizations::authorization)
			.optional();
	}

	static String link(UUID jobId) {
		return "/cong-viec/" + jobId;
	}

	static UUID cause(UUID id, String fact) {
		return UUID.nameUUIDFromBytes((id + ":" + fact).getBytes(StandardCharsets.UTF_8));
	}

	void audit(String action, UUID id, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action, AuditObject.of("repo.final_merge_authorization", id))
			.forJob(jobId));
	}

	private static Authorization authorization(ResultSet rs, int n) throws SQLException {
		return new Authorization(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("customer_account_id", UUID.class),
				rs.getLong("repository_github_id"), rs.getString("repository_name"), rs.getString("status"),
				instant(rs, "deadline_at"), java.util.Objects.requireNonNull(instant(rs, "requested_at")),
				instant(rs, "ended_at"), rs.getString("end_reason"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
