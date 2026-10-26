package vn.vibefix.matching.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.GitHubException;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.MentorWork;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Mentors' Applications (FR-13): one per Mentor and Job, with a price in whole VND, a timeline in days,
 * and an approach. Refused, in this order, for a hidden or closed Job Post, a second Application, the
 * daily limit, no free Student, the Job limit, a person-link signal shared with the Customer (which
 * alerts every Administrator), or a contribution to the Source Repository. The Customer is told of the
 * first Application and keeps a to-do with the open count until J2 or J3. An invited Mentor's
 * Application answers their invitation.
 */
@Component
class Applications {

	/** Applications per Mentor and Vietnam day (AD-34). */
	static final int DAILY_LIMIT = 20;

	/** N-5's version, recorded with the Application. */
	static final String POST_HIDDEN = "job.job_post_hidden";

	static final String PUBLIC_CODE_NOTICE_VERSION = "1.0";

	static final String RECEIVED = "matching.application_received";

	static final String AUTO_WITHDRAWN = "matching.application_auto_withdrawn";

	static final String CHOOSE_ASSIGNEE = "matching.choose_assignee";

	static final String ON_HOLD = "matching.applications_on_hold";

	static final String SIGN_NDA = "matching.sign_nda";

	static final String CLOSED_NOTICE = "matching.applications_closed";

	static final String REOPENED_NOTICE = "matching.applications_reopened";

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	enum Status {
		SUBMITTED, ON_HOLD, SELECTED, CLOSED, WITHDRAWN
	}

	enum EndReason {
		WITHDRAWN, MENTOR_UNAVAILABLE, JOB_WITHDRAWN, JOB_LAPSED, NOT_SELECTED, DECLINED, NOT_SIGNED
	}

	/** What the Mentor offers. */
	record Quote(long priceVnd, int timelineDays, String approach) {
	}

	record Application(UUID id, UUID jobId, UUID productId, UUID customerId, UUID mentorId, Status status,
			long priceVnd, int timelineDays, String approach, Instant submittedAt, @Nullable Instant endedAt,
			@Nullable EndReason endReason) {
	}

	private static final String COLUMNS = """
			id, job_id, product_id, customer_account_id, mentor_account_id, status, price_vnd, timeline_days, approach,
			submitted_at, ended_at, end_reason
			""";

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final MentorWork work;

	private final PersonSignals signals;

	private final CustomerRepositories repositories;

	private final ProductDirectory products;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	private final Todos todos;

	private final ConflictAlerts alerts;

	private final Deadlines deadlines;

