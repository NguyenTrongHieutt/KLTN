package vn.vibefix.matching.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;

/**
 * Invitations to apply (FR-14): a Customer asks an available Mentor, one with GitHub linked (Story 19.7),
 * to apply to their Open Job Post, once per Mentor and Job, within the day's counters. The Mentor is told and keeps a to-do until they
 * apply or the Job leaves Open; an invitation reserves nothing and selects no one.
 */
@Component
class Invitations {

	/** Invitations per Job Post and Vietnam day (AD-34). */
	static final int JOB_POST_LIMIT = 10;

	/** Invitations per Customer and Vietnam day (AD-34). */
	static final int CUSTOMER_LIMIT = 30;

	static final String INVITED = "matching.invited_to_apply";

	static final String ANSWER = "matching.answer_invitation_to_apply";

	enum Status {
		OPEN, APPLIED, LAPSED
	}

	/** What a search card's Invite button shows (UX-DR49). */
	enum Relation {
		NONE, INVITED, APPLIED
	}

	record Invitation(UUID id, UUID jobId, UUID customerId, UUID mentorId, Status status, Instant invitedAt,
			@Nullable Instant endedAt) {
	}

	/**
	 * The invitations the Customer may still send for the Job today.
	 * @param limit the used-up counter, if any: {@code INVITE_LIMIT_JOB_POST} or {@code INVITE_LIMIT_DAILY}
	 */
	record Quota(int left, @Nullable MatchingReason limit) {
	}

	/** The Mentor's invitation as MEN-3 shows it; {@code LAPSED} once the Job Post no longer takes Applications. */
	record MentorInvitation(UUID id, String jobTitle, Status status, Instant invitedAt) {
	}

