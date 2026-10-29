package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.CrewMemberAdded;
import vn.vibefix.job.CrewPicks;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link CrewPicks}, the pick, its end and the signature (FR-75, FR-81, AD-3, AD-4, AD-41, AD-43, A-03, A-04, A-16,
 * Story 21.5). Each command takes the Job row lock first, in the caller's transaction, and checks its refusals under it
 * in the spec's order; the contributor read's answer comes in from before the transaction. A pick takes a recruitment
 * place ({@link JobRecruitment#takeSlot}) and an unsigned pick that ends gives it back; a signature activates the row,
 * publishes {@link CrewMemberAdded}, and tells the Mentor and the active members the name, and the Customer the GitHub
 * username only (R15). The Mentor never learns why a person is unavailable (A-16): another running Crew and a lock read
 * the same.
 */
@Component
class JobCrewPicks implements CrewPicks {

	static final String JOINED = "job.crew_member_joined";

	static final String LOGIN_ADDED = "job.crew_login_added";

	/** The states a Crew takes members in, from J4 until J8 (AD-41). */
	private static final Set<JobState> RECRUITS = Set.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE);

	/** What the one-role trigger of {@code job.crew_row} raises (AD-43). */
	private static final String ONE_ROLE_TRIGGER = "one role per Job";

	/** What the no-rejoin trigger of {@code job.crew_row} raises (R3). */
	private static final String REJOIN_TRIGGER = "no rejoining";

	private final CrewService crews;

	private final JobRecruitment recruitment;

	private final Agreements agreements;

	private final AgreementReviews reviews;

	private final PersonSignals signals;

	private final CrewAlerts alerts;

	private final PublicProfiles profiles;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	JobCrewPicks(CrewService crews, JobRecruitment recruitment, Agreements agreements, AgreementReviews reviews,
			PersonSignals signals, CrewAlerts alerts, PublicProfiles profiles, Notifications notifications, AuditLog auditLog,
			ApplicationEventPublisher events, IdGenerator ids) {
		this.crews = crews;
		this.recruitment = recruitment;
		this.agreements = agreements;
		this.reviews = reviews;
		this.signals = signals;
		this.alerts = alerts;
		this.profiles = profiles;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.events = events;
		this.ids = ids;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Pick addCrew(UUID mentorId, UUID jobId, UUID applicationId, UUID accountId, Contribution contribution) {
		Job job = this.crews.lockJob(jobId);
		if (!mentorId.equals(job.mentorId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!RECRUITS.contains(job.state())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.RECRUITMENT_CLOSED);
		}
		List<Row> rows = this.crews.rows(jobId);
		if (rows.stream().anyMatch(row -> applicationId.equals(row.applicationId()))) {
			// The application was picked meanwhile (a second click, or a second tab): the screen is stale.
			throw new ApiException(ErrorCode.STALE);
		}
		int cap = this.recruitment.cap();
		if (rows.stream().filter(row -> !row.released()).count() >= cap) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_FULL, Map.of("n", cap));
		}
		boolean elsewhere = this.crews.runningCrewOf(accountId).filter(other -> !other.equals(jobId)).isPresent();
		if (elsewhere || this.crews.locked(accountId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.APPLICANT_UNAVAILABLE);
		}
		if (rows.stream().anyMatch(row -> row.studentId().equals(accountId) && row.activatedAt() != null)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_REJOIN_REFUSED);
		}
		CrewService.requireNoOtherRole(job, accountId);
		if (rows.stream().anyMatch(row -> row.studentId().equals(accountId) && !row.released())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.ROLE_TAKEN);
		}
		refuseConflictAndContribution(job, accountId, contribution);
		Row row;
		try {
			row = this.crews.insertPick(jobId, accountId, applicationId);
		}
		catch (DuplicateKeyException running) {
			// SG-51: another pick of the same person committed first; one running Crew per person (crew_row_running_idx).
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.APPLICANT_UNAVAILABLE);
		}
		catch (DataIntegrityViolationException backstop) {
			throw backstop(backstop);
		}
		this.crews.bump(jobId, false);
		this.recruitment.takeSlot(jobId);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.crew_pick_added", AuditObject.of("job.crew_row", row.id()))
			.forJob(jobId));
		return new Pick(row.id(), jobId, mentorId, job.title());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean releasePick(UUID jobId, UUID rowId, PickEnd end) {
		this.crews.lockJob(jobId);
		Row row = this.crews.row(rowId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!row.assigned()) {
			// Released already (J8, a lock), or signed: nobody who signed is removed (FR-75).
			return false;
		}
		this.crews.release(row, end.name());
		this.crews.bump(jobId, false);
		this.recruitment.returnSlot(jobId);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.crew_pick_released", AuditObject.of("job.crew_row", rowId))
			.forJob(jobId));
		return true;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void activateCrew(UUID jobId, UUID rowId, Contribution contribution, Instant signedAt) {
		Job job = this.crews.lockJob(jobId);
		Row row = this.crews.row(rowId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (row.released() || !RECRUITS.contains(job.state())) {
			// A-04: J8 released the pick, or the Job left Active; the row stays as it is.
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.PICK_ENDED);
		}
		if (!row.assigned()) {
			throw new ApiException(ErrorCode.STALE);
		}
		UUID member = row.studentId();
		CrewService.requireNoOtherRole(job, member);
		List<Row> rows = this.crews.rows(jobId);
		if (rows.stream().anyMatch(other -> other.studentId().equals(member) && other.activatedAt() != null)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_REJOIN_REFUSED);
		}
		boolean elsewhere = this.crews.runningCrewOf(member).filter(other -> !other.equals(jobId)).isPresent();
		if (elsewhere || this.crews.locked(member)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.APPLICANT_UNAVAILABLE);
		}
		refuseConflictAndContribution(job, member, contribution);
		try {
			this.crews.activate(row, signedAt);
		}
		catch (DataIntegrityViolationException backstop) {
			throw backstop(backstop);
		}
		this.crews.bump(jobId, true);
		this.auditLog.record(AuditEntry.of(AuditActor.user(member), "job.crew_member_joined", AuditObject.of("job.crew_row", rowId))
			.forJob(jobId));
		this.events.publishEvent(new CrewMemberAdded(this.ids.newId(), jobId, rowId, member, job.state().name()));
		PersonFacts person = this.profiles.factsOf(List.of(member)).get(member);
		String name = person == null || person.displayName().isBlank() ? "Một thành viên nhóm" : person.displayName();
		String link = "/cong-viec/" + jobId;
		List<Recipient> crew = new ArrayList<>(List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		this.crews.rows(jobId)
			.stream()
			.filter(other -> other.active() && !other.id().equals(rowId))
			.forEach(other -> crew.add(new Recipient(other.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(JOINED, key(rowId + ":joined"), crew,
				Map.of("name", name, "job", job.title()), link, jobId, null));
		if (person != null && person.githubLogin() != null) {
			// R15, UX-DR108: the Customer reads the GitHub username only, never a name, a profile or a pick.
			this.notifications.send(new NotificationRequest(LOGIN_ADDED, key(rowId + ":login-added"),
					List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
					Map.of("login", person.githubLogin(), "job", job.title()), link, jobId, null));
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Assignment> assignment(UUID jobId, UUID accountId) {
		Optional<Row> row = this.crews.rows(jobId)
			.stream()
			.filter(found -> found.studentId().equals(accountId) && found.assigned())
			.findFirst();
		if (row.isEmpty()) {
			return Optional.empty();
		}
		Job job = this.crews.current(jobId);
		// The Agreement in force, or before the confirmation the live draft (AD-41): its scope and Milestones only.
		Content content = this.reviews.confirmed(jobId)
			.map(AgreementReviews.Confirmed::content)
			.or(() -> this.agreements.draft(job).map(Agreements.Draft::content))
			.orElse(null);
		List<AssignmentMilestone> milestones = content == null ? List.of()
				: content.milestones()
					.stream()
					.map(milestone -> new AssignmentMilestone(milestone.title(), milestone.dueOn(), milestone.priceVnd()))
					.toList();
		return Optional.of(new Assignment(jobId, row.get().id(), job.title(), job.state().name(),
				content == null || content.scope().isBlank() ? null : content.scope(), job.code(), milestones));
	}

	/** A shared person-link signal with the Customer or the Mentor (AD-19), then the contributor read's answer (A-03). */
	private void refuseConflictAndContribution(Job job, UUID accountId, Contribution contribution) {
		UUID mentorId = job.mentorId();
		if (this.signals.sharesPersonSignal(accountId, job.customerId())
				|| (mentorId != null && this.signals.sharesPersonSignal(accountId, mentorId))) {
			this.alerts.conflictRefused();
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CONFLICT_OF_INTEREST);
		}
		if (contribution == Contribution.CONTRIBUTOR) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.APPLICANT_IS_CONTRIBUTOR);
		}
		if (contribution == Contribution.UNKNOWN) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CONTRIBUTORS_UNKNOWN);
		}
	}

	/** The one-role and no-rejoin triggers are the backstops of the refusals above (AD-43, R3). */
	private static RuntimeException backstop(DataIntegrityViolationException backstop) {
		String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(backstop).getMessage());
		if (cause.contains(ONE_ROLE_TRIGGER)) {
			return new ApiException(ErrorCode.GUARD_FAILED, JobReason.ROLE_TAKEN);
		}
		if (cause.contains(REJOIN_TRIGGER)) {
			return new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_REJOIN_REFUSED);
		}
		return backstop;
	}

	private static UUID key(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

}
