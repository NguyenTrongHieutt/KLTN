package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Administrators;
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.JobNeedsDecision;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.IdGenerator;

/**
 * FR-59, FR-82, AD-19, AD-41, UX-DR83, UX-DR104: what a lock means for every relation the account holds, Job by
 * Job: an account holds no role (Story 20.3), and one role per Job (Story 20.2) gives each Job one branch. The
 * Jobs are every one where the account has an unreleased Crew row, and every one in Agreement through Closing it
 * mentors or is the Customer of, locked in ascending ID order (AD-4).
 * <ul>
 * <li>A lock counts as leaving (Story 20.4): an {@code ACTIVE} row is released as {@code LEFT}, its window ending
 * at the lock's own time; the Mentor and the other active members hear {@code job.crew_member_left}, the Customer
 * {@code job.crew_login_removed} with the GitHub username only, and the Administrators {@code job.party_locked}.
 * An {@code ASSIGNED} row, a pick not yet signed, is released as {@code DECLINED}, and only the Mentor hears
 * ({@code job.crew_pick_released}): the Customer never learns of picks. When a release after J6 leaves no counted
 * {@code ACTIVE} row, the Customer hears {@code job.crew_empty}; no Dispute opens for a Job with no Crew.</li>
 * <li>A locked Mentor or Customer leaves each of their running Jobs needing a decision ({@link JobNeedsDecision});
 * {@code job.party_locked} goes to the other party, to the active Crew about the Mentor, and to the
 * Administrators.</li>
 * </ul>
 * The Customer never learns that a Crew member was locked, and nobody learns why. The caller makes it replay-safe
 * by marking the event processed in the same transaction. Beyond that marker, the Crew branch acts only on unreleased
 * rows and keys each notice by its row, so it changes nothing a second time by itself; the party branch keys its
 * notice by the event, but would publish {@link JobNeedsDecision} again without the marker, and so relies on it and
 * on {@code dispute} keeping one open Dispute per Job.
 */
@Component
class LockConsequences {

	static final String PARTY_LOCKED = "job.party_locked";

	static final String MEMBER_LEFT = "job.crew_member_left";

	static final String LOGIN_REMOVED = "job.crew_login_removed";

	static final String PICK_RELEASED = "job.crew_pick_released";

	static final String CREW_EMPTY = "job.crew_empty";

	static final String MENTOR_LOCKED = "MENTOR_LOCKED";

	static final String CUSTOMER_LOCKED = "CUSTOMER_LOCKED";

