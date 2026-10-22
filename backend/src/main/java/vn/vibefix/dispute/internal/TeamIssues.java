package vn.vibefix.dispute.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.job.Crews;
import vn.vibefix.job.JobRules;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.job.TeamIssueLocks.TeamIssueJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.ShareHolds;
import vn.vibefix.reputation.CrewRatings;
import vn.vibefix.reputation.CrewRatings.CrewRating;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Team Issues (FR-79, AD-40, Tier 3): a Crew member who was ever active raises one with the Job's Mentor
 * about their share of a Held Milestone or the Rating the Mentor gave them. The raise is one transaction in
 * AD-4's lock order: the Job row ({@code job.lockForTeamIssue}), the Milestone's Team Issue rows, then the
 * hold {@code payment} takes under the ledger account lock, and a refused hold creates nothing. It opens the
 * non-pausable answer Deadline of the Job's copied period and tells the Mentor, never the Customer; it never
 * takes the busy marker or freezes the Job.
 */
@Component
class TeamIssues {

	static final String RAISED = "dispute.team_issue_raised";

	static final String ANSWER_TODO = "dispute.answer_team_issue";

	/** How long after a Rating becomes visible it may be contested (FR-79). */
	static final Duration RATING_WINDOW = Duration.ofDays(14);

	/** The FR-61 default of both periods, for a Job published before the rules existed. */
	static final int DEFAULT_DAYS = 5;

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final Set<String> HELD = Set.of("FUNDED", "REVISION_REQUESTED", "SUBMITTED");

	enum Subject {
		SHARE, RATING
	}

	enum Phase {
		ANSWER, ESCALATION, ESCALATED, ENDED
	}

	record TeamIssue(UUID id, UUID jobId, Subject subject, @Nullable UUID milestoneId, @Nullable Integer milestoneNumber,
			@Nullable UUID ratingId, @Nullable UUID raiserId, @Nullable UUID mentorId, String reason, Phase phase,
			Instant raisedAt, Instant answerDueAt, @Nullable Instant escalationDueAt, @Nullable Instant endedAt) {
	}

	/**
	 * A Milestone the Student may raise their share of.
	 * @param refusal why "Phần chia giai đoạn {n}" is disabled, if it is
	 * @param endedAt when the earlier issue on it ended, for {@code TEAM_ISSUE_ALREADY_RAISED}
	 */
	record ShareOption(UUID milestoneId, int number, String title, @Nullable DisputeReason refusal,
			@Nullable Instant endedAt) {
	}

	/** A Rating the Student may contest until {@code closesAt}. */
	record RatingOption(UUID ratingId, int stars, Instant visibleAt, Instant closesAt, @Nullable DisputeReason refusal,
			@Nullable Instant endedAt) {
	}

	/** STU-4's Team Issue panel for one Job. */
	record JobPanel(UUID jobId, String title, String mentorName, List<ShareOption> shares, List<RatingOption> ratings,
			List<TeamIssue> issues) {
	}

	private final JdbcClient jdbc;

	private final TeamIssueLocks locks;

	private final Crews crews;

	private final MilestoneFunding funding;

	private final JobRules rules;

	private final Ledger ledger;

	private final ShareHolds holds;

