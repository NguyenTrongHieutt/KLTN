package vn.vibefix.matching.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.CrewRecruitment;
import vn.vibefix.job.CrewRecruitment.ClosedBy;
import vn.vibefix.job.CrewRecruitment.Recruitment;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobRules;
import vn.vibefix.matching.internal.BidProofs.Item;
import vn.vibefix.matching.internal.SourceContributors.Contribution;
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
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Crew Applications, "Đơn xin vào nhóm" (FR-81, AD-3, AD-4, AD-7, AD-19, AD-43, R3, R5, R6, Story 21.4): a user with
 * GitHub linked applies from FND-2 to a Job whose Mentor recruits, with a note, Skills and at most five proof items
 * ({@link CrewApplicationProofs}), and no price or share. Refused, each with its plain reason, for a role already held
 * in the Job ({@code ROLE_TAKEN}: its Customer, its Mentor, a bidder still in play, a Crew member), a member who left
 * it ({@code CREW_REJOIN_REFUSED}), a Crew row not released on another Job ({@code ON_ANOTHER_CREW}, X8), a pending
 * application already ({@code ALREADY_APPLIED}), the daily limit, a person-link signal shared with the Customer or the
 * Mentor ({@code CONFLICT_OF_INTEREST}, which alerts every Administrator through {@code job}), a contribution to the
 * Source Repository, which {@link SourceContributors} reads before the transaction, or a recruitment closed or full. A
 * bidder not selected may apply (R3).
 * <p>
 * Sending runs a read-only precheck of the cheap refusals, then the contributor read outside any transaction, then in
 * one transaction {@code job.requireRecruiting} (which takes the Job row lock), the applicant's advisory lock, the
 * refusals again, and the insert (AD-4, A-03, A-14): no application commits after {@code CrewRecruitmentClosed}. The
 * Mentor is told of each application and keeps a to-do while any waits; the application lapses after the Job's copied
 * {@code CREW_APPLICATION_LAPSE_DAYS} ({@link CrewApplicationLapse}) or when recruitment closes, the applicant learning
 * why. An ended application and its proof are deleted 90 days after the decision ({@link CrewApplicationPurge}).
 */
@Component
class CrewApplications {

	/** Crew Applications per account and Vietnam day (AD-34, X2). */
	static final int DAILY_LIMIT = 10;

	/** Skills an application names at most (the Skill picker's limit on a Crew Application). */
	static final int MAX_SKILLS = 10;

	/** The lapse rule's name, copied onto the Job when it was published (FR-61). */
	static final String LAPSE_RULE = "CREW_APPLICATION_LAPSE_DAYS";

	/** The lapse days of a Job published before the rule existed (R6). */
	static final int DEFAULT_LAPSE_DAYS = 14;

	static final String RECEIVED = "matching.crew_application_received";

	static final String DECLINED_NOTICE = "matching.crew_application_declined";

	static final String LAPSED_NOTICE = "matching.crew_application_lapsed";

	static final String AUTO_WITHDRAWN = "matching.crew_application_auto_withdrawn";

	/** J4's to-do for the Mentor (UX-DR112): until recruitment turns on or a member has signed. */
	static final String OPEN_TODO = "matching.open_crew_recruitment";

	/** The Mentor's to-do while applications wait (UX-DR112): tier 2 while no member has signed, else 3. */
	static final String REVIEW_TODO = "matching.review_crew_applications";

	/** The states a Crew takes members in, from J4 until J8 (AD-41): any other Job is not found by a stranger (SG-31). */
	private static final Set<String> RECRUITS = Set.of("AGREEMENT", "STARTING", "ACTIVE");

	/** The one-role CHECK of {@code matching.crew_application} (AD-43). */
	private static final String NOT_A_ROLE_CHECK = "crew_application_not_a_role_check";

	/** What the open-bid trigger of {@code matching.crew_application} raises (AD-43). */
	private static final String BIDDER_TRIGGER = "an open or selected bidder never applies";

	enum Status {
		PENDING, DECLINED, WITHDRAWN, LAPSED
	}

	enum EndReason {

		/** "Không chọn". */
		DECLINED,

		/** The applicant's "Rút". */
		WITHDRAWN,

