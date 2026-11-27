package vn.vibefix.job.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.identity.PersonDirectory.PersonCard;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * M2 (FR-32, AD-4): the Mentor's Milestone Handover makes a Funded or Revision Requested Milestone
 * Submitted. {@code delivery} vouches for the handover and passes its ID, which the Milestone records
 * with the time; a Code Job also needs its Handover Commit, which Epic 7 records. The Customer gets
 * {@code job.milestone_submitted} with the date to check by: now plus the Job's copied Auto-acceptance
 * days (FR-35), whose Deadline {@code delivery} keeps from Story 6.4 on.
 * <p>
 * In a Self-mentored Job a counted {@code ACTIVE} Crew member hands over, and the Customer, who is that Job's Mentor,
 * cannot (FR-32, AD-47 exception 1, Story 25.5); the notice names the member.
 */
@Component
class M2Submit implements JobLifecycle.Step {

	static final String SUBMITTED = "job.milestone_submitted";

	static final int DEFAULT_AUTO_ACCEPTANCE_DAYS = 7;

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private final JdbcClient jdbc;

	private final M1Fund numbers;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	private final DueDates dueDates;

	private final CrewService crews;

	private final PersonDirectory people;

	M2Submit(JdbcClient jdbc, M1Fund numbers, MentorDirectory mentors, Notifications notifications, DueDates dueDates,
			CrewService crews, PersonDirectory people) {
		this.crews = crews;
		this.people = people;
		this.dueDates = dueDates;
		this.jdbc = jdbc;
		this.numbers = numbers;
		this.mentors = mentors;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.M2;
	}

	/**
	 * @throws ApiException {@code FORBIDDEN MENTOR_ONLY}, or {@code MEMBER_ONLY} in a Self-mentored Job;
	 * {@code STALE STATE_CHANGED} outside Active; {@code GUARD_FAILED HANDOVER_COMMIT_MISSING} for a Code Job without
	 * its commit
	 */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		UUID actor = applying.actor().accountId();
		if (job.selfMentored()) {
			// AD-47 exception 1: a member who counts hands over, never the Customer, though the Customer is the Mentor.
			if (actor == null || this.crews.countedActive(job.id()).stream().noneMatch(row -> row.studentId().equals(actor))) {
				throw new ApiException(ErrorCode.FORBIDDEN, JobReason.MEMBER_ONLY);
			}
		}
		else if (!Objects.equals(actor, job.mentorId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.MENTOR_ONLY);
		}
		if (job.state() != JobState.ACTIVE) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		applying.input("handoverId");
		if (job.code() && !applying.has("handoverCommit")) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.HANDOVER_COMMIT_MISSING);
		}
	}

	@Override
	public void apply(Applying applying) {
		UUID handover = (UUID) applying.input("handoverId");
		applying.set("submitted_at", applying.now());
		applying.set("handover_id", handover);
		applying.set("overdue_since", null);
		// FR-87, AD-48: every handover records its days late against the due date in force at this moment, read before
		// this statement clears the overdue flag. They count from when the Mentor sent it: a Code Job's M2 applies once
		// the runner has copied the Handover Commit, and that wait is not the team's lateness. The first handover
		// decides "on time"; a later handover or a later due date never rewrites that.
		Instant sent = applying.has("sentAt") ? (Instant) applying.input("sentAt") : applying.now();
		int daysLate = this.dueDates.daysLate(applying.milestoneId(), sent.isAfter(applying.now()) ? applying.now() : sent);
		applying.set("days_late", daysLate);
		applying.fact("daysLate", String.valueOf(daysLate));
		Boolean first = this.jdbc.sql("SELECT first_submitted_at IS NULL FROM job.milestone WHERE id = :id")
			.param("id", applying.milestoneId())
			.query(Boolean.class)
			.single();
		if (first) {
			applying.set("first_submitted_at", applying.now());
			applying.set("first_days_late", daysLate);
		}
		applying.fact("milestoneId", applying.milestoneId().toString());
		applying.fact("handoverId", handover.toString());
		if (applying.has("handoverCommit")) {
			applying.fact("handoverCommit", String.valueOf(applying.input("handoverCommit")));
		}
		applying.fact("number", String.valueOf(this.numbers.number(applying.job().id(), applying.milestoneId())));
		applying.fact("checkBy", checkBy(applying).toString());
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		String date = DAY_MONTH.format(checkBy(applying).atZone(VIETNAM));
		this.notifications.send(new NotificationRequest(SUBMITTED, applying.transitionId(),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
				Map.of("who", who(applying), "n", String.valueOf(this.numbers.number(job.id(), applying.milestoneId())), "date",
						date),
				"/cong-viec/" + job.id() + "/ban-giao/" + applying.input("handoverId"), job.id(), null));
	}

	/**
	 * Who handed over, as the Customer's notice names them (UX-DR126): "Người hướng dẫn {name}", or in a Self-mentored
	 * Job "Thành viên {name}", the member under the name the Crew reads.
	 */
	private String who(Applying applying) {
		Job job = applying.job();
		if (!job.selfMentored()) {
			String mentor = this.mentors.cards(List.of(Objects.requireNonNull(job.mentorId()))).stream().findFirst()
				.map(MentorCard::displayName).orElse("");
			return ("Người hướng dẫn " + mentor).strip();
		}
		String member = this.people.cards(List.of(Objects.requireNonNull(applying.actor().accountId()))).stream().findFirst()
			.map(PersonCard::shownName).orElse("");
		return member.isBlank() ? "Một thành viên nhóm" : "Thành viên " + member;
	}

	/** When the Customer should have checked by: the handover plus the Job's copied Auto-acceptance days. */
	Instant checkBy(Applying applying) {
		Integer days = this.jdbc.sql("SELECT (rules ->> 'AUTO_ACCEPTANCE_DAYS')::int FROM job.job WHERE id = :id")
			.param("id", applying.job().id())
			.query(Integer.class)
			.optional()
			.orElse(null);
		return applying.now().plus(Duration.ofDays(days == null ? DEFAULT_AUTO_ACCEPTANCE_DAYS : days));
	}

}