	private static final Set<JobState> RUNNING = EnumSet.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE,
			JobState.CLOSING);

	/** After J6, where an emptied Crew is told to the Customer (Q7). */
	private static final Set<JobState> STARTED = EnumSet.of(JobState.STARTING, JobState.ACTIVE, JobState.CLOSING);

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Administrators administrators;

	private final CustomerNames customers;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final Notifications notifications;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	LockConsequences(JdbcClient jdbc, CrewService crews, Administrators administrators, CustomerNames customers,
			MentorDirectory mentors, StudentDirectory students, Notifications notifications, ApplicationEventPublisher events,
			IdGenerator ids) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.administrators = administrators;
		this.customers = customers;
		this.mentors = mentors;
		this.students = students;
		this.notifications = notifications;
		this.events = events;
		this.ids = ids;
	}

	/**
	 * The account's lock on every Job it holds a relation to, in one transaction, Job by Job in ascending ID order.
	 * @param causeId the lock event, which keeps each party notice once per event and Job
	 * @param lockedAt the lock's own time, where each released window ends
	 */
	@Transactional
	void locked(UUID causeId, UUID accountId, Instant lockedAt) {
		List<UUID> jobs = this.jdbc.sql("""
				SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND status <> 'RELEASED'
				UNION
				SELECT id FROM job.job WHERE (mentor_account_id = :id OR customer_account_id = :id)
				  AND state IN ('AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING')
				ORDER BY 1
				""").param("id", accountId).query(UUID.class).list();
		for (UUID jobId : jobs) {
			Job job = lock(jobId);
			boolean customer = accountId.equals(job.customerId());
			if (customer || accountId.equals(job.mentorId())) {
				if (RUNNING.contains(job.state()) && job.mentorId() != null) {
					partyLocked(causeId, job, accountId, !customer);
				}
			}
			else {
				crewLocked(job, accountId, lockedAt);
			}
		}
	}

	/** The account's Crew rows on the Job are released as a lock releases them, and whom UX-DR104 names is told. */
	private void crewLocked(Job job, UUID memberId, Instant lockedAt) {
		List<Row> released = this.crews.releaseLocked(job, memberId, lockedAt);
		if (released.isEmpty()) {
			return;
		}
		StudentCard card = this.students.cards(List.of(memberId)).stream().findFirst().orElse(null);
		String name = card == null || card.shownName().isBlank() ? "Một thành viên nhóm" : card.shownName();
		UUID mentorId = Objects.requireNonNull(job.mentorId());
		String link = "/cong-viec/" + job.id();
		for (Row row : released) {
			if (!"ACTIVE".equals(row.status())) {
				this.notifications.send(new NotificationRequest(PICK_RELEASED, key(row.id() + ":pick-released"),
						List.of(new Recipient(mentorId, Audience.MENTOR)), Map.of("name", name, "job", job.title()),
						Agreements.agreementLink(job.id()), job.id(), null));
				continue;
			}
			List<Recipient> crew = new ArrayList<>(List.of(new Recipient(mentorId, Audience.MENTOR)));
			this.crews.rows(job.id())
				.stream()
				.filter(other -> "ACTIVE".equals(other.status()))
				.forEach(other -> crew.add(new Recipient(other.studentId(), Audience.CREW_ACTIVE)));
			this.notifications.send(new NotificationRequest(MEMBER_LEFT, key(row.id() + ":left"), crew,
					Map.of("name", name, "job", job.title()), link, job.id(), null));
			if (card != null && card.githubLogin() != null) {
				// R15, UX-DR108: the Customer reads the GitHub username only, never a name or why.
				this.notifications.send(new NotificationRequest(LOGIN_REMOVED, key(row.id() + ":login-removed"),
						List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
						Map.of("login", card.githubLogin(), "job", job.title()), link, job.id(), null));
			}
			List<Recipient> admins = admins();
			if (!admins.isEmpty()) {
				this.notifications.send(new NotificationRequest(PARTY_LOCKED, key(row.id() + ":locked"), admins,
						Map.of("name", name, "job", job.title()), link, job.id(), null));
			}
		}
		if (STARTED.contains(job.state()) && this.crews.countedActive(job.id()).isEmpty()) {
			crewEmpty(job, mentorId);
		}
	}

	/**
	 * Q7, FR-82: the Customer hears that no Crew member is left and that the Mentor carries on. It is keyed by how
	 * many rows the Job ever activated, so two locks that empty the Crew together tell the Customer once, and a Crew
	 * that fills and empties again tells them again.
	 */
	private void crewEmpty(Job job, UUID mentorId) {
		long everActive = this.crews.rows(job.id()).stream().filter(row -> row.activatedAt() != null).count();
		String mentor = this.mentors.cards(List.of(mentorId)).stream().findFirst().map(MentorCard::displayName).orElse("");
		this.notifications.send(new NotificationRequest(CREW_EMPTY, key(job.id() + ":crew-empty:" + everActive),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)), Map.of("job", job.title(), "mentor", mentor),
				"/cong-viec/" + job.id(), job.id(), null));
	}

	/** The Job's Mentor ({@code mentor}) or its Customer is locked: the Job needs a decision. */
	private void partyLocked(UUID causeId, Job job, UUID accountId, boolean mentor) {
		String name = mentor ? this.mentors.cards(List.of(accountId)).stream().findFirst().map(MentorCard::displayName)
			.orElse("Người hướng dẫn") : this.customers.displayName(accountId);
		List<Recipient> told = new ArrayList<>();
		if (mentor) {
			told.add(new Recipient(job.customerId(), Audience.CUSTOMER));
			this.crews.rows(job.id())
				.stream()
				.filter(row -> "ACTIVE".equals(row.status()))
				.forEach(row -> told.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		}
		else {
			told.add(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR));
		}
		told.addAll(admins());
		this.notifications.send(new NotificationRequest(PARTY_LOCKED, key(causeId + ":" + job.id()), told,
				Map.of("name", name, "job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
		this.events.publishEvent(new JobNeedsDecision(this.ids.newId(), job.id(), mentor ? MENTOR_LOCKED : CUSTOMER_LOCKED));
	}

	/** The Job, locked (AD-4). */
	private Job lock(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id FOR UPDATE")
			.param("id", jobId)
			.query(Jobs::job)
			.single();
	}

	private List<Recipient> admins() {
		return this.administrators.activeIds().stream().map(admin -> new Recipient(admin, Audience.ADMIN)).toList();
	}

	private static UUID key(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

}