	private static final String COLUMNS = """
			id, job_id, customer_account_id, mentor_account_id, status, invited_at, ended_at
			""";

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	Invitations(JdbcClient jdbc, JobPosts posts, MentorDirectory mentors, Notifications notifications, Todos todos,
			AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.mentors = mentors;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Sends the Customer's invitation (FR-14), checked in this order under the Customer's lock.
	 * @throws ApiException {@code NOT_FOUND} for another Customer's Job; {@code GUARD_FAILED} with
	 * {@code JOB_POST_HIDDEN}, {@code JOB_POST_NOT_OPEN}, or {@code INVITEE_NOT_AVAILABLE};
	 * {@code CONFLICT} with {@code ALREADY_INVITED} or {@code INVITEE_ALREADY_APPLIED}
	 * @throws RateLimitedException with {@code INVITE_LIMIT_JOB_POST} or {@code INVITE_LIMIT_DAILY}
	 */
	@Transactional
	Quota invite(UUID customerId, UUID jobId, UUID mentorId) {
		JobPostState post = ownPost(customerId, jobId);
		if (post.hidden()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.JOB_POST_HIDDEN);
		}
		if (!post.open()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.JOB_POST_NOT_OPEN);
		}
		AdvisoryLocks.lockAccount(this.jdbc, customerId);
		boolean available = this.mentors.cards(List.of(mentorId)).stream().anyMatch(MentorCard::available);
		if (!available) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.INVITEE_NOT_AVAILABLE);
		}
		if (exists("matching.invitation", jobId, mentorId)) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.ALREADY_INVITED);
		}
		if (exists("matching.application", jobId, mentorId)) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.INVITEE_ALREADY_APPLIED);
		}
		Instant now = this.clock.instant();
		Quota quota = quota(customerId, jobId, now);
		if (quota.limit() != null) {
			throw new RateLimitedException(Duration.between(now, nextDay(now)), quota.limit());
		}
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO matching.invitation (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    invited_at)
				VALUES (:id, :job, :product, :customer, :mentor, 'OPEN', :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", post.productId())
			.param("customer", customerId)
			.param("mentor", mentorId)
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(customerId), "matching.invitation_sent",
				AuditObject.of("matching.invitation", id)).forJob(jobId));
		String link = "/tim-viec/" + jobId;
		this.notifications.send(new NotificationRequest(INVITED, id, List.of(new Recipient(mentorId, Audience.SUBJECT)),
				Map.of("job", post.title()), link, null, id));
		this.todos.open(new TodoRequest(ANSWER, mentorId, Audience.SUBJECT, id, Map.of("job", post.title()), link,
				post.lapsesAt(), null));
		return quota(customerId, jobId, now);
	}

	/** The Customer's quota for their Job today (UX-DR49). */
	@Transactional(readOnly = true)
	Quota quota(UUID customerId, UUID jobId) {
		ownPost(customerId, jobId);
		return quota(customerId, jobId, this.clock.instant());
	}

	/** The invitations the Customer sent for their Job, newest first. */
	@Transactional(readOnly = true)
	List<Invitation> ofJob(UUID customerId, UUID jobId) {
		ownPost(customerId, jobId);
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.invitation WHERE job_id = :job AND customer_account_id = :customer
				ORDER BY invited_at DESC, id
				""").param("job", jobId).param("customer", customerId).query(Invitations::invitation).list();
	}

	/** What each search card's Invite button shows for the Job: applied wins over invited. */
	@Transactional(readOnly = true)
	Map<UUID, Relation> relations(UUID jobId, Collection<UUID> mentorIds) {
		Map<UUID, Relation> relations = new HashMap<>();
		mentorIds.forEach(id -> relations.put(id, Relation.NONE));
		if (mentorIds.isEmpty()) {
			return relations;
		}
		this.jdbc.sql("SELECT mentor_account_id FROM matching.invitation WHERE job_id = :job AND mentor_account_id IN (:ids)")
			.param("job", jobId)
			.param("ids", List.copyOf(mentorIds))
			.query(UUID.class)
			.list()
			.forEach(id -> relations.put(id, Relation.INVITED));
		this.jdbc.sql("SELECT mentor_account_id FROM matching.application WHERE job_id = :job AND mentor_account_id IN (:ids)")
			.param("job", jobId)
			.param("ids", List.copyOf(mentorIds))
			.query(UUID.class)
			.list()
			.forEach(id -> relations.put(id, Relation.APPLIED));
		return relations;
	}

	/** The Mentors invited to the Job, whatever came of it: CUS-8's "Đã mời" chip (UX-DR50). */
	@Transactional(readOnly = true)
	List<UUID> invitedMentors(UUID jobId) {
		return this.jdbc.sql("SELECT mentor_account_id FROM matching.invitation WHERE job_id = :job")
			.param("job", jobId)
			.query(UUID.class)
			.list();
	}

	/** The Jobs whose Customer invited this Mentor and still waits: MEN-2's "Bạn được mời". */
	@Transactional(readOnly = true)
	Set<UUID> openFor(UUID mentorId) {
		return new HashSet<>(this.jdbc.sql("""
				SELECT job_id FROM matching.invitation WHERE mentor_account_id = :mentor AND status = 'OPEN'
				""").param("mentor", mentorId).query(UUID.class).list());
	}

	/**
	 * The Mentor's invitation to the Job, for MEN-3; an open one whose Job Post is gone, closed, or
	 * hidden reads as {@code LAPSED} before any listener ran.
	 */
	@Transactional(readOnly = true)
	Optional<MentorInvitation> forMentor(UUID mentorId, UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM matching.invitation WHERE job_id = :job AND mentor_account_id = :mentor")
			.param("job", jobId)
			.param("mentor", mentorId)
			.query(Invitations::invitation)
			.optional()
			.map(invitation -> {
				Optional<JobPostState> post = this.posts.post(jobId);
				Status status = invitation.status() == Status.OPEN
						&& post.map(found -> !found.open() || found.hidden()).orElse(true) ? Status.LAPSED
								: invitation.status();
				return new MentorInvitation(invitation.id(), post.map(JobPostState::title).orElse(""), status,
						invitation.invitedAt());
			});
	}

	/** J2 or J3: the Job's open invitations lapse and their to-dos close (FR-14, SG-41). */
	@Transactional
	void lapseForJob(UUID jobId) {
		List<UUID> lapsed = this.jdbc.sql("""
				UPDATE matching.invitation SET status = 'LAPSED', ended_at = :now WHERE job_id = :job AND status = 'OPEN'
				RETURNING id
				""").param("job", jobId).param("now", Timestamp.from(this.clock.instant())).query(UUID.class).list();
		lapsed.forEach(id -> this.todos.closeAll(ANSWER, id));
	}

	/** The invited Mentor applied: the invitation is answered and its to-do closes (FR-14). */
	@Transactional
	void markApplied(UUID jobId, UUID mentorId) {
		List<UUID> answered = this.jdbc.sql("""
				UPDATE matching.invitation SET status = 'APPLIED', ended_at = :now
				WHERE job_id = :job AND mentor_account_id = :mentor AND status = 'OPEN'
				RETURNING id
				""")
			.param("job", jobId)
			.param("mentor", mentorId)
			.param("now", Timestamp.from(this.clock.instant()))
			.query(UUID.class)
			.list();
		answered.forEach(id -> this.todos.closeAll(ANSWER, id));
	}

	private JobPostState ownPost(UUID customerId, UUID jobId) {
		return this.posts.post(jobId)
			.filter(post -> post.customerId().equals(customerId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private Quota quota(UUID customerId, UUID jobId, Instant now) {
		Timestamp since = Timestamp.from(LocalDate.ofInstant(now, Applications.VIETNAM)
			.atStartOfDay(Applications.VIETNAM)
			.toInstant());
		long forPost = this.jdbc.sql("SELECT count(*) FROM matching.invitation WHERE job_id = :job AND invited_at >= :since")
			.param("job", jobId)
			.param("since", since)
			.query(Long.class)
			.single();
		long forCustomer = this.jdbc.sql("""
				SELECT count(*) FROM matching.invitation WHERE customer_account_id = :customer AND invited_at >= :since
				""").param("customer", customerId).param("since", since).query(Long.class).single();
		int left = (int) Math.max(0, Math.min(JOB_POST_LIMIT - forPost, CUSTOMER_LIMIT - forCustomer));
		MatchingReason limit = forPost >= JOB_POST_LIMIT ? MatchingReason.INVITE_LIMIT_JOB_POST
				: forCustomer >= CUSTOMER_LIMIT ? MatchingReason.INVITE_LIMIT_DAILY : null;
		return new Quota(left, limit);
	}

	private boolean exists(String table, UUID jobId, UUID mentorId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE job_id = :job AND mentor_account_id = :mentor)")
			.param("job", jobId)
			.param("mentor", mentorId)
			.query(Boolean.class)
			.single();
	}

	private static Instant nextDay(Instant now) {
		return LocalDate.ofInstant(now, Applications.VIETNAM).plusDays(1).atStartOfDay(Applications.VIETNAM).toInstant();
	}

	private static Invitation invitation(ResultSet rs, int n) throws SQLException {
		Timestamp ended = rs.getTimestamp("ended_at");
		return new Invitation(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("customer_account_id", UUID.class), rs.getObject("mentor_account_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getTimestamp("invited_at").toInstant(),
				ended == null ? null : ended.toInstant());
	}

}
