package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
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
import vn.vibefix.repo.FinalMergeAuthorizationEnded;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * A Code Job's Final Merge (FR-37, FR-67), one row per Job and Source Repository: its identity, and the anchor of the
 * FR-37 deadline and of the closure fallback. It opens {@code REQUESTED} at the first closure proposal or at J8, with
 * the to-do {@value #TODO}; J8 sets the FR-37 deadline from the Job's copied {@code FINAL_MERGE_AUTHORIZATION_DAYS}
 * and schedules it. It becomes {@code USED} once the run's branch is read back (Story 23.9a) and {@code ENDED} when
 * its proposal ends without J8, at J10, at the deadline, or when the Final Merge cannot run.
 * <p>
 * Story 23.9a retired the Merge App (R1, D7): there is no app to install at the end, so the {@code BOUND} state, the
 * install link's single-use {@code state}, the installation, the organization's install request and its refusal are
 * all gone. The row stays because {@link vn.vibefix.repo.FinalMergeAuthorizationEnded} is what starts Story 8.9's
 * closure fallback, which Story 23.10 replaces with the self-download path.
 */
@Component
class FinalMergeAuthorizations {

	static final String TODO = "repo.authorize_final_merge";

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

	private final ApplicationEventPublisher events;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	FinalMergeAuthorizations(JdbcClient jdbc, JobBusy busy, ProductSources sources, JobRules rules, Todos todos,
			Deadlines deadlines, ApplicationEventPublisher events, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.sources = sources;
		this.rules = rules;
		this.todos = todos;
		this.deadlines = deadlines;
		this.events = events;
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
		this.todos.open(new TodoRequest(TODO, job.customerId(), Audience.CUSTOMER, id, Map.of(), link(jobId), null, jobId));
		audit("repo.final_merge_requested", id, jobId);
		return find(id);
	}

	/** J8: the FR-37 deadline runs from now. The to-do gets it, and the deadline is scheduled. */
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
		if (customer != null) {
			this.todos.closeAll(TODO, authorization.id());
			this.todos.open(new TodoRequest(TODO, customer, Audience.CUSTOMER, authorization.id(), Map.of(), link(jobId),
					deadline, jobId));
		}
		this.deadlines.schedule(new DeadlineRequest(FinalMergeDeadline.KEY, authorization.id(), authorization.id().toString(),
				jobId, deadline));
	}

	/** Ends the Job's open row, if any. */
	@Transactional
	void endOf(UUID jobId, String reason) {
		openOf(jobId).ifPresent(authorization -> end(authorization.id(), reason));
	}

	/**
	 * Ends an open row ({@code PROPOSAL_ENDED}, {@code CANCELLED}, {@code DEADLINE}, ...): the to-do closes, and
	 * {@link FinalMergeAuthorizationEnded} tells Story 8.9's closure fallback.
	 * @return whether it ended it
	 */
	@Transactional
	boolean end(UUID id, String reason) {
		Authorization authorization = find(id).orElse(null);
		int ended = this.jdbc.sql("""
				UPDATE repo.final_merge_authorization SET status = 'ENDED', end_reason = :reason, ended_at = :now
				WHERE id = :id AND status = 'REQUESTED'
				""").param("reason", reason).param("now", Timestamp.from(this.clock.instant())).param("id", id).update();
		if (ended == 0 || authorization == null) {
			return false;
		}
		this.todos.closeAll(TODO, id);
		this.events.publishEvent(new FinalMergeAuthorizationEnded(this.ids.newId(), authorization.jobId(), id, reason));
		audit("repo.final_merge_ended", id, authorization.jobId());
		return true;
	}

	/** The run's branch was read back (Story 23.9a): the row is spent and the to-do is done. */
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
