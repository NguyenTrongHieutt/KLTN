package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
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
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.Registered;
import vn.vibefix.repo.FinalMergeAuthorizationEnded;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * A Code Job's Final Merge authorization (FR-37, FR-67, AD-10), one per Job and Source Repository. It
 * opens {@code REQUESTED} at the first closure proposal or at J8, with the to-do {@value #TODO}; J8 sets
 * the FR-37 deadline from the Job's copied {@code FINAL_MERGE_AUTHORIZATION_DAYS} and schedules the
 * deadline and its reminder 2 days before. It ends {@code ENDED} when its proposal ends without J8, at J10,
 * or still {@code REQUESTED} at the deadline. The install link's {@code state} is single-use, kept only as
 * its SHA-256, and valid for an hour. {@link MergeAppBindings} binds and unbinds.
 */
@Component
class FinalMergeAuthorizations {

	static final String TODO = "repo.authorize_final_merge";

	static final String PENDING = "repo.final_merge_authorization_pending";

	static final String REMINDER = "repo.final_merge_authorization_reminder";

	static final String RULE = "FINAL_MERGE_AUTHORIZATION_DAYS";

	static final int DEFAULT_DAYS = 7;

	static final Duration REMINDER_BEFORE = Duration.ofDays(2);

	static final Duration STATE_LIFETIME = Duration.ofHours(1);

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final String COLUMNS = """
			id, job_id, product_id, customer_account_id, repository_github_id, repository_name, status, installation_id,
			deadline_at, refusal, refused_at, request_org, request_seen_at, request_refused_at, requested_at, bound_at,
			ended_at, end_reason
			""";

	/**
	 * One authorization.
	 * @param installationId the installation it is or was bound to
	 * @param refusal the last refused installation's reason, {@code MERGE_APP_WRONG_REPOSITORY}
	 * @param requestOrg the organization whose owner must approve the install request, once one was listed
	 */
	record Authorization(UUID id, UUID jobId, UUID productId, @Nullable UUID customerId, long repositoryId,
			String repositoryName, String status, @Nullable Long installationId, @Nullable Instant deadlineAt,
			@Nullable String refusal, @Nullable Instant refusedAt, @Nullable String requestOrg,
			@Nullable Instant requestSeenAt, @Nullable Instant requestRefusedAt, Instant requestedAt,
			@Nullable Instant boundAt, @Nullable Instant endedAt, @Nullable String endReason) {

		boolean open() {
			return "REQUESTED".equals(this.status) || "BOUND".equals(this.status);
		}

		/** The repository's owner, the account the Merge App is installed on. */
		String owner() {
			int slash = this.repositoryName.indexOf('/');
			return slash < 0 ? this.repositoryName : this.repositoryName.substring(0, slash);
		}

	}

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final ProductSources sources;

	private final JobRules rules;

	private final Todos todos;

	private final Notifications notifications;

	private final Deadlines deadlines;

	private final ApplicationEventPublisher events;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	FinalMergeAuthorizations(JdbcClient jdbc, JobBusy busy, ProductSources sources, JobRules rules, Todos todos,
			Notifications notifications, Deadlines deadlines, ApplicationEventPublisher events, AuditLog auditLog,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.sources = sources;
		this.rules = rules;
		this.todos = todos;
		this.notifications = notifications;
		this.deadlines = deadlines;
		this.events = events;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Opens the Job's authorization {@code REQUESTED}, unless one is open: a Code Job that is Active or
	 * Closing, whose Source Repository has no other open authorization (AD-13 keeps one live Product per
	 * repository).
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

	/**
	 * J8: the FR-37 deadline runs from now. The to-do gets it, the deadline and its reminder are
	 * scheduled, and a Customer who has not authorized yet hears it waits.
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
		if (customer != null) {
			this.todos.closeAll(TODO, authorization.id());
			this.todos.open(new TodoRequest(TODO, customer, Audience.CUSTOMER, authorization.id(), Map.of(), link(jobId),
					deadline, jobId));
			if ("REQUESTED".equals(authorization.status())) {
				this.notifications.send(new NotificationRequest(PENDING, cause(authorization.id(), "pending"),
						List.of(new Recipient(customer, Audience.CUSTOMER)), Map.of("date", dayMonth(deadline)), link(jobId),
						jobId, null));
			}
		}
		this.deadlines.schedule(new DeadlineRequest(FinalMergeDeadline.KEY, authorization.id(), authorization.id().toString(),
				jobId, deadline));
		this.deadlines.schedule(new DeadlineRequest(FinalMergeReminder.KEY, authorization.id(), authorization.id().toString(),
				jobId, deadline.minus(REMINDER_BEFORE)));
	}

	/** Ends the Job's open authorization, if any. */
	@Transactional
	void endOf(UUID jobId, String reason) {
		openOf(jobId).ifPresent(authorization -> end(authorization.id(), reason));
	}

	/**
	 * Ends an open authorization ({@code PROPOSAL_ENDED}, {@code CANCELLED}, {@code DEADLINE}, ...): its pair
	 * is no longer bound, the to-do closes, and {@link FinalMergeAuthorizationEnded} tells Story 8.9's ZIP
	 * Fallback.
	 * @return whether it ended it
	 */
	@Transactional
	boolean end(UUID id, String reason) {
		Authorization authorization = find(id).orElse(null);
		int ended = this.jdbc.sql("""
				UPDATE repo.final_merge_authorization SET status = 'ENDED', end_reason = :reason, ended_at = :now,
				    state_hash = NULL, state_expires_at = NULL
				WHERE id = :id AND status IN ('REQUESTED', 'BOUND')
				""").param("reason", reason).param("now", Timestamp.from(this.clock.instant())).param("id", id).update();
		if (ended == 0 || authorization == null) {
			return false;
		}
		this.todos.closeAll(TODO, id);
		this.events.publishEvent(new FinalMergeAuthorizationEnded(this.ids.newId(), authorization.jobId(), id, reason));
		audit("repo.final_merge_ended", id, authorization.jobId());
		return true;
	}

	/**
	 * A fresh install link's single-use {@code state} for the Job's open authorization.
	 * @return the state, kept only as its SHA-256
	 */
	@Transactional
	Optional<String> newState(UUID jobId) {
		Authorization authorization = openOf(jobId).orElse(null);
		if (authorization == null) {
			return Optional.empty();
		}
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		this.jdbc.sql("UPDATE repo.final_merge_authorization SET state_hash = :hash, state_expires_at = :until WHERE id = :id")
			.param("hash", hash(state))
			.param("until", Timestamp.from(this.clock.instant().plus(STATE_LIFETIME)))
			.param("id", authorization.id())
			.update();
		return Optional.of(state);
	}

	/**
	 * Uses up a setup return's {@code state}: it is the signed-in Customer's, unexpired, and its
	 * authorization still open. Any other state changes nothing.
	 */
	@Transactional
	Optional<Authorization> consume(String state, UUID customerId) {
		return this.jdbc.sql("""
				UPDATE repo.final_merge_authorization SET state_hash = NULL, state_expires_at = NULL
				WHERE state_hash = :hash AND state_expires_at > :now AND customer_account_id = :customer
				    AND status IN ('REQUESTED', 'BOUND')
				RETURNING id
				""")
			.param("hash", hash(state))
			.param("now", Timestamp.from(this.clock.instant()))
			.param("customer", customerId)
			.query(UUID.class)
			.optional()
			.flatMap(this::find);
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
		return this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM repo.final_merge_authorization WHERE job_id = :job AND status IN ('REQUESTED', 'BOUND')")
			.param("job", jobId)
			.query(FinalMergeAuthorizations::authorization)
			.optional();
	}

	/** The Job's open authorization, else its latest. */
	@Transactional(readOnly = true)
	Optional<Authorization> latest(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM repo.final_merge_authorization WHERE job_id = :job
				ORDER BY (status IN ('REQUESTED', 'BOUND')) DESC, requested_at DESC LIMIT 1
				""")
			.param("job", jobId)
			.query(FinalMergeAuthorizations::authorization)
			.optional();
	}

	/** Every authorization in a state, for the reconciliation and the bindings. */
	@Transactional(readOnly = true)
	List<Authorization> inStatus(String status) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.final_merge_authorization WHERE status = :status")
			.param("status", status)
			.query(FinalMergeAuthorizations::authorization)
			.list();
	}

	/** The deadline's day and month, or none before J8. */
	static String dayMonth(@Nullable Instant at) {
		return at == null ? "" : DAY_MONTH.format(at.atZone(VIETNAM));
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

	static String hash(String state) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Authorization authorization(ResultSet rs, int n) throws SQLException {
		return new Authorization(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("customer_account_id", UUID.class),
				rs.getLong("repository_github_id"), rs.getString("repository_name"), rs.getString("status"),
				rs.getObject("installation_id", Long.class), instant(rs, "deadline_at"), rs.getString("refusal"),
				instant(rs, "refused_at"), rs.getString("request_org"), instant(rs, "request_seen_at"),
				instant(rs, "request_refused_at"), java.util.Objects.requireNonNull(instant(rs, "requested_at")),
				instant(rs, "bound_at"), instant(rs, "ended_at"), rs.getString("end_reason"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