		/** A lock or an erasure withdrew it (AD-19). */
		APPLICANT_UNAVAILABLE,

		/** The Job's copied lapse days ran out (R6). */
		EXPIRED,

		/** The Mentor turned recruitment off. */
		RECRUITMENT_CLOSED,

		/** J8. */
		JOB_CLOSING,

		/** J10. */
		JOB_CANCELLED,

		/** An Administrator hid the Job Post (FR-60). */
		JOB_HIDDEN

	}

	/** What the applicant writes on FND-2. */
	record Draft(String note, List<UUID> skillIds) {

		Draft {
			skillIds = List.copyOf(skillIds);
		}

	}

	record CrewApplication(UUID id, UUID jobId, UUID productId, UUID customerId, UUID mentorId, UUID applicantId,
			Status status, String note, List<UUID> skillIds, List<String> skillNames, Instant submittedAt,
			Instant lapsesAt, @Nullable Instant endedAt, @Nullable EndReason endReason) {

		CrewApplication {
			skillIds = List.copyOf(skillIds);
			skillNames = List.copyOf(skillNames);
		}

	}

	private static final String COLUMNS = """
			id, job_id, product_id, customer_account_id, mentor_account_id, applicant_account_id, status, note, skill_ids,
			skill_names, submitted_at, lapses_at, ended_at, end_reason
			""";

	private final JdbcClient jdbc;

	private final CrewRecruitment recruitment;

	private final Crews crews;

	private final JobRules rules;

	private final PersonSignals signals;

	private final PublicProfiles profiles;

	private final Catalog catalog;

	private final CrewApplicationProofs proofs;

	private final Notifications notifications;

