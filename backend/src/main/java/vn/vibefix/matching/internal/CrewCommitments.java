package vn.vibefix.matching.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import vn.vibefix.identity.Notices;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.CrewEmptied;
import vn.vibefix.job.CrewPicks;
import vn.vibefix.job.CrewPicks.Assignment;
import vn.vibefix.job.CrewPicks.Pick;
import vn.vibefix.job.CrewPicks.PickEnd;
import vn.vibefix.job.CrewRecruitment;
import vn.vibefix.job.CrewRecruitment.Recruitment;
import vn.vibefix.matching.internal.CrewApplications.CrewApplication;
import vn.vibefix.matching.internal.CrewApplications.EndReason;
import vn.vibefix.matching.internal.CrewApplications.Status;
import vn.vibefix.matching.internal.SourceContributors.Contribution;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Picks and the commitment (FR-81, AD-3, AD-4, AD-7, AD-41, R4, Story 21.5). The Mentor picks a pending Crew
 * Application: in one transaction {@code job.addCrew} takes the Job row lock first and inserts the {@code ASSIGNED}
 * row, and only then is the application locked and {@code PICKED}, with the 3-day signature Deadline
 * ({@link CommitmentDue}); the person hears it and gets the {@value #SIGN_TODO} to-do. The picked person reads the
 * Commitment panel on MEN-3 and signs ("Ký và tham gia", {@link CrewSignatures}). A pick not yet signed ends when the person declines, the period passes, the Mentor
 * withdraws it ("Rút lựa chọn"), the person is locked, or J8 or J10 ends it; {@code job.releasePick} releases its row.
 * The Source Repository's contributors are read before each transaction and passed in (A-03).
 */
@Component
class CrewCommitments {

	/** The commitment text's version; its legal wording waits for OQ5, and {@code vi.json} shows the same version. */
	static final String COMMITMENT_VERSION = "1.0";

	/** The signature period after a pick (FR-81, AD-7): not pausable. */
	static final Duration SIGN_PERIOD = Duration.ofDays(3);

	static final String PICKED = "matching.crew_application_picked";

	static final String PICK_WITHDRAWN = "matching.crew_pick_withdrawn";

	static final String PICK_ENDED = "matching.crew_pick_ended";

	static final String DECLINED = "matching.commitment_declined";

	static final String LAPSED = "matching.commitment_lapsed";

	/** The picked person's to-do (UX-DR112): until they sign or decline, or the pick ends. */
	static final String SIGN_TODO = "matching.sign_commitment";

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private final JdbcClient jdbc;

	private final CrewApplications applications;

	private final CrewPicks picks;

	private final CrewRecruitment recruitment;

	private final PublicProfiles profiles;

	private final Notices notices;

	private final Notifications notifications;

	private final Todos todos;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final Clock clock;

	CrewCommitments(JdbcClient jdbc, CrewApplications applications, CrewPicks picks, CrewRecruitment recruitment,
			PublicProfiles profiles, Notices notices, Notifications notifications, Todos todos, Deadlines deadlines,
			AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.applications = applications;
		this.picks = picks;
		this.recruitment = recruitment;
		this.profiles = profiles;
		this.notices = notices;
		this.notifications = notifications;
		this.todos = todos;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	// ---- The Mentor ---------------------------------------------------------------------------

	/**
	 * The pending application the Mentor's "Chọn" names, read before the contributor read so a pick refused anyway asks
	 * GitHub nothing. The caller checked that the Mentor calls.
	 * @throws ApiException {@code NOT_FOUND} for an application of another Job; {@code CONFLICT NOT_PICKABLE} once it
	 * is no longer pending
	 */
	@Transactional(readOnly = true)
	CrewApplication requirePending(UUID jobId, UUID applicationId) {
		CrewApplication application = this.applications.byId(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (application.status() != Status.PENDING) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_PICKABLE);
		}
		return application;
	}

	/**
	 * "Chọn" (FR-81, AD-3, AD-4): {@code job.addCrew} first, under the Job row lock, then the application, locked, is
	 * {@code PICKED} with its 3-day signature period; the person hears it and gets the signature to-do.
	 * @param contribution what {@link SourceContributors} read before this transaction (A-03)
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT NOT_PICKABLE} for an application that ended meanwhile;
	 * and {@code job.addCrew}'s refusals
	 */
	@Transactional
	CrewApplication pick(UUID mentorId, UUID jobId, UUID applicationId, Contribution contribution) {
		CrewApplication before = this.applications.byId(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Pick pick = this.picks.addCrew(mentorId, jobId, applicationId, before.applicantId(), job(contribution));
		CrewApplication application = this.applications.lock(applicationId).orElseThrow();
		if (application.status() != Status.PENDING) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_PICKABLE);
		}
		Instant now = this.clock.instant();
		Instant due = now.plus(SIGN_PERIOD);
		this.jdbc.sql("""
				UPDATE matching.crew_application SET status = 'PICKED', picked_at = :now, crew_row_id = :row, sign_due_at = :due
				WHERE id = :id AND status = 'PENDING'
				""")
			.param("id", applicationId)
			.param("now", Timestamp.from(now))
			.param("row", pick.rowId())
			.param("due", Timestamp.from(due))
			.update();
		this.deadlines.schedule(new DeadlineRequest(CommitmentDue.KEY, applicationId, applicationId.toString(), jobId, due));
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "matching.crew_application_picked",
				AuditObject.of("matching.crew_application", applicationId)).forJob(jobId));
		UUID applicant = application.applicantId();
		this.notifications.send(new NotificationRequest(PICKED, applicationId,
				List.of(new Recipient(applicant, Audience.CREW_ASSIGNED)),
				Map.of("mentor", this.applications.name(mentorId), "job", pick.title(), "date", dayMonth(due)),
				commitmentLink(jobId), jobId, null));
		this.todos.open(new TodoRequest(SIGN_TODO, applicant, Audience.CREW_ASSIGNED, applicationId,
				Map.of("job", pick.title()), commitmentLink(jobId), due, jobId));
		refreshReview(jobId);
		return this.applications.byId(applicationId).orElseThrow();
	}

	/**
	 * "Rút lựa chọn" (R4): {@code job.releasePick} first, under the Job row lock, then the application ends as
	 * {@code WITHDRAWN}; the person hears it and is free again. The caller checked that the Mentor calls.
	 * @throws ApiException {@code NOT_FOUND} for an application of another Job; {@code CONFLICT NOT_WITHDRAWABLE} for
	 * one that is not a pick waiting for its signature
	 */
	@Transactional
	void withdrawPick(UUID mentorId, UUID jobId, UUID applicationId) {
		CrewApplication before = this.applications.byId(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (before.status() != Status.PICKED || before.crewRowId() == null) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_WITHDRAWABLE);
		}
		if (!this.picks.releasePick(jobId, before.crewRowId(), PickEnd.WITHDRAWN)) {
			// Signed, or released by J8 or a lock whose listener has not run yet, which ends the application itself.
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_WITHDRAWABLE);
		}
		CrewApplication application = this.applications.lock(applicationId).orElseThrow();
		if (application.status() != Status.PICKED
				|| !this.applications.endPick(applicationId, Status.WITHDRAWN, EndReason.PICK_WITHDRAWN)) {
			// The person signed first (the Job row lock let their signature through): nobody who signed is removed.
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_WITHDRAWABLE);
		}
		this.todos.closeAll(SIGN_TODO, applicationId);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "matching.crew_pick_withdrawn",
				AuditObject.of("matching.crew_application", applicationId)).forJob(jobId));
		this.notifications.send(new NotificationRequest(PICK_WITHDRAWN, applicationId,
				List.of(new Recipient(application.applicantId(), Audience.SUBJECT)), Map.of("job", title(jobId)),
				commitmentLink(jobId), null, applicationId));
		refreshReview(jobId);
		reopenRecruitmentTodo(jobId);
	}

	// ---- The picked person --------------------------------------------------------------------

	/**
	 * The person's pick on the Job waiting for the signature, read before the contributor read.
	 * @throws ApiException {@code NOT_FOUND} without one; {@code GUARD_FAILED PICK_ENDED} for a pick that ended
	 */
	@Transactional(readOnly = true)
	CrewApplication requirePicked(UUID accountId, UUID jobId) {
		CrewApplication pick = this.applications.pickOf(jobId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (pick.status() != Status.PICKED) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.PICK_ENDED);
		}
		return pick;
	}

	/**
	 * "Từ chối" (R4): {@code job.releasePick} first, under the Job row lock, then the pick ends as {@code DECLINED} and
	 * the Mentor hears it.
	 * @throws ApiException {@code NOT_FOUND} without a pick; {@code GUARD_FAILED PICK_ENDED} for one that ended
	 */
	@Transactional
	void decline(UUID accountId, UUID jobId) {
		CrewApplication pick = requirePicked(accountId, jobId);
		if (!this.picks.releasePick(jobId, pick.crewRowId(), PickEnd.DECLINED)) {
			// Signed, or released by J8 or a lock whose listener has not run yet, which ends the application itself.
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.PICK_ENDED);
		}
		CrewApplication application = this.applications.lock(pick.id()).orElseThrow();
		if (application.status() != Status.PICKED
				|| !this.applications.endPick(pick.id(), Status.DECLINED, EndReason.COMMITMENT_DECLINED)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.PICK_ENDED);
		}
		this.todos.closeAll(SIGN_TODO, pick.id());
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "matching.commitment_declined",
				AuditObject.of("matching.crew_application", pick.id())).forJob(jobId));
		this.notifications.send(new NotificationRequest(DECLINED, pick.id(),
				List.of(new Recipient(application.mentorId(), Audience.MENTOR)),
				Map.of("name", this.applications.name(accountId), "job", title(jobId)),
				CrewApplications.recruitmentLink(jobId), jobId, null));
		refreshReview(jobId);
		reopenRecruitmentTodo(jobId);
	}

	/**
	 * The signature Deadline (AD-7, R4): a pick still waiting when its 3 days pass is released as {@code DECLINED}, and
	 * the Mentor and the person hear it. {@code job.releasePick} takes the Job row lock first, so a signature that
	 * committed meanwhile wins and nothing more is sent.
	 * @return whether it lapsed: {@code false} for a pick that was signed or ended
	 */
	@Transactional
	boolean lapse(UUID applicationId) {
		CrewApplication pick = this.applications.byId(applicationId).orElse(null);
		if (pick == null || pick.status() != Status.PICKED || pick.crewRowId() == null) {
			return false;
		}
		if (!this.picks.releasePick(pick.jobId(), pick.crewRowId(), PickEnd.DECLINED)) {
			// Signed, or released by J8 or a lock whose listener has not run yet, which ends the application itself.
			return false;
		}
		CrewApplication application = this.applications.lock(applicationId).orElseThrow();
		if (application.status() != Status.PICKED
				|| !this.applications.endPick(applicationId, Status.LAPSED, EndReason.COMMITMENT_LAPSED)) {
			return false;
		}
		this.todos.closeAll(SIGN_TODO, applicationId);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "matching.commitment_lapsed",
				AuditObject.of("matching.crew_application", applicationId)).forJob(pick.jobId()));
		Map<String, String> params = Map.of("name", this.applications.name(application.applicantId()), "job", title(pick.jobId()));
		// One request per reader: JOB-9 is the Mentor's, and the person reads why on MEN-3.
		this.notifications.send(new NotificationRequest(LAPSED, applicationId,
				List.of(new Recipient(application.mentorId(), Audience.MENTOR)), params,
				CrewApplications.recruitmentLink(pick.jobId()), pick.jobId(), null));
		this.notifications.send(new NotificationRequest(LAPSED, applicationId,
				List.of(new Recipient(application.applicantId(), Audience.SUBJECT)), params, commitmentLink(pick.jobId()), null,
				applicationId));
		refreshReview(pick.jobId());
		reopenRecruitmentTodo(pick.jobId());
		return true;
	}

	// ---- Other modules' facts -----------------------------------------------------------------

	/**
	 * J8 or J10 (AD-41, A-04): {@code job} released every pick not yet signed in the transition's transaction; each
	 * such application ends ({@code PICK_ENDED}) and its person hears {@value #PICK_ENDED}. Replaying it changes nothing.
	 */
	@Transactional
	void endPicksForJob(UUID jobId) {
		List<CrewApplication> picked = this.jdbc.sql("SELECT " + CrewApplications.COLUMNS + """
				 FROM matching.crew_application WHERE job_id = :job AND status = 'PICKED' ORDER BY picked_at, id FOR UPDATE
				""").param("job", jobId).query(CrewApplications::application).list();
		if (picked.isEmpty()) {
			return;
		}
		String title = title(jobId);
		for (CrewApplication application : picked) {
			if (!this.applications.endPick(application.id(), Status.LAPSED, EndReason.PICK_ENDED)) {
				continue;
			}
			this.todos.closeAll(SIGN_TODO, application.id());
			this.auditLog.record(AuditEntry.of(AuditActor.system(), PICK_ENDED,
					AuditObject.of("matching.crew_application", application.id())).forJob(jobId));
			this.notifications.send(new NotificationRequest(PICK_ENDED, application.id(),
					List.of(new Recipient(application.applicantId(), Audience.SUBJECT)), Map.of("job", title),
					commitmentLink(jobId), null, application.id()));
		}
		refreshReview(jobId);
	}

	/**
	 * A person signed and joined a Crew (A-16, UX-DR112): their pending applications to other Jobs lapse, the Job's
	 * open-recruitment to-do closes (a member has signed), and the review to-do moves to its tier for a Crew with a
	 * member. Replaying it changes nothing.
	 */
	@Transactional
	void joined(UUID jobId, UUID accountId) {
		this.applications.lapseElsewhere(accountId, jobId);
		this.applications.closeRecruitmentTodo(jobId);
		refreshReview(jobId);
	}

	/**
	 * A lock emptied the Crew (R7, Story 21.5): while the Job is in Agreement, confirmed or not, the Mentor is asked again
	 * to open recruitment, unless it is on, and the review to-do returns to its tier for a Crew with no member.
	 */
	@Transactional
	void crewEmptied(CrewEmptied event) {
		if ("AGREEMENT".equals(event.state())) {
			this.applications.openRecruitmentTodo(event.jobId(), event.mentorId(), event.agreementDueAt());
		}
		refreshReview(event.jobId());
	}

	// ---- Reads --------------------------------------------------------------------------------

	/** The person's latest pick on the Job, whatever became of it: the Commitment panel and its ended states. */
	@Transactional(readOnly = true)
	Optional<CrewApplication> pickOf(UUID accountId, UUID jobId) {
		return this.applications.pickOf(jobId, accountId);
	}

	/** The assignment projection while the pick waits for the signature (AD-41); empty otherwise. */
	@Transactional(readOnly = true)
	Optional<Assignment> assignment(UUID accountId, UUID jobId) {
		return this.picks.assignment(jobId, accountId);
	}

	/** The account's name and GitHub username, as N-19 and the panel name them. */
	@Nullable PersonFacts person(UUID accountId) {
		return this.profiles.factsOf(List.of(accountId)).get(accountId);
	}

	/** Whether the account acknowledged N-23's current version (FR-18). */
	boolean commitEmailAcknowledged(UUID accountId) {
		return this.notices.acknowledged(accountId, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION);
	}

	/** Where a picked person signs: MEN-3. */
	static String commitmentLink(UUID jobId) {
		return "/tim-viec/" + jobId;
	}

	/** The Mentor's review to-do, its count and tier as the Job now stands (UX-DR112). */
	void refreshReview(UUID jobId) {
		this.recruitment.recruitment(jobId).ifPresent(this.applications::refreshReviewTodo);
	}

	/**
	 * R7: an unsigned pick ended on a Job in Agreement with recruitment off (picks filled the places), nobody signed and
	 * no row left: nothing else asks the Mentor for a Crew while the Agreement deadline runs.
	 */
	private void reopenRecruitmentTodo(UUID jobId) {
		this.recruitment.recruitment(jobId)
			.filter(found -> "AGREEMENT".equals(found.state()) && found.unreleased() == 0)
			.ifPresent(found -> this.applications.openRecruitmentTodo(jobId, Objects.requireNonNull(found.mentorId()),
					found.agreementDueAt()));
	}

	private String title(UUID jobId) {
		return this.recruitment.recruitment(jobId).map(Recruitment::title).orElse("");
	}

	private static String dayMonth(Instant at) {
		return DAY_MONTH.format(at.atZone(Applications.VIETNAM));
	}

	static CrewPicks.Contribution job(Contribution contribution) {
		return switch (contribution) {
			case NONE -> CrewPicks.Contribution.NONE;
			case CONTRIBUTOR -> CrewPicks.Contribution.CONTRIBUTOR;
			case UNKNOWN -> CrewPicks.Contribution.UNKNOWN;
		};
	}

}
