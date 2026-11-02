package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.CrewEmptied;
import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.IdGenerator;

/**
 * A signed member's departure (FR-59, FR-82, AD-41, UX-DR104), the same consequences whether they left or a lock counted
 * as leaving, so nobody can tell the two apart: the Mentor and the other active members hear
 * {@code job.crew_member_left}, the Customer {@code job.crew_login_removed} with the GitHub username only (R15), the
 * Customer's grant to-dos for the member close, and {@link CrewMemberRemoved} lets {@code repo} and {@code work} react
 * (FR-21, FR-66). When no counted {@code ACTIVE} row is left, {@link CrewEmptied} lets {@code matching} and {@code payment}
 * react before J6, and after J6 the Customer hears {@code job.crew_empty}; no Dispute opens for a Job with no Crew (Q7). A
 * reason never reaches a notice, a to-do or an event: it stays on the row, for JOB-9's Mentor alone.
 */
@Component
class CrewDepartures {

	static final String MEMBER_LEFT = "job.crew_member_left";

	static final String LOGIN_REMOVED = "job.crew_login_removed";

	static final String CREW_EMPTY = "job.crew_empty";

	/** After J6, where an emptied Crew is told to the Customer (Q7). */
	private static final Set<JobState> STARTED = EnumSet.of(JobState.STARTING, JobState.ACTIVE, JobState.CLOSING);

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final ExternalAccessGrants externalAccess;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final Notifications notifications;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	CrewDepartures(JdbcClient jdbc, CrewService crews, ExternalAccessGrants externalAccess, MentorDirectory mentors,
			StudentDirectory students, Notifications notifications, ApplicationEventPublisher events, IdGenerator ids) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.externalAccess = externalAccess;
		this.mentors = mentors;
		this.students = students;
		this.notifications = notifications;
		this.events = events;
		this.ids = ids;
	}

	/**
	 * "Rời công việc" (FR-82): under the Job row lock, first in the transaction (AD-4), the member's row is released and
	 * everyone concerned is told.
	 * @param note the reason, for JOB-9's Mentor alone; {@code null} for none
	 * @throws vn.vibefix.shared.ApiException as {@link CrewService#leave}, {@code NOT_FOUND} for no such Job
	 */
	@Transactional
	void leave(UUID accountId, UUID jobId, @Nullable String note) {
		Job job = this.crews.lockJob(jobId);
		Row row = this.crews.leave(job, accountId, note);
		departed(job, row);
		emptied(job);
	}

	/** What the Job's people hear of the release of {@code row}, an {@code ACTIVE} row as it was before. */
	void departed(Job job, Row row) {
		UUID member = row.studentId();
		StudentCard card = card(member);
		String link = "/cong-viec/" + job.id();
		List<Recipient> crew = new ArrayList<>(List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		this.crews.rows(job.id())
			.stream()
			.filter(Row::active)
			.forEach(other -> crew.add(new Recipient(other.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(MEMBER_LEFT, key(row.id() + ":left"), crew,
				Map.of("name", shownName(card), "job", job.title()), link, job.id(), null));
		if (card != null && card.githubLogin() != null) {
			// R15, UX-DR108: the Customer reads the GitHub username only, never a name or why.
			this.notifications.send(new NotificationRequest(LOGIN_REMOVED, key(row.id() + ":login-removed"),
					List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
					Map.of("login", card.githubLogin(), "job", job.title()), link, job.id(), null));
		}
		this.externalAccess.memberLeft(job, member);
		this.events.publishEvent(new CrewMemberRemoved(this.ids.newId(), job.id(), row.id(), member));
	}

	/** After a departure: with no counted {@code ACTIVE} row left, the Crew emptied (AD-41). */
	void emptied(Job job) {
		if (!this.crews.countedActive(job.id()).isEmpty()) {
			return;
		}
		UUID mentorId = Objects.requireNonNull(job.mentorId());
		this.events.publishEvent(new CrewEmptied(this.ids.newId(), job.id(), mentorId, job.state().name(),
				job.agreementVersionNo() != null, agreementDue(job.id())));
		if (STARTED.contains(job.state())) {
			crewEmpty(job, mentorId);
		}
	}

	/** The member as the Crew reads their name. */
	String shownName(UUID memberId) {
		return shownName(card(memberId));
	}

	private @Nullable StudentCard card(UUID memberId) {
		return this.students.cards(List.of(memberId)).stream().findFirst().orElse(null);
	}

	private static String shownName(@Nullable StudentCard card) {
		return card == null || card.shownName().isBlank() ? "Một thành viên nhóm" : card.shownName();
	}

	/** The Agreement deadline, the open-recruitment to-do's (UX-DR112). */
	private @Nullable Instant agreementDue(UUID jobId) {
		return this.jdbc.sql("SELECT agreement_due_at FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant)
			.orElse(null);
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

	private static UUID key(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

}