	private final Todos todos;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	CrewApplications(JdbcClient jdbc, CrewRecruitment recruitment, Crews crews, JobRules rules, PersonSignals signals,
			PublicProfiles profiles, Catalog catalog, CrewApplicationProofs proofs, Notifications notifications, Todos todos,
			Deadlines deadlines, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.recruitment = recruitment;
		this.crews = crews;
		this.rules = rules;
		this.signals = signals;
		this.profiles = profiles;
		this.catalog = catalog;
		this.proofs = proofs;
		this.notifications = notifications;
		this.todos = todos;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * The cheap refusals, read before the contributor read (A-03) so that an application refused anyway asks GitHub
	 * nothing; {@link #send} runs them again under the Job row lock and the applicant's lock. A shared person-link
	 * signal alerts the Administrators here, and {@link #send} finds it again only if it appeared since.
	 * @throws ApiException as {@link #send} does for every rule but the contributor's and the proof's
	 * @throws RateLimitedException after {@value #DAILY_LIMIT} applications today
	 */
	@Transactional(readOnly = true)
	void precheck(UUID applicantId, UUID jobId) {
		Recruitment found = this.recruitment.recruitment(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireVisible(applicantId, found);
		requireNoRole(applicantId, found);
		if (!found.recruiting() || found.hidden()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.RECRUITMENT_CLOSED);
		}
		if (!found.freeSlot()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.CREW_FULL, Map.of("n", found.cap()));
		}
		refuse(applicantId, found);
	}

	/**
	 * Where a proof upload for the applicant's application to the Job may go: a recruiting Job where they hold no role,
	 * never left, are on no other running Crew, and have no pending application.
	 * @throws ApiException {@code NOT_FOUND}, {@code GUARD_FAILED} with {@code ROLE_TAKEN}, {@code RECRUITMENT_CLOSED},
	 * {@code CREW_FULL}, {@code CREW_REJOIN_REFUSED} or {@code ON_ANOTHER_CREW}, or {@code CONFLICT ALREADY_APPLIED}
	 */
	@Transactional(readOnly = true)
	Recruitment requireApplicable(UUID applicantId, UUID jobId) {
		Recruitment found = this.recruitment.recruitment(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireVisible(applicantId, found);
		requireNoRole(applicantId, found);
		if (!found.recruiting() || found.hidden()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.RECRUITMENT_CLOSED);
		}
		if (!found.freeSlot()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.CREW_FULL, Map.of("n", found.cap()));
		}
		refuseMembership(applicantId, jobId);
		if (pending(jobId, applicantId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.ALREADY_APPLIED);
		}
		return found;
	}

	/**
	 * Sends the application with its proof (the caller checks the GitHub link, AD-19, N-5 and the proof's shape), in
	 * AD-4's order: {@code job.requireRecruiting} first (the Job row lock), then the applicant's lock and every rule
	 * again; the Mentor is told and the lapse is scheduled.
	 * @param contribution what {@link SourceContributors} read before this transaction (A-03), never read again here
	 * @throws ApiException {@code NOT_FOUND}; {@code GUARD_FAILED} with {@code RECRUITMENT_CLOSED}, {@code CREW_FULL},
	 * {@code ROLE_TAKEN}, {@code CREW_REJOIN_REFUSED}, {@code ON_ANOTHER_CREW}, {@code CONFLICT_OF_INTEREST},
	 * {@code APPLICANT_IS_CONTRIBUTOR} or {@code CONTRIBUTORS_UNKNOWN}; {@code CONFLICT ALREADY_APPLIED};
	 * {@code VALIDATION} with {@code SKILL_LIMIT}, {@code SKILL_INACTIVE} or {@code PROOF_FILE_UNKNOWN}
	 * @throws RateLimitedException after {@value #DAILY_LIMIT} applications today
	 */
	@Transactional
	CrewApplication send(UUID applicantId, UUID jobId, Draft draft, List<Item> proof, Contribution contribution) {
		Recruitment locked = this.recruitment.requireRecruiting(jobId);
		AdvisoryLocks.lockAccount(this.jdbc, applicantId);
		requireNoRole(applicantId, locked);
		refuse(applicantId, locked);
		if (contribution == Contribution.CONTRIBUTOR) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.APPLICANT_IS_CONTRIBUTOR);
		}
		if (contribution == Contribution.UNKNOWN) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.CONTRIBUTORS_UNKNOWN);
		}
		List<UUID> skillIds = List.copyOf(new LinkedHashSet<>(draft.skillIds()));
		this.catalog.requireAtMost(skillIds.size(), MAX_SKILLS);
		this.catalog.requireActive(skillIds);
		Map<UUID, String> names = new HashMap<>();
		this.catalog.byIds(skillIds).forEach(skill -> names.put(skill.id(), skill.name()));
		Instant now = this.clock.instant();
		int days = this.rules.value(jobId, LAPSE_RULE, DEFAULT_LAPSE_DAYS);
		Instant lapsesAt = now.plus(Duration.ofDays(days));
		UUID id = this.ids.newId();
		UUID mentorId = Objects.requireNonNull(locked.mentorId());
		try {
			this.jdbc.sql("""
					INSERT INTO matching.crew_application (id, job_id, product_id, customer_account_id, mentor_account_id,
					    applicant_account_id, status, note, skill_ids, skill_names, public_code_notice_version, submitted_at,
					    lapses_at)
					VALUES (:id, :job, :product, :customer, :mentor, :applicant, 'PENDING', :note, :skills, :names, :notice,
					    :now, :lapses)
					""")
				.param("id", id)
				.param("job", jobId)
				.param("product", locked.productId())
				.param("customer", locked.customerId())
				.param("mentor", mentorId)
				.param("applicant", applicantId)
				.param("note", draft.note().strip())
				.param("skills", skillIds.toArray(UUID[]::new))
				.param("names", skillIds.stream().map(names::get).toArray(String[]::new))
				.param("notice", Applications.PUBLIC_CODE_NOTICE_VERSION)
				.param("now", Timestamp.from(now))
				.param("lapses", Timestamp.from(lapsesAt))
				.update();
		}
		catch (DuplicateKeyException pending) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.ALREADY_APPLIED);
		}
		catch (DataIntegrityViolationException backstop) {
			// The one-role CHECK or the open-bid trigger (AD-43): the rules above refuse first. Anything else is no refusal.
			String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(backstop).getMessage());
			if (cause.contains(NOT_A_ROLE_CHECK) || cause.contains(BIDDER_TRIGGER)) {
				throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.ROLE_TAKEN);
			}
			throw backstop;
		}
		this.proofs.attach(id, jobId, locked.productId(), applicantId, proof);
		this.deadlines.schedule(new DeadlineRequest(CrewApplicationLapse.KEY, id, id.toString(), jobId, lapsesAt));
		this.auditLog.record(AuditEntry.of(AuditActor.user(applicantId), "matching.crew_application_sent",
				AuditObject.of("matching.crew_application", id)).forJob(jobId));
		this.notifications.send(new NotificationRequest(RECEIVED, id, List.of(new Recipient(mentorId, Audience.MENTOR)),
				Map.of("name", name(applicantId), "job", locked.title()), recruitmentLink(jobId), jobId, null));
		refreshReviewTodo(locked);
		return byId(id).orElseThrow();
	}

	/**
	 * "Rút" (FR-81): the applicant withdraws their own pending application; the Mentor's to-do counts one fewer.
	 * @throws ApiException {@code NOT_FOUND} for another's; {@code CONFLICT NOT_WITHDRAWABLE} once it ended
	 */
	@Transactional
	CrewApplication withdraw(UUID applicantId, UUID applicationId) {
		CrewApplication application = this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM matching.crew_application WHERE id = :id AND applicant_account_id = :applicant FOR UPDATE")
			.param("id", applicationId)
			.param("applicant", applicantId)
			.query(CrewApplications::application)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (application.status() != Status.PENDING || !end(applicationId, Status.WITHDRAWN, EndReason.WITHDRAWN)) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_WITHDRAWABLE);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(applicantId), "matching.crew_application_withdrawn",
				AuditObject.of("matching.crew_application", applicationId)).forJob(application.jobId()));
		this.recruitment.recruitment(application.jobId()).ifPresent(this::refreshReviewTodo);
		return byId(applicationId).orElseThrow();
	}

	/**
	 * "Không chọn" (FR-81, UX-DR102): the Job's Mentor declines a pending application, asking no reason; the applicant
	 * learns only that they were not picked. The caller checked that the Mentor calls.
	 * @throws ApiException {@code NOT_FOUND} for an application of another Job; {@code CONFLICT NOT_DECLINABLE} once it
	 * ended
	 */
	@Transactional
	void decline(UUID mentorId, UUID jobId, UUID applicationId) {
		CrewApplication application = this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM matching.crew_application WHERE id = :id AND job_id = :job FOR UPDATE")
			.param("id", applicationId)
			.param("job", jobId)
			.query(CrewApplications::application)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (application.status() != Status.PENDING || !end(applicationId, Status.DECLINED, EndReason.DECLINED)) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.NOT_DECLINABLE);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "matching.crew_application_declined",
				AuditObject.of("matching.crew_application", applicationId)).forJob(jobId));
		Optional<Recruitment> found = this.recruitment.recruitment(jobId);
		String title = found.map(Recruitment::title).orElse("");
		this.notifications.send(new NotificationRequest(DECLINED_NOTICE, applicationId,
				List.of(new Recipient(application.applicantId(), Audience.SUBJECT)), Map.of("job", title), APPLICANT_LINK,
				null, applicationId));
		found.ifPresent(this::refreshReviewTodo);
	}

	/**
	 * The lapse Deadline (R6, AD-7): a pending application lapses once its days run out, and its applicant learns why.
	 * The row is locked first, so a decline or a withdrawal that committed meanwhile wins and nothing more is sent.
	 * @return whether it lapsed: {@code false} for one that already ended
	 */
	@Transactional
	boolean lapse(UUID applicationId) {
		CrewApplication application = this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM matching.crew_application WHERE id = :id FOR UPDATE")
			.param("id", applicationId)
			.query(CrewApplications::application)
			.optional()
			.orElse(null);
		if (application == null || application.status() != Status.PENDING) {
			return false;
		}
		return lapse(List.of(application), EndReason.EXPIRED) > 0;
	}

	/**
	 * {@code CrewRecruitmentClosed} (AD-7, AD-41, A-04): every application of the Job still pending that was sent by
	 * the time recruitment closed lapses at once, each applicant told which closing ended it; one sent after a quick
	 * reopening stays. Lapsing them again, or a replayed event, changes nothing.
	 */
	@Transactional
	void lapseForJob(UUID jobId, ClosedBy closedBy, Instant closedAt) {
		List<CrewApplication> open = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.crew_application WHERE job_id = :job AND status = 'PENDING' AND submitted_at <= :closed
				ORDER BY submitted_at, id FOR UPDATE
				""").param("job", jobId).param("closed", Timestamp.from(closedAt)).query(CrewApplications::application).list();
		lapse(open, switch (closedBy) {
			case MENTOR -> EndReason.RECRUITMENT_CLOSED;
			case CLOSING -> EndReason.JOB_CLOSING;
			case CANCELLED -> EndReason.JOB_CANCELLED;
		});
	}

	/** Lapses each application still pending, telling its applicant why; returns how many lapsed. */
	private int lapse(List<CrewApplication> applications, EndReason reason) {
		if (applications.isEmpty()) {
			return 0;
		}
		int lapsed = 0;
		for (CrewApplication application : applications) {
			if (!end(application.id(), Status.LAPSED, reason)) {
				continue;
			}
			lapsed++;
			String title = this.recruitment.recruitment(application.jobId()).map(Recruitment::title).orElse("");
			this.notifications.send(new NotificationRequest(LAPSED_NOTICE, application.id(),
					List.of(new Recipient(application.applicantId(), Audience.SUBJECT)),
					Map.of("job", title, "cause", reason.name()), APPLICANT_LINK, null, application.id()));
		}
		applications.stream()
			.map(CrewApplication::jobId)
			.distinct()
			.forEach(jobId -> this.recruitment.recruitment(jobId).ifPresent(this::refreshReviewTodo));
		return lapsed;
	}

	/**
	 * The account's pending applications end (FR-81, AD-19, Story 20.4): on a lock, and before an erasure deletes them,
	 * each is withdrawn with {@code APPLICANT_UNAVAILABLE} and its Job's Mentor learns it, never why. An account with
	 * none changes nothing, and withdrawing them again changes nothing.
	 */
	@Transactional
	void withdrawForAccount(UUID accountId) {
		List<CrewApplication> open = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.crew_application WHERE applicant_account_id = :id AND status = 'PENDING'
				ORDER BY submitted_at, id FOR UPDATE
				""").param("id", accountId).query(CrewApplications::application).list();
		if (open.isEmpty()) {
			return;
		}
		String name = name(accountId);
		for (CrewApplication application : open) {
			if (!end(application.id(), Status.WITHDRAWN, EndReason.APPLICANT_UNAVAILABLE)) {
				continue;
			}
			this.recruitment.recruitment(application.jobId()).ifPresent(found -> {
				this.notifications.send(new NotificationRequest(AUTO_WITHDRAWN, application.id(),
						List.of(new Recipient(application.mentorId(), Audience.MENTOR)),
						Map.of("name", name, "job", found.title()), recruitmentLink(application.jobId()), application.jobId(),
						null));
				refreshReviewTodo(found);
			});
		}
	}

	/**
	 * FR-60: an Administrator hid the Job Post, so its pending applications lapse ({@code JOB_HIDDEN}), each applicant
	 * told through {@code matching.crew_application_lapsed}; while hidden, the Job takes none.
	 */
	@Transactional
	void postHidden(UUID jobId) {
		List<CrewApplication> open = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.crew_application WHERE job_id = :job AND status = 'PENDING' ORDER BY submitted_at, id FOR UPDATE
				""").param("job", jobId).query(CrewApplications::application).list();
		lapse(open, EndReason.JOB_HIDDEN);
	}

	/**
	 * J4 (UX-DR112): the Mentor's "Mở tuyển thành viên nhóm cho {job}", due at the Agreement deadline, unless the
	 * recruitment is already on or a member has signed (the listener may run after the Mentor turned it on).
	 */
	@Transactional
	void openRecruitmentTodo(UUID jobId, UUID mentorId, @Nullable Instant agreementDueAt) {
		this.recruitment.recruitment(jobId)
			.filter(found -> RECRUITS.contains(found.state()) && !found.on() && !found.signedMember())
			.ifPresent(found ->
				this.todos.open(new TodoRequest(OPEN_TODO, mentorId, Audience.MENTOR, jobId, Map.of("job", found.title()),
						recruitmentLink(jobId), agreementDueAt, jobId)));
	}

	/** Recruitment turned on, or the Job entered Closing or was cancelled (J8, J10): the J4 to-do closes. */
	@Transactional
	void closeRecruitmentTodo(UUID jobId) {
		this.todos.closeAll(OPEN_TODO, jobId);
	}

	@Transactional(readOnly = true)
	Optional<CrewApplication> byId(UUID applicationId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM matching.crew_application WHERE id = :id")
			.param("id", applicationId)
			.query(CrewApplications::application)
			.optional();
	}

	/** The applicant's own applications, newest first (MEN-3, CUS-7). */
	@Transactional(readOnly = true)
	List<CrewApplication> ofApplicant(UUID applicantId) {
		return this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM matching.crew_application WHERE applicant_account_id = :id ORDER BY submitted_at DESC, id")
			.param("id", applicantId)
			.query(CrewApplications::application)
			.list();
	}

	/**
	 * The Job's applications as JOB-9 lists them (UX-DR102): the pending ones oldest first, never reordered, then the
	 * declined and lapsed ones ("Đơn đã đóng"); a withdrawn one is the applicant's alone.
	 */
	@Transactional(readOnly = true)
	List<CrewApplication> ofJob(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.crew_application WHERE job_id = :job AND status <> 'WITHDRAWN'
				ORDER BY status <> 'PENDING', submitted_at, id
				""").param("job", jobId).query(CrewApplications::application).list();
	}

	/** The applicant's pending application to the Job, if any. */
	@Transactional(readOnly = true)
	Optional<CrewApplication> pending(UUID jobId, UUID applicantId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM matching.crew_application WHERE job_id = :job AND applicant_account_id = :applicant AND status = 'PENDING'
				""").param("job", jobId).param("applicant", applicantId).query(CrewApplications::application).optional();
	}

	/**
	 * One role per Job (AD-43): the Job's Customer or Mentor, a bidder whose bid is still in play, or a Crew member of
	 * the Job never applies to its Crew.
	 * @throws ApiException {@code GUARD_FAILED ROLE_TAKEN}
	 */
	private void requireNoRole(UUID applicantId, Recruitment found) {
		if (applicantId.equals(found.customerId()) || applicantId.equals(found.mentorId())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.ROLE_TAKEN);
		}
		Relationship crew = this.crews.crewRelationship(found.jobId(), applicantId).kind();
		if (crew == Relationship.ASSIGNED || crew == Relationship.ACTIVE) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.ROLE_TAKEN);
		}
		boolean bidding = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM matching.application WHERE job_id = :job AND mentor_account_id = :applicant
				    AND status IN ('SUBMITTED', 'ON_HOLD', 'SELECTED'))
				""").param("job", found.jobId()).param("applicant", applicantId).query(Boolean.class).single();
		if (bidding) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.ROLE_TAKEN);
		}
	}

	/**
	 * SG-31: a Job that is not in Agreement, Starting or Active takes no Crew Application, and a caller who holds no role
	 * in it does not learn that it exists.
	 * @throws ApiException {@code NOT_FOUND}
	 */
	private void requireVisible(UUID applicantId, Recruitment found) {
		if (RECRUITS.contains(found.state())) {
			return;
		}
		boolean role = applicantId.equals(found.customerId()) || applicantId.equals(found.mentorId())
				|| this.crews.crewRelationship(found.jobId(), applicantId).kind() != Relationship.NONE;
		if (!role) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	/** A member who left the Job never rejoins it (R3), and one on another running Crew is busy (X8). */
	private void refuseMembership(UUID applicantId, UUID jobId) {
		if (this.crews.crewRelationship(jobId, applicantId).kind() == Relationship.FORMER) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.CREW_REJOIN_REFUSED);
		}
		if (this.crews.runningCrewOf(applicantId).filter(other -> !other.equals(jobId)).isPresent()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.ON_ANOTHER_CREW);
		}
	}

	/** Every rule after the role and the recruitment, in FR-81's order. */
	private void refuse(UUID applicantId, Recruitment found) {
		UUID jobId = found.jobId();
		refuseMembership(applicantId, jobId);
		if (pending(jobId, applicantId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, MatchingReason.ALREADY_APPLIED);
		}
		Instant now = this.clock.instant();
		Instant dayStart = LocalDate.ofInstant(now, Applications.VIETNAM).atStartOfDay(Applications.VIETNAM).toInstant();
		long today = this.jdbc.sql("""
				SELECT count(*) FROM matching.crew_application WHERE applicant_account_id = :id AND submitted_at >= :since
				""").param("id", applicantId).param("since", Timestamp.from(dayStart)).query(Long.class).single();
		if (today >= DAILY_LIMIT) {
			throw new RateLimitedException(Duration.between(now, dayStart.plus(Duration.ofDays(1))));
		}
		UUID mentorId = found.mentorId();
		if (this.signals.sharesPersonSignal(applicantId, found.customerId())
				|| (mentorId != null && this.signals.sharesPersonSignal(applicantId, mentorId))) {
			this.recruitment.conflictRefused();
			throw new ApiException(ErrorCode.GUARD_FAILED, MatchingReason.CONFLICT_OF_INTEREST);
		}
	}

	/**
	 * "Xem {n} đơn xin vào nhóm cho {job}" while any application waits (UX-DR112): tier 2 while no member has signed,
	 * else 3.
	 */
	private void refreshReviewTodo(Recruitment found) {
		this.todos.closeAll(REVIEW_TODO, found.jobId());
		if (found.mentorId() == null) {
			return;
		}
		int open = this.jdbc.sql("SELECT count(*) FROM matching.crew_application WHERE job_id = :job AND status = 'PENDING'")
			.param("job", found.jobId())
			.query(Integer.class)
			.single();
		if (open > 0) {
			this.todos.open(new TodoRequest(REVIEW_TODO, found.mentorId(), Audience.MENTOR, found.jobId(),
					Map.of("n", String.valueOf(open), "job", found.title()), recruitmentLink(found.jobId()), null,
					found.jobId(), found.signedMember() ? 3 : 2));
		}
	}

	/**
	 * Ends the application if it is still pending: it and its proof are due for deletion {@link BidProofs#RETENTION}
	 * later (X3).
	 * @return whether it ended now: {@code false} for one that had already ended
	 */
	private boolean end(UUID applicationId, Status status, EndReason reason) {
		Instant now = this.clock.instant();
		return this.jdbc.sql("""
				UPDATE matching.crew_application SET status = :status, ended_at = :now, end_reason = :reason, purge_due_at = :due
				WHERE id = :id AND status = 'PENDING'
				""")
			.param("id", applicationId)
			.param("status", status.name())
			.param("reason", reason.name())
			.param("now", Timestamp.from(now))
			.param("due", Timestamp.from(now.plus(BidProofs.RETENTION)))
			.update() > 0;
	}

	/** The applicant's name as the Mentor's notices show it: the one name rule (Story 19.3). */
	private String name(UUID accountId) {
		return Optional.ofNullable(this.profiles.factsOf(List.of(accountId)).get(accountId))
			.map(PersonFacts::displayName)
			.orElse("");
	}

	/** Where an applicant's notices lead: their applications on CUS-7 "Việc tôi tham gia". */
	static final String APPLICANT_LINK = "/cong-viec?vai-tro=tham-gia";

	static String recruitmentLink(UUID jobId) {
		return "/cong-viec/" + jobId + "/tuyen-thanh-vien";
	}

	/** The Skills sought by ID, as the catalog names them now. */
	Map<UUID, Skill> skills(List<UUID> skillIds) {
		Map<UUID, Skill> skills = new HashMap<>();
		this.catalog.byIds(skillIds).forEach(skill -> skills.put(skill.id(), skill));
		return skills;
	}

	private static CrewApplication application(ResultSet rs, int n) throws SQLException {
		String reason = rs.getString("end_reason");
		Timestamp ended = rs.getTimestamp("ended_at");
		return new CrewApplication(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("customer_account_id", UUID.class),
				rs.getObject("mentor_account_id", UUID.class), rs.getObject("applicant_account_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getString("note"), uuids(rs.getArray("skill_ids")),
				Arrays.asList((String[]) rs.getArray("skill_names").getArray()), rs.getTimestamp("submitted_at").toInstant(),
				rs.getTimestamp("lapses_at").toInstant(), ended == null ? null : ended.toInstant(),
				reason == null ? null : EndReason.valueOf(reason));
	}

	private static List<UUID> uuids(Array array) throws SQLException {
		return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
	}

}
