package vn.vibefix.job.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.identity.PersonDirectory.PersonCard;
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.MilestoneStates;
import vn.vibefix.job.MilestoneStates.MilestoneDetail;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-1's progress (FR-28, AD-33, UX-DR68), under {@code /api/v1/boards/{boardId}} like the Job's other
 * Active-phase calls (the path carries the Job's ID): per Milestone of the Agreement in force its live state,
 * price and due date, and {@code assessment} (FR-10), which tells the page to show N-12 and the team's link
 * to the Job's private read-only copy -- a link {@code repo}'s setup endpoint serves, never this one (R6,
 * Story 23.11b).
 * <p>
 * No mid-Milestone progress figure is served (Story 24.2, decision of 2026-11-18): the platform does not
 * track the team's tasks, so the status is the Milestone's own state and the words for it are composed in the
 * SPA ({@code jobStateText.ts}). The per-criterion detail the Customer decides on is in each Delivery Report
 * (FR-32).
 * <p>
 * The Job's Customer, its Mentor, and its active Crew read it, and only the Mentor reads the Crew roster.
 * A Crew member reads the Milestones and an empty {@code crew}: the roster carries every member's AD-41 state,
 * {@code ASSIGNED} and {@code RELEASED} included, which is the Mentor's to see and not a peer's, so do not
 * widen {@link #crew} to the {@code CREW} audience. A former Crew member, a locked one included, reads the
 * Job's header alone, as {@code FORMER}: its state, title, Product and Mentor as they stand now, which is what
 * their post-Job duties need and what {@code GET /api/v1/jobs/{jobId}/crew/me} already tells them, and no
 * Milestone and no member of the Crew they left (AD-41). An Administrator is no viewer of JOB-1 whatever grant
 * they hold: the Evidence Package is where a case reads a Job (AD-20). Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobProgressController {

	/**
	 * One Milestone's progress.
	 * @param state the live Milestone state name
	 * @param roundsLeft the Revision Rounds left (FR-34)
	 * @param revisionCriteria how many criteria the latest revision request named
	 * @param autoAccepted accepted by Auto-acceptance (FR-35)
	 * @param refunded cancelled with its held money refunded (FR-40)
	 * @param overdueSince when the Funded or Revision Requested Milestone became overdue (FR-48)
	 * @param dueOn the due date in force (FR-87, AD-48); the Agreement's date for a Milestone with no live row
	 * @param agreedDueOn the date the Agreement holds, only when the due date in force differs from it
	 * @param daysLate how many days late the current handover of a Submitted, Accepted or Settled Milestone came
	 * (FR-87, UX-DR129); the page shows it when above 0
	 */
	record MilestoneProgressView(UUID milestoneId, int number, String title, String state, @Nullable Long priceVnd,
			@Nullable LocalDate dueOn, int roundsLeft, @Nullable Integer revisionCriteria, boolean autoAccepted,
			boolean refunded, @Nullable Instant overdueSince, @Nullable LocalDate agreedDueOn, @Nullable Integer daysLate) {
	}

	/**
	 * One Crew member on JOB-1, for the Mentor (UX-DR68).
	 * @param state the row's AD-41 state: {@code ASSIGNED}, {@code ACTIVE}, or {@code RELEASED}
	 */
	record CrewProgressView(UUID accountId, String name, String state) {
	}

	/**
	 * The progress of a Job from Active on.
	 * @param audience {@code CUSTOMER}, {@code MENTOR}, {@code CREW}, or {@code FORMER} (the header only)
	 * @param crew the Crew with each member's state, for the Mentor only
	 * @param assessment an Assessment Job (FR-10), for the Mentor and the Crew; {@code false} for the Customer. It is
	 * what makes the page show N-12 and, under it, the link to the private read-only copy the team reads -- which the
	 * frontend takes from {@code repo}'s own {@code GET /api/v1/jobs/{jobId}/setup}, from each viewer's own row (R6,
	 * Story 23.11b). Nothing here names the Customer's Source Repository: a private Source would not serve the team
	 */
	record JobProgressView(UUID boardId, String title, String state, String productName, String mentorName,
			String audience, List<MilestoneProgressView> milestones, List<CrewProgressView> crew, boolean assessment) {
	}

	/** Who the viewer is on this Job: the Customer, the Mentor, an active member, or a former one. */
	private enum Audience {

		CUSTOMER, MENTOR, CREW, FORMER

	}

	private record Viewer(Job job, Audience audience) {

		boolean mentor() {
			return this.audience == Audience.MENTOR;
		}

	}

	/**
	 * The Job states JOB-1's progress is served in. A Job cancelled before it reached Active has no Agreement in
	 * force, so its Milestone list comes back empty and the page shows no Milestone section at all.
	 */
	private static final List<JobState> SHOWN = List.of(JobState.ACTIVE, JobState.CLOSING, JobState.CLOSED,
			JobState.CANCELLED);

	private final JdbcClient jdbc;

	private final JobRoleReader roles;

	private final AgreementCriteria criteria;

	private final MilestoneStates states;

	private final Crews crews;

	private final ProductDirectory products;

	private final MentorDirectory mentors;

	private final PersonDirectory people;

	private final CurrentCaller callers;

	JobProgressController(JdbcClient jdbc, JobRoleReader roles, AgreementCriteria criteria, MilestoneStates states,
			Crews crews, ProductDirectory products, MentorDirectory mentors, PersonDirectory people,
			CurrentCaller callers) {
		this.jdbc = jdbc;
		this.roles = roles;
		this.criteria = criteria;
		this.states = states;
		this.crews = crews;
		this.products = products;
		this.mentors = mentors;
		this.people = people;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/progress")
	JobProgressView progress(@PathVariable("boardId") UUID jobId) {
		Viewer viewer = viewer(jobId, this.callers.get());
		Job job = viewer.job();
		if (viewer.audience() == Audience.FORMER) {
			return header(viewer, List.of(), List.of(), false);
		}
		Map<UUID, MilestoneDetail> live = this.states.detailsOf(job.id());
		List<MilestoneProgressView> milestones = new ArrayList<>();
		for (MilestoneInForce milestone : this.criteria.inForce(job.id()).map(found -> found.milestones()).orElse(List.of())) {
			MilestoneDetail detail = live.get(milestone.milestoneId());
			LocalDate agreed = milestone.dueOn();
			LocalDate inForce = detail == null || detail.dueOn() == null ? agreed : detail.dueOn();
			milestones.add(new MilestoneProgressView(milestone.milestoneId(), milestone.number(), milestone.title(),
					detail == null ? "PLANNED" : detail.state(), milestone.priceVnd(), inForce,
					detail == null ? 0 : detail.roundsLeft(), detail == null ? null : detail.revisionCriteria(),
					detail != null && detail.autoAccepted(), detail != null && detail.refunded(),
					detail == null ? null : detail.overdueSince(), Objects.equals(agreed, inForce) ? null : agreed,
					detail == null ? null : detail.daysLate()));
		}
		return header(viewer, milestones, crew(viewer), viewer.audience() != Audience.CUSTOMER && !job.code());
	}

	private JobProgressView header(Viewer viewer, List<MilestoneProgressView> milestones, List<CrewProgressView> crew,
			boolean assessment) {
		Job job = viewer.job();
		ProductInfo info = this.products.find(job.productId()).orElse(null);
		return new JobProgressView(job.id(), job.title(), job.state().name(), info == null ? "" : info.name(),
				mentorName(job), viewer.audience().name(), milestones, crew, assessment);
	}

	/** AD-41: every row that is not released, then each former member who was ever active, once per Student. */
	private List<CrewProgressView> crew(Viewer viewer) {
		if (!viewer.mentor()) {
			return List.of();
		}
		Map<UUID, String> states = new LinkedHashMap<>();
		this.crews.crewOf(viewer.job().id(), Selection.UNRELEASED)
			.forEach(member -> states.put(member.studentId(), member.status()));
		this.crews.crewOf(viewer.job().id(), Selection.EVER_ACTIVE)
			.forEach(member -> states.putIfAbsent(member.studentId(), member.status()));
		if (states.isEmpty()) {
			return List.of();
		}
		Map<UUID, PersonCard> cards = new HashMap<>();
		this.people.cards(List.copyOf(states.keySet())).forEach(card -> cards.put(card.accountId(), card));
		List<CrewProgressView> crew = new ArrayList<>();
		states.forEach((studentId, state) -> {
			PersonCard card = cards.get(studentId);
			crew.add(new CrewProgressView(studentId, card == null ? "" : card.shownName(), state));
		});
		return crew;
	}

	private String mentorName(Job job) {
		UUID mentorId = job.mentorId();
		if (mentorId == null) {
			return "";
		}
		return this.mentors.cards(List.of(mentorId)).stream().findFirst().map(MentorCard::displayName).orElse("");
	}

	/** The Customer, the Mentor, an {@code ACTIVE} member, or a former one, of a Job from Active on. */
	private Viewer viewer(UUID jobId, Caller caller) {
		Job job = this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND " + Jobs.LIVE)
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (job.mentorId() == null || !SHOWN.contains(job.state())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		JobRole role = this.roles.roleOf(job, caller);
		if (role.isCustomer()) {
			return new Viewer(job, Audience.CUSTOMER);
		}
		if (role.isMentor()) {
			return new Viewer(job, Audience.MENTOR);
		}
		return switch (role.crewKind()) {
			case ACTIVE -> new Viewer(job, Audience.CREW);
			case FORMER -> new Viewer(job, Audience.FORMER);
			default -> throw new ApiException(ErrorCode.NOT_FOUND);
		};
	}

}