	private final Invitations invitations;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	Applications(JdbcClient jdbc, JobPosts posts, MentorWork work, PersonSignals signals,
			CustomerRepositories repositories, ProductDirectory products, MentorDirectory mentors,
			Notifications notifications, Todos todos, ConflictAlerts alerts, Deadlines deadlines,
			Invitations invitations, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.work = work;
		this.signals = signals;
		this.repositories = repositories;
		this.products = products;
		this.mentors = mentors;
		this.notifications = notifications;
		this.todos = todos;
		this.alerts = alerts;
		this.deadlines = deadlines;
		this.invitations = invitations;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Submits the Mentor's Application (the caller checks the GitHub link, AD-19).
	 * @throws ApiException {@code NOT_FOUND} for no such Job; {@code GUARD_FAILED} with
	 * {@code JOB_POST_HIDDEN}, {@code JOB_POST_NOT_OPEN}, {@code NO_FREE_STUDENT}, {@code MENTOR_JOB_LIMIT}
	 * (with {@code limit}), {@code CONFLICT_OF_INTEREST}, {@code APPLICANT_IS_CONTRIBUTOR}, or
	 * {@code CONTRIBUTORS_UNKNOWN}; {@code CONFLICT ALREADY_APPLIED}
	 * @throws RateLimitedException after {@value #DAILY_LIMIT} Applications today
	 */
	@Transactional
	Application submit(UUID mentorId, UUID jobId, Quote quote) {
		JobPostState post = this.posts.post(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (post.hidden()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.JOB_POST_HIDDEN);
		}
		if (!post.open()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.JOB_POST_NOT_OPEN);
		}
		// FR-13's guards run under the Mentor's lock, which J3 takes too (SG-52, AD-4).
		AdvisoryLocks.lockAccount(this.jdbc, mentorId);
		if (find(jobId, mentorId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.ALREADY_APPLIED);
		}
		Instant now = this.clock.instant();
		Instant dayStart = LocalDate.ofInstant(now, VIETNAM).atStartOfDay(VIETNAM).toInstant();
		long today = this.jdbc.sql("""
				SELECT count(*) FROM matching.application WHERE mentor_account_id = :mentor AND submitted_at >= :since
				""").param("mentor", mentorId).param("since", Timestamp.from(dayStart)).query(Long.class).single();
		if (today >= DAILY_LIMIT) {
			throw new RateLimitedException(Duration.between(now, dayStart.plus(Duration.ofDays(1))));
		}
		if (this.work.freeStudents(mentorId).isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.NO_FREE_STUDENT);
		}
		int limit = this.work.activeJobLimit();
		if (this.work.activeJobCount(mentorId) >= limit) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.MENTOR_JOB_LIMIT, Map.of("limit", limit));
		}
		if (this.signals.sharesPersonSignal(mentorId, post.customerId())) {
			this.alerts.conflictRefused();
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.CONFLICT_OF_INTEREST);
		}
		if (contributor(post, mentorId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.APPLICANT_IS_CONTRIBUTOR);
		}
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :customer, :mentor, 'SUBMITTED', :price, :days, :approach, :notice, :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", post.productId())
			.param("customer", post.customerId())
			.param("mentor", mentorId)
			.param("price", quote.priceVnd())
			.param("days", quote.timelineDays())
			.param("approach", quote.approach().strip())
			.param("notice", PUBLIC_CODE_NOTICE_VERSION)
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "matching.application_submitted",
				AuditObject.of("matching.application", id)).forJob(jobId));
		this.invitations.markApplied(jobId, mentorId);
		int open = openCount(jobId);
		if (open == 1) {
			this.notifications.send(new NotificationRequest(RECEIVED, id,
					List.of(new Recipient(post.customerId(), Audience.CUSTOMER)), Map.of("job", post.title()),
					applicationsLink(jobId), jobId, null));
		}
		refreshTodo(post, open);
		return find(jobId, mentorId).orElseThrow();
	}

	/** Whether the Mentor contributed to the Source Repository; GitHub not answering refuses, failing closed. */
	private boolean contributor(JobPostState post, UUID mentorId) {
		ProductInfo product = this.products.find(post.productId())
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.JOB_POST_NOT_OPEN));
		String[] repository = product.repositoryFullName().split("/", 2);
		try {
			return this.repositories.hasContributor(post.customerId(), repository[0], repository[1], mentorId);
		}
		catch (GitHubException | ApiException unanswered) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.CONTRIBUTORS_UNKNOWN);
		}
	}

	/**
	 * Withdraws the Mentor's own submitted or held Application (FR-13).
	 * @throws ApiException {@code NOT_FOUND} for another Mentor's; {@code CONFLICT NOT_WITHDRAWABLE} once
	 * selected or ended
	 */
	@Transactional
	Application withdraw(UUID mentorId, UUID applicationId) {
		Application application = this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM matching.application WHERE id = :id AND mentor_account_id = :mentor FOR UPDATE")
			.param("id", applicationId)
			.param("mentor", mentorId)
			.query(Applications::application)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (application.status() != Status.SUBMITTED && application.status() != Status.ON_HOLD) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_WITHDRAWABLE);
		}
		end(applicationId, Status.WITHDRAWN, EndReason.WITHDRAWN);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "matching.application_withdrawn",
				AuditObject.of("matching.application", applicationId)).forJob(application.jobId()));
		this.posts.post(application.jobId()).ifPresent(post -> refreshTodo(post, openCount(post.jobId())));
		return find(application.jobId(), mentorId).orElseThrow();
	}

	/** FR-60: each Mentor with a submitted or held Application learns the Job Post was hidden. */
	@Transactional
	void postHidden(UUID jobId) {
		String title = this.posts.post(jobId).map(JobPostState::title).orElse("");
		this.jdbc.sql("""
				SELECT id, mentor_account_id FROM matching.application WHERE job_id = :job AND status IN ('SUBMITTED', 'ON_HOLD')
				""").param("job", jobId).query((rs, n) -> {
			UUID application = rs.getObject("id", UUID.class);
			this.notifications.send(new NotificationRequest(POST_HIDDEN, application,
					List.of(new Recipient(rs.getObject("mentor_account_id", UUID.class), Audience.SUBJECT)),
					Map.of("job", title), "/cong-viec", null, application));
			return null;
		}).list();
	}

	/** J2: the Job's submitted and held Applications close, and so does the Customer's to-do (§4.0). */
	@Transactional
	void closeForJob(UUID jobId, EndReason reason) {
		this.jdbc.sql("""
				UPDATE matching.application SET status = 'CLOSED', ended_at = :now, end_reason = :reason
				WHERE job_id = :job AND status IN ('SUBMITTED', 'ON_HOLD')
				""")
			.param("job", jobId)
			.param("reason", reason.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.closeAll(CHOOSE_ASSIGNEE, jobId);
	}

	/**
	 * A locked Mentor (FR-13, AD-19): their submitted and held Applications are withdrawn, and
	 * each Customer learns why. Withdrawing them again changes nothing.
	 */
	@Transactional
	void withdrawForMentor(UUID mentorId) {
		List<Application> open = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.application WHERE mentor_account_id = :mentor AND status IN ('SUBMITTED', 'ON_HOLD')
				ORDER BY submitted_at, id FOR UPDATE
				""").param("mentor", mentorId).query(Applications::application).list();
		if (open.isEmpty()) {
			return;
		}
		String mentor = this.mentors.cards(List.of(mentorId)).stream().findFirst().map(card -> card.displayName()).orElse("");
		for (Application application : open) {
			end(application.id(), Status.WITHDRAWN, EndReason.MENTOR_UNAVAILABLE);
			Optional<JobPostState> post = this.posts.post(application.jobId());
			this.notifications.send(new NotificationRequest(AUTO_WITHDRAWN, application.id(),
					List.of(new Recipient(application.customerId(), Audience.CUSTOMER)),
					Map.of("mentor", mentor, "job", post.map(JobPostState::title).orElse("")),
					applicationsLink(application.jobId()), application.jobId(), null));
			post.ifPresent(found -> refreshTodo(found, openCount(found.jobId())));
		}
	}

	/**
	 * J3's consequence for Applications (§4.0, AD-5): the selected one is {@code SELECTED}, the other
	 * submitted ones go {@code ON_HOLD} and their Mentors are told, the Customer's to-do closes, and the
	 * selected Mentor's NDA to-do opens with the FR-15 deadline.
	 */
	@Transactional
	void applySelection(UUID jobId, UUID selectedApplicationId, UUID mentorId, Instant ndaDueAt) {
		this.jdbc.sql("UPDATE matching.application SET status = 'SELECTED' WHERE id = :id AND job_id = :job")
			.param("id", selectedApplicationId)
			.param("job", jobId)
			.update();
		List<Application> held = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.application WHERE job_id = :job AND status = 'SUBMITTED' AND id <> :selected
				ORDER BY submitted_at, id FOR UPDATE
				""").param("job", jobId).param("selected", selectedApplicationId).query(Applications::application).list();
		String title = this.posts.post(jobId).map(JobPostState::title).orElse("");
		for (Application application : held) {
			this.jdbc.sql("UPDATE matching.application SET status = 'ON_HOLD' WHERE id = :id")
				.param("id", application.id())
				.update();
			this.notifications.send(new NotificationRequest(ON_HOLD, application.id(),
					List.of(new Recipient(application.mentorId(), Audience.SUBJECT)), Map.of("job", title), "/cong-viec",
					null, application.id()));
		}
		this.todos.closeAll(CHOOSE_ASSIGNEE, jobId);
		this.todos.open(new TodoRequest(SIGN_NDA, mentorId, Audience.MENTOR, jobId, Map.of("job", title),
				"/cong-viec/" + jobId, ndaDueAt, jobId));
		this.deadlines.schedule(new DeadlineRequest(NdaDue.KEY, selectedApplicationId,
				selectedApplicationId.toString(), jobId, ndaDueAt));
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "matching.applications_held",
				AuditObject.of("matching.application", selectedApplicationId)).forJob(jobId));
	}

	/** J4 (§4.0): the held Applications close, their Mentors are told, and the NDA to-do closes. */
	@Transactional
	void closeHeld(UUID jobId) {
		List<Application> held = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.application WHERE job_id = :job AND status = 'ON_HOLD' ORDER BY submitted_at, id FOR UPDATE
				""").param("job", jobId).query(Applications::application).list();
		String title = this.posts.post(jobId).map(JobPostState::title).orElse("");
		for (Application application : held) {
			end(application.id(), Status.CLOSED, EndReason.NOT_SELECTED);
			this.notifications.send(new NotificationRequest(CLOSED_NOTICE, application.id(),
					List.of(new Recipient(application.mentorId(), Audience.SUBJECT)), Map.of("job", title), "/cong-viec",
					null, application.id()));
		}
		this.todos.closeAll(SIGN_NDA, jobId);
	}

	/**
	 * J5 (§4.0): the selected Application closes as declined or not signed, the held ones reopen and
	 * their Mentors are told, the NDA to-do closes, and the Customer's choice to-do opens again.
	 */
	@Transactional
	void reopen(UUID jobId, UUID selectedApplicationId, EndReason reason) {
		end(selectedApplicationId, Status.CLOSED, reason);
		List<Application> held = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.application WHERE job_id = :job AND status = 'ON_HOLD' ORDER BY submitted_at, id FOR UPDATE
				""").param("job", jobId).query(Applications::application).list();
		Optional<JobPostState> post = this.posts.post(jobId);
		String title = post.map(JobPostState::title).orElse("");
		for (Application application : held) {
			this.jdbc.sql("UPDATE matching.application SET status = 'SUBMITTED' WHERE id = :id")
				.param("id", application.id())
				.update();
			this.notifications.send(new NotificationRequest(REOPENED_NOTICE, application.id(),
					List.of(new Recipient(application.mentorId(), Audience.SUBJECT)), Map.of("job", title),
					"/tim-viec/" + jobId, null, application.id()));
		}
		this.todos.closeAll(SIGN_NDA, jobId);
		post.ifPresent(found -> refreshTodo(found, openCount(jobId)));
	}

	/** Whether the Application is still the selected one: the NDA's Deadline then reminds the Customer. */
	@Transactional(readOnly = true)
	Optional<Application> byId(UUID applicationId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM matching.application WHERE id = :id")
			.param("id", applicationId)
			.query(Applications::application)
			.optional();
	}

	/** The Mentor's Applications, newest first (MEN-4, MEN-3). */
	@Transactional(readOnly = true)
	List<Application> ofMentor(UUID mentorId) {
		return this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM matching.application WHERE mentor_account_id = :mentor ORDER BY submitted_at DESC, id")
			.param("mentor", mentorId)
			.query(Applications::application)
			.list();
	}

	/** One Application of the Job, whatever its status. */
	@Transactional(readOnly = true)
	Optional<Application> ofJob(UUID jobId, UUID applicationId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM matching.application WHERE id = :id AND job_id = :job")
			.param("id", applicationId)
			.param("job", jobId)
			.query(Applications::application)
			.optional();
	}

	/** The Job's submitted, held, and selected Applications, newest first (CUS-8). */
	@Transactional(readOnly = true)
	List<Application> openOfJob(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.application WHERE job_id = :job AND status IN ('SUBMITTED', 'ON_HOLD', 'SELECTED')
				ORDER BY submitted_at DESC, id
				""").param("job", jobId).query(Applications::application).list();
	}

	private Optional<Application> find(UUID jobId, UUID mentorId) {
		return this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM matching.application WHERE job_id = :job AND mentor_account_id = :mentor")
			.param("job", jobId)
			.param("mentor", mentorId)
			.query(Applications::application)
			.optional();
	}

	private int openCount(UUID jobId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM matching.application WHERE job_id = :job AND status = 'SUBMITTED'
				""").param("job", jobId).query(Integer.class).single();
	}

	/** "Chọn người hướng dẫn ({n} đơn ứng tuyển)", due when the Job Post lapses, while any is open (UX-DR15). */
	private void refreshTodo(JobPostState post, int open) {
		this.todos.closeAll(CHOOSE_ASSIGNEE, post.jobId());
		if (open > 0 && post.open()) {
			this.todos.open(new TodoRequest(CHOOSE_ASSIGNEE, post.customerId(), Audience.CUSTOMER, post.jobId(),
					Map.of("n", String.valueOf(open)), applicationsLink(post.jobId()), post.lapsesAt(), post.jobId()));
		}
	}

	private void end(UUID applicationId, Status status, EndReason reason) {
		this.jdbc.sql("""
				UPDATE matching.application SET status = :status, ended_at = :now, end_reason = :reason WHERE id = :id
				""")
			.param("id", applicationId)
			.param("status", status.name())
			.param("reason", reason.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	static String applicationsLink(UUID jobId) {
		return "/cong-viec/" + jobId + "/ung-tuyen";
	}

	private static Application application(ResultSet rs, int n) throws SQLException {
		String reason = rs.getString("end_reason");
		Timestamp ended = rs.getTimestamp("ended_at");
		return new Application(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("customer_account_id", UUID.class),
				rs.getObject("mentor_account_id", UUID.class), Status.valueOf(rs.getString("status")),
				rs.getLong("price_vnd"), rs.getInt("timeline_days"), rs.getString("approach"),
				rs.getTimestamp("submitted_at").toInstant(), ended == null ? null : ended.toInstant(),
				reason == null ? null : EndReason.valueOf(reason));
	}

}