	private final CrewRatings ratings;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	TeamIssues(JdbcClient jdbc, TeamIssueLocks locks, Crews crews, MilestoneFunding funding, JobRules rules, Ledger ledger,
			ShareHolds holds, CrewRatings ratings, MentorDirectory mentors, StudentDirectory students, Deadlines deadlines,
			Notifications notifications, Todos todos, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.locks = locks;
		this.crews = crews;
		this.funding = funding;
		this.rules = rules;
		this.ledger = ledger;
		this.holds = holds;
		this.ratings = ratings;
		this.mentors = mentors;
		this.students = students;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * STU-4: per Job where the Student was ever active, what they may raise and their own issues; a Job
	 * with none of these is left out.
	 */
	@Transactional(readOnly = true)
	List<JobPanel> panel(UUID studentId) {
		Instant now = this.clock.instant();
		List<CrewRating> received = this.ratings.receivedBy(studentId);
		List<JobPanel> panels = new ArrayList<>();
		for (UUID jobId : this.locks.crewJobsOf(studentId)) {
			FundingJob job = this.funding.byId(jobId).orElse(null);
			if (job == null || job.mentorId() == null) {
				continue;
			}
			List<TeamIssue> issues = this.jdbc
				.sql("SELECT * FROM dispute.team_issue WHERE job_id = :job AND raiser_account_id = :raiser ORDER BY raised_at DESC, id")
				.param("job", jobId)
				.param("raiser", studentId)
				.query(TeamIssues::issue)
				.list();
			boolean open = issues.stream().anyMatch(issue -> issue.phase() != Phase.ENDED);
			List<ShareOption> shares = new ArrayList<>();
			for (FundingMilestone milestone : job.milestones()) {
				boolean party = HELD.contains(milestone.state()) && this.crews.releaseTermsOf(milestone.milestoneId())
					.map(terms -> terms.shares().containsKey(studentId))
					.orElse(false);
				if (!party || this.ledger.releasedTotal(milestone.milestoneId()) > 0) {
					continue;
				}
				TeamIssue earlier = issues.stream()
					.filter(issue -> milestone.milestoneId().equals(issue.milestoneId()))
					.findFirst()
					.orElse(null);
				shares.add(new ShareOption(milestone.milestoneId(), milestone.number(), milestone.title(),
						earlier != null ? DisputeReason.TEAM_ISSUE_ALREADY_RAISED : open ? DisputeReason.TEAM_ISSUE_OPEN : null,
						earlier == null ? null : earlier.endedAt()));
			}
			List<RatingOption> options = new ArrayList<>();
			for (CrewRating rating : received) {
				if (!rating.jobId().equals(jobId)) {
					continue;
				}
				TeamIssue earlier = issues.stream()
					.filter(issue -> rating.ratingId().equals(issue.ratingId()))
					.findFirst()
					.orElse(null);
				Instant closes = rating.visibleAt().plus(RATING_WINDOW);
				if (earlier == null && !now.isBefore(closes)) {
					continue;
				}
				options.add(new RatingOption(rating.ratingId(), rating.stars(), rating.visibleAt(), closes,
						earlier != null ? DisputeReason.TEAM_ISSUE_ALREADY_RAISED : open ? DisputeReason.TEAM_ISSUE_OPEN : null,
						earlier == null ? null : earlier.endedAt()));
			}
			if (shares.isEmpty() && options.isEmpty() && issues.isEmpty()) {
				continue;
			}
			String mentorName = this.mentors.cards(List.of(job.mentorId()))
				.stream()
				.findFirst()
				.map(MentorDirectory.MentorCard::displayName)
				.orElse("");
			panels.add(new JobPanel(jobId, job.title(), mentorName, shares, options, issues));
		}
		return panels;
	}

	/**
	 * Raises a Team Issue.
	 * @param targetId the Milestone of a {@code SHARE} issue, the Rating of a {@code RATING} issue
	 * @throws ApiException {@code NOT_FOUND} unless the Student was ever active on the Job and, for a
	 * Rating, is its ratee; {@code VALIDATION}; {@code CONFLICT TEAM_ISSUE_ALREADY_RAISED} or
	 * {@code TEAM_ISSUE_OPEN}; {@code GUARD_FAILED MILESTONE_NOT_HELD} or {@code TEAM_ISSUE_WINDOW_CLOSED}
	 */
	@Transactional
	TeamIssue raise(UUID studentId, UUID jobId, Subject subject, UUID targetId, String reasonText) {
		if (!this.locks.crewJobsOf(studentId).contains(jobId)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		String reason = reasonText.strip();
		if (reason.isEmpty() || reason.length() > 2000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		CrewRating rating = subject == Subject.RATING ? this.ratings.find(targetId)
			.filter(found -> found.studentId().equals(studentId) && found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)) : null;
		boolean share = subject == Subject.SHARE;
		TeamIssueJob job = this.locks.lockForTeamIssue(jobId, share ? targetId : null, share)
			.filter(found -> found.mentorId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (share) {
			this.jdbc.sql("SELECT id FROM dispute.team_issue WHERE milestone_id = :milestone ORDER BY id FOR UPDATE")
				.param("milestone", targetId)
				.query(UUID.class)
				.list();
		}
		boolean raised = this.jdbc.sql(share ? """
				SELECT EXISTS (SELECT 1 FROM dispute.team_issue WHERE subject = 'SHARE' AND milestone_id = :target
				                                                  AND raiser_account_id = :raiser)
				""" : "SELECT EXISTS (SELECT 1 FROM dispute.team_issue WHERE subject = 'RATING' AND rating_id = :target)")
			.param("target", targetId)
			.param("raiser", studentId)
			.query(Boolean.class)
			.single();
		if (raised) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.TEAM_ISSUE_ALREADY_RAISED);
		}
		if (openOf(jobId, studentId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.TEAM_ISSUE_OPEN);
		}
		Instant now = this.clock.instant();
		if (rating != null && !now.isBefore(rating.visibleAt().plus(RATING_WINDOW))) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.TEAM_ISSUE_WINDOW_CLOSED);
		}
		int answerDays = this.rules.value(jobId, "TEAM_ISSUE_ANSWER_DAYS", DEFAULT_DAYS);
		int escalationDays = this.rules.value(jobId, "TEAM_ISSUE_ESCALATION_DAYS", DEFAULT_DAYS);
		Instant answerDue = now.plus(Duration.ofDays(answerDays));
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, milestone_id, milestone_number, rating_id,
				    raiser_account_id, mentor_account_id, reason, phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES (:id, :job, :product, :subject, :milestone, :number, :rating, :raiser, :mentor, :reason, 'ANSWER',
				    :answerDays, :escalationDays, :now, :answerDue)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", job.productId())
			.param("subject", subject.name())
			.param("milestone", share ? targetId : null)
			.param("number", share ? job.milestoneNumber() : null)
			.param("rating", share ? null : targetId)
			.param("raiser", studentId)
			.param("mentor", job.mentorId())
			.param("reason", reason)
			.param("answerDays", answerDays)
			.param("escalationDays", escalationDays)
			.param("now", Timestamp.from(now))
			.param("answerDue", Timestamp.from(answerDue))
			.update();
		if (share) {
			this.holds.holdShares(targetId, id, studentId);
		}
		this.deadlines.schedule(new DeadlineRequest(TeamIssueAnswerDeadline.KEY, id, generation(id, Phase.ANSWER), jobId,
				answerDue));
		String name = this.students.cards(List.of(studentId))
			.stream()
			.findFirst()
			.map(card -> card.name() != null ? card.name() : "@" + card.githubLogin())
			.orElse("");
		String link = "/cong-viec/" + jobId;
		this.notifications.send(new NotificationRequest(RAISED, id, List.of(new Recipient(job.mentorId(), Audience.MENTOR)),
				Map.of("name", name, "job", job.title(), "date", DAY_MONTH.format(answerDue.atZone(VIETNAM))), link, jobId,
				null));
		this.todos.open(new TodoRequest(ANSWER_TODO, job.mentorId(), Audience.MENTOR, id, Map.of("name", name), link,
				answerDue, jobId));
		this.auditLog.record(AuditEntry.of(AuditActor.user(studentId), "dispute.team_issue_raised",
				AuditObject.of("dispute.team_issue", id)).forJob(jobId));
		return find(id).orElseThrow();
	}

	/** The Job's Team Issues, the newest first. */
	@Transactional(readOnly = true)
	List<TeamIssue> ofJob(UUID jobId) {
		return this.jdbc.sql("SELECT * FROM dispute.team_issue WHERE job_id = :job ORDER BY raised_at DESC, id")
			.param("job", jobId)
			.query(TeamIssues::issue)
			.list();
	}

	Optional<TeamIssue> find(UUID id) {
		return this.jdbc.sql("SELECT * FROM dispute.team_issue WHERE id = :id").param("id", id).query(TeamIssues::issue).optional();
	}

	/** The issue under its row lock; the caller took the Job row lock first (AD-4). */
	Optional<TeamIssue> lock(UUID id) {
		return this.jdbc.sql("SELECT * FROM dispute.team_issue WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(TeamIssues::issue)
			.optional();
	}

	private Optional<TeamIssue> openOf(UUID jobId, UUID raiserId) {
		return this.jdbc.sql("""
				SELECT * FROM dispute.team_issue WHERE job_id = :job AND raiser_account_id = :raiser AND phase <> 'ENDED'
				""").param("job", jobId).param("raiser", raiserId).query(TeamIssues::issue).optional();
	}

	/** A Team Issue Deadline's generation: the issue and the phase it serves (AD-7, AD-40). */
	static String generation(UUID id, Phase phase) {
		return id + ":" + phase.name();
	}

	private static TeamIssue issue(ResultSet rs, int n) throws SQLException {
		Timestamp escalation = rs.getTimestamp("escalation_due_at");
		Timestamp ended = rs.getTimestamp("ended_at");
		return new TeamIssue(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				Subject.valueOf(rs.getString("subject")), rs.getObject("milestone_id", UUID.class),
				rs.getObject("milestone_number", Integer.class), rs.getObject("rating_id", UUID.class),
				rs.getObject("raiser_account_id", UUID.class), rs.getObject("mentor_account_id", UUID.class),
				rs.getString("reason"), Phase.valueOf(rs.getString("phase")), rs.getTimestamp("raised_at").toInstant(),
				rs.getTimestamp("answer_due_at").toInstant(), escalation == null ? null : escalation.toInstant(),
				ended == null ? null : ended.toInstant());
	}

}
