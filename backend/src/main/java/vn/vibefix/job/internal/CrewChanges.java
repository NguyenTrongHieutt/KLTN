package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.CrewMemberAdded;
import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.job.HeldMilestones;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.CrewService.TermsVersion;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * A Crew change on a running Job (Story 11.3; FR-75, AD-30, AD-41 "Tier 2 changes"): while no Milestone
 * is Held the Mentor saves a {@code PENDING} Crew Terms version naming the Crew after the change, new
 * members join as {@code ASSIGNED}, and every party confirms that version. It takes effect at its last
 * confirmation, or inside an M5, M7, or M8 apply once nothing is Held; J8, J9, and J10 void one still
 * waiting. The Customer never learns of a waiting change, only of the new usernames once it took effect.
 */
@Component
class CrewChanges {

	static final String PROPOSED = "job.crew_change_proposed";

	static final String CHANGED = "job.crew_changed";

	static final String VOIDED = "job.crew_change_voided";

	private static final Set<JobState> RUNNING = Set.of(JobState.STARTING, JobState.ACTIVE, JobState.CLOSING);

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final HeldMilestones held;

	private final Settings settings;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	private final boolean largeCrews;

	CrewChanges(JdbcClient jdbc, CrewService crews, HeldMilestones held, Settings settings, Notifications notifications,
			AuditLog auditLog, ApplicationEventPublisher events, IdGenerator ids, Clock clock,
			@Value("${vibefix.features.large-crews:false}") boolean largeCrews) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.held = held;
		this.settings = settings;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
		this.largeCrews = largeCrews;
	}

	/** Whether the Crew of a Job in this state may change (AD-30: only with {@code features.largeCrews} on). */
	boolean changeable(JobState state) {
		return this.largeCrews && RUNNING.contains(state);
	}

	/** Whether a Milestone of the Job is Held, so a change waits (FR-75). */
	boolean held(UUID jobId) {
		return !this.held.heldOf(jobId).isEmpty();
	}

	/** The change still waiting: a version is in force and a later one is {@code PENDING}. */
	Optional<TermsVersion> waiting(UUID jobId) {
		if (this.crews.inForce(jobId).isEmpty()) {
			return Optional.empty();
		}
		return this.crews.latest(jobId).filter(version -> "PENDING".equals(version.status()));
	}

	/**
	 * "Áp dụng khi mọi người đã xác nhận": saves the Crew after the change as a {@code PENDING} version.
	 * A current member left out of {@code shares} is removed when it takes effect; a Student without a row
	 * joins as {@code ASSIGNED} after every Story 4.3 check. Only {@code crewVersion} moves.
	 * @param shares every party's share after the change, the Mentor's included
	 * @throws ApiException {@code NOT_FOUND} with large Crews off; {@code STALE}; {@code GUARD_FAILED ROLE_TAKEN}
	 * for the Job's Customer as a member, before any other rule (AD-43); {@code GUARD_FAILED
	 * CREW_CHANGE_WHILE_HELD} or {@code CREW_LIMIT}; {@code VALIDATION SHARES_INVALID}; and the Story 4.3 refusals
	 * for a new member
	 */
	@Transactional
	TermsVersion propose(UUID mentorId, UUID jobId, long crewVersion, Map<UUID, Integer> shares) {
		if (!this.largeCrews) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Job job = this.crews.lockMentorJob(mentorId, jobId, crewVersion);
		shares.keySet().stream().filter(party -> !party.equals(mentorId))
			.forEach(member -> CrewService.requireNoOtherRole(job, member));
		if (!RUNNING.contains(job.state())) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		if (held(jobId)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_CHANGE_WHILE_HELD);
		}
		int cap = this.settings.value(Rule.MENTOR_SHARE_CAP_PERCENT);
		Set<UUID> members = new LinkedHashSet<>(shares.keySet());
		members.remove(mentorId);
		Integer mentorShare = shares.get(mentorId);
		boolean valid = mentorShare != null && mentorShare >= 0 && mentorShare <= cap && !members.isEmpty()
				&& members.stream().allMatch(member -> shares.get(member) != null && shares.get(member) > 0)
				&& shares.values().stream().mapToInt(Integer::intValue).sum() == 100;
		if (!valid) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.SHARES_INVALID, Map.of("cap", cap));
		}
		if (members.size() > CrewService.LARGE_CREW_LIMIT) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_LIMIT);
		}
		List<Row> running = this.crews.rows(jobId).stream().filter(row -> !row.released()).toList();
		Map<UUID, Row> current = running.stream().collect(Collectors.toMap(Row::studentId, Function.identity()));
		// A new member of an older waiting change who is not in this one never joined: they go now.
		for (Row row : running) {
			if ("ASSIGNED".equals(row.status()) && !members.contains(row.studentId())) {
				this.crews.release(row, "REMOVED");
				this.notifications.send(new NotificationRequest(CrewService.MEMBER_REMOVED, row.id(),
						List.of(new Recipient(row.studentId(), Audience.SUBJECT)), Map.of("job", job.title()), "/cong-viec", null,
						row.id()));
			}
		}
		Map<UUID, UUID> rowOf = new HashMap<>();
		for (UUID member : members) {
			Row row = current.get(member);
			rowOf.put(member, row != null ? row.id() : this.crews.addRow(job, mentorId, member).id());
		}
		int versionNo = this.crews.writeVersion(jobId, mentorId, cap, shares, rowOf);
		this.crews.bump(jobId, false);
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.crew_change_proposed", AuditObject.of("job.job", jobId))
			.forJob(jobId));
		for (UUID member : members) {
			Row row = current.get(member);
			if (row != null && "ACTIVE".equals(row.status())) {
				this.notifications.send(new NotificationRequest(PROPOSED, key(row.id() + ":p" + versionNo),
						List.of(new Recipient(member, Audience.CREW_ACTIVE)), Map.of("job", job.title()), "/cong-viec", jobId, null));
			}
		}
		return this.crews.latest(jobId).orElseThrow();
	}

	/**
	 * A party confirms the waiting version: recorded on it, with a new member's NDA time, and no row's
	 * status changes until the change takes effect, which the last confirmation tries.
	 * @throws ApiException {@code GUARD_FAILED CREW_CHANGE_REQUIRED} with nothing waiting; {@code STALE}
	 * unless {@code versionNo} is the waiting version; {@code GUARD_FAILED CREW_NOT_CONFIRMED} for a row
	 * that is not a party of it
	 */
	void confirm(Job job, Row row, int versionNo, Instant signedAt) {
		TermsVersion pending = waiting(job.id())
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_CHANGE_REQUIRED));
		if (pending.versionNo() != versionNo) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (!pending.shares().containsKey(row.studentId())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_NOT_CONFIRMED);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_confirmation (job_id, version_no, crew_row_id, confirmed_at)
				VALUES (:job, :no, :row, :now) ON CONFLICT DO NOTHING
				""")
			.param("job", job.id())
			.param("no", versionNo)
			.param("row", row.id())
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("UPDATE job.crew_row SET signed_at = coalesce(signed_at, :signed) WHERE id = :id")
			.param("id", row.id())
			.param("signed", Timestamp.from(signedAt))
			.update();
		this.crews.bump(job.id(), false);
		this.auditLog.record(AuditEntry.of(AuditActor.user(row.studentId()), "job.crew_change_confirmed",
				AuditObject.of("job.crew_row", row.id())).forJob(job.id()));
		this.crews.closeConfirmTodo(row.id());
		this.notifications.send(new NotificationRequest(CrewService.MEMBER_CONFIRMED, key(row.id() + ":c" + versionNo),
				List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("job", job.title(), "student", this.crews.studentName(row.studentId())), Agreements.agreementLink(job.id()),
				job.id(), null));
		evaluate(job.id(), true);
	}

	/**
	 * A new member of the waiting change declines and is released; an active member stays until a change
	 * removes them.
	 * @throws ApiException {@code GUARD_FAILED CREW_CHANGE_REQUIRED} for an active member or with nothing
	 * waiting; {@code STALE} unless {@code versionNo} is the waiting version
	 */
	void decline(Job job, Row row, int versionNo) {
		TermsVersion pending = waiting(job.id()).filter(version -> "ASSIGNED".equals(row.status()))
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_CHANGE_REQUIRED));
		if (pending.versionNo() != versionNo) {
			throw new ApiException(ErrorCode.STALE);
		}
		this.crews.release(row, "DECLINED");
		this.crews.bump(job.id(), false);
		this.auditLog.record(AuditEntry.of(AuditActor.user(row.studentId()), "job.crew_member_declined",
				AuditObject.of("job.crew_row", row.id())).forJob(job.id()));
		this.notifications.send(new NotificationRequest(CrewService.MEMBER_DECLINED, row.id(),
				List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("job", job.title(), "student", this.crews.studentName(row.studentId())), Agreements.agreementLink(job.id()),
				job.id(), null));
	}

	/**
	 * Takes the waiting change into effect when it can (AD-41): the Job is running with no Held Milestone,
	 * every party has a row that is not released, signed the NDA, and confirmed the version, and at least one
	 * party counts: its account is unlocked, read through {@code identity.lockedAmong} under the Job lock the
	 * caller holds (Story 20.4). In the caller's transaction the version comes into force,
	 * removed rows are released as {@code REMOVED}, and new rows become {@code ACTIVE}; the Customer, the
	 * Mentor, and the Crew get the new usernames. Runs at the last confirmation and inside M5, M7, and M8.
	 * @param ownVersion outside an apply the usernames change moves {@code Job.version} itself; inside one
	 * the apply's own bump counts
	 * @return whether the change took effect
	 */
	boolean evaluate(UUID jobId, boolean ownVersion) {
		Job job = this.crews.current(jobId);
		TermsVersion pending = waiting(jobId).orElse(null);
		if (pending == null || !RUNNING.contains(job.state()) || held(jobId)) {
			return false;
		}
		List<Row> running = this.crews.rows(jobId).stream().filter(row -> !row.released()).toList();
		List<Row> parties = running.stream().filter(row -> pending.shares().containsKey(row.studentId())).toList();
		boolean ready = parties.size() == pending.shares().size() - 1
				&& parties.stream().allMatch(row -> row.signedAt() != null && pending.confirmedRows().contains(row.id()));
		List<UUID> members = parties.stream().map(Row::studentId).toList();
		Set<UUID> locked = this.crews.lockedAmong(members);
		if (!ready || members.stream().allMatch(locked::contains)) {
			return false;
		}
		Map<UUID, StudentCard> cards = this.crews.cards(members);
		this.crews.bringIntoForce(jobId);
		Instant now = this.clock.instant();
		for (Row row : running) {
			if (!parties.contains(row)) {
				this.crews.release(row, "REMOVED");
				this.notifications.send(new NotificationRequest(CrewService.MEMBER_REMOVED, row.id(),
						List.of(new Recipient(row.studentId(), Audience.SUBJECT)), Map.of("job", job.title()), "/cong-viec", null,
						row.id()));
				this.events.publishEvent(new CrewMemberRemoved(this.ids.newId(), jobId, row.id(), row.studentId()));
			}
		}
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'ACTIVE', activated_at = coalesce(activated_at, :now)
				WHERE job_id = :job AND status = 'ASSIGNED'
				""").param("job", jobId).param("now", Timestamp.from(now)).update();
		parties.stream()
			.filter(row -> "ASSIGNED".equals(row.status()))
			.forEach(row -> this.events.publishEvent(
					new CrewMemberAdded(this.ids.newId(), jobId, row.id(), row.studentId(), job.state().name())));
		this.crews.bump(jobId, ownVersion);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.crew_change_applied", AuditObject.of("job.job", jobId))
			.forJob(jobId));
		String usernames = parties.stream()
			.map(row -> cards.get(row.studentId()))
			.filter(card -> card != null && card.githubLogin() != null)
			.map(StudentCard::githubLogin)
			.collect(Collectors.joining(" · "));
		List<Recipient> people = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
				new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		parties.forEach(row -> people.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(CHANGED, key(jobId + ":changed:" + pending.versionNo()), people,
				Map.of("job", job.title(), "usernames", usernames), Agreements.agreementLink(jobId), jobId, null));
		return true;
	}

	/**
	 * J8, J9, and J10 (FR-75, AD-41): a change still waiting is voided, its new members are released as
	 * {@code JOB_ENDED}, and the Mentor and those members are told.
	 */
	void voidWaiting(UUID jobId) {
		TermsVersion pending = waiting(jobId).orElse(null);
		if (pending == null) {
			return;
		}
		Job job = this.crews.current(jobId);
		this.jdbc.sql("UPDATE job.crew_terms_version SET status = 'VOIDED' WHERE job_id = :job AND status = 'PENDING'")
			.param("job", jobId)
			.update();
		List<Row> joining = this.crews.rows(jobId).stream().filter(row -> "ASSIGNED".equals(row.status())).toList();
		List<Recipient> told = new ArrayList<>(List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		joining.forEach(row -> told.add(new Recipient(row.studentId(), Audience.CREW_ASSIGNED)));
		// The audience is checked against the rows as they stand, so the new members hear before their release.
		this.notifications.send(new NotificationRequest(VOIDED, key(jobId + ":voided:" + pending.versionNo()), told,
				Map.of("job", job.title()), Agreements.agreementLink(jobId), jobId, null));
		joining.forEach(row -> this.crews.release(row, "JOB_ENDED"));
		this.crews.bump(jobId, false);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.crew_change_voided", AuditObject.of("job.job", jobId))
			.forJob(jobId));
	}

	private static UUID key(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

}
