package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Administrators;
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.job.JobNeedsDecision;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.Role;

/**
 * FR-59, AD-19, AD-41, UX-DR83: what a lock means for the person's running Jobs (Agreement, Starting,
 * Active, Closing). A locked Crew member's rows become {@code INACTIVE} ({@link CrewService#accountLocked});
 * the Mentor and the Administrators hear of it, and when nobody is left to work the Job needs a decision.
 * A locked Mentor or Customer leaves each of their Jobs needing one. {@code job.party_locked} goes only
 * to whom UX-DR83 names: the Customer about the Mentor, the Mentor about the Customer or a Crew member,
 * the active Crew about the Mentor, and the Administrators always. The caller makes it replay-safe.
 */
@Component
class LockConsequences {

	static final String PARTY_LOCKED = "job.party_locked";

	static final String CREW_LOCKED = "CREW_LOCKED";

	static final String MENTOR_LOCKED = "MENTOR_LOCKED";

	static final String CUSTOMER_LOCKED = "CUSTOMER_LOCKED";

	private static final String RUNNING = "('AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING')";

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

	/** The account's lock, by its role; {@code causeId} keeps each notice once per event and Job. */
	@Transactional
	void locked(UUID causeId, UUID accountId, Role role) {
		switch (role) {
			case STUDENT -> crewLocked(causeId, accountId);
			case MENTOR, CUSTOMER -> partyLocked(causeId, accountId, role);
			default -> {
			}
		}
	}

	private void crewLocked(UUID causeId, UUID studentId) {
		List<UUID> jobs = this.crews.accountLocked(studentId);
		String name = this.students.cards(List.of(studentId))
			.stream()
			.findFirst()
			.map(card -> card.name() != null ? card.name() : card.githubLogin() != null ? "@" + card.githubLogin() : null)
			.orElse(null);
		for (UUID jobId : jobs) {
			Job job = running(jobId);
			if (job == null) {
				continue;
			}
			List<Recipient> told = new ArrayList<>(List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
			told.addAll(admins());
			notify(causeId, job, name == null ? "Một thành viên nhóm" : name, told);
			boolean nobody = job.state() == JobState.AGREEMENT
					? count(jobId, "('ASSIGNED', 'ACTIVE')") == 0 : count(jobId, "('ACTIVE')") == 0;
			if (nobody) {
				this.events.publishEvent(new JobNeedsDecision(this.ids.newId(), jobId, CREW_LOCKED));
			}
		}
	}

	private void partyLocked(UUID causeId, UUID accountId, Role role) {
		boolean mentor = role == Role.MENTOR;
		List<UUID> jobs = this.jdbc.sql("SELECT id FROM job.job WHERE " + (mentor ? "mentor_account_id" : "customer_account_id")
				+ " = :id AND state IN " + RUNNING + " ORDER BY id")
			.param("id", accountId)
			.query(UUID.class)
			.list();
		String name = mentor ? this.mentors.cards(List.of(accountId)).stream().findFirst().map(card -> card.displayName())
			.orElse("Người hướng dẫn") : this.customers.displayName(accountId);
		for (UUID jobId : jobs) {
			Job job = running(jobId);
			if (job == null || job.mentorId() == null) {
				continue;
			}
			List<Recipient> told = new ArrayList<>();
			if (mentor) {
				told.add(new Recipient(job.customerId(), Audience.CUSTOMER));
				this.crews.rows(jobId)
					.stream()
					.filter(row -> "ACTIVE".equals(row.status()))
					.forEach(row -> told.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
			}
			else {
				told.add(new Recipient(job.mentorId(), Audience.MENTOR));
			}
			told.addAll(admins());
			notify(causeId, job, name, told);
			this.events.publishEvent(new JobNeedsDecision(this.ids.newId(), jobId, mentor ? MENTOR_LOCKED : CUSTOMER_LOCKED));
		}
	}

	/** The Job, locked, while it is running; {@code null} once it moved on. */
	private Job running(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND state IN " + RUNNING + " FOR UPDATE")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElse(null);
	}

	private long count(UUID jobId, String statuses) {
		return this.jdbc.sql("SELECT count(*) FROM job.crew_row WHERE job_id = :job AND status IN " + statuses)
			.param("job", jobId)
			.query(Long.class)
			.single();
	}

	private List<Recipient> admins() {
		return this.administrators.activeIds().stream().map(admin -> new Recipient(admin, Audience.ADMIN)).toList();
	}

	private void notify(UUID causeId, Job job, String name, List<Recipient> told) {
		this.notifications.send(new NotificationRequest(PARTY_LOCKED,
				UUID.nameUUIDFromBytes((causeId + ":" + job.id()).getBytes(StandardCharsets.UTF_8)), told,
				Map.of("name", name, "job", job.title()), "/cong-viec/" + job.id(), job.id(), null));
	}

}
