package vn.vibefix.delivery.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
import vn.vibefix.job.CrewMemberAdded;
import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.job.Crews;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.HandoverJob;
import vn.vibefix.job.MilestoneStates;
import vn.vibefix.job.MilestoneStates.MilestoneDetail;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ProcessedEvents;

/**
 * M1, M3, and M4 make a Milestone wait for its handover (FR-32, UX-DR15): the Mentor gets
 * {@code delivery.hand_over}, due at the end of the Milestone's due date in force in Vietnam time (FR-87,
 * AD-48), without the time a freeze adds. M2 closes it in {@link Handovers}. Each is
 * replay-safe through {@link ProcessedEvents}. M5 on an Assessment Job opens the Mentor's follow-up to-do
 * (FR-11).
 * <p>
 * In a Self-mentored Job the members hand over (AD-47 exception 1, Story 25.5), so the to-do goes to each
 * {@code ACTIVE} Crew member and never to the Customer: to those active when the Milestone starts to wait, to a member
 * who joins while it waits, and away from a member who leaves. M2 closes it for all of them.
 */
@Component
class DeliveryListeners {

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final Handovers handovers;

	private final FollowUps followUps;

	private final MilestoneHandovers jobs;

	private final Todos todos;

	private final ProcessedEvents processedEvents;

	private final MilestoneStates states;

	private final Crews crews;

	DeliveryListeners(Handovers handovers, FollowUps followUps, MilestoneHandovers jobs, Todos todos,
			ProcessedEvents processedEvents, MilestoneStates states, Crews crews) {
		this.crews = crews;
		this.states = states;
		this.handovers = handovers;
		this.followUps = followUps;
		this.jobs = jobs;
		this.todos = todos;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean waits = "M1".equals(event.transition()) || "M3".equals(event.transition()) || "M4".equals(event.transition());
		String milestoneId = event.facts().get("milestoneId");
		if (!waits || milestoneId == null
				|| !this.processedEvents.markProcessed("delivery.DeliveryListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		UUID milestone = UUID.fromString(milestoneId);
		this.jobs.job(event.jobId()).filter(job -> job.mentorId() != null).ifPresent(job -> {
			MilestoneInForce inForce = this.handovers.milestones(job.jobId())
				.stream()
				.filter(found -> found.milestoneId().equals(milestone))
				.findFirst()
				.orElse(null);
			if (inForce == null) {
				return;
			}
			if (job.selfMentored()) {
				this.crews.crewOf(job.jobId(), Crews.Selection.ACTIVE)
					.forEach(member -> open(job, inForce, member.studentId(), Audience.CREW_ACTIVE));
			}
			else {
				open(job, inForce, job.mentorId(), Audience.MENTOR);
			}
		});
	}

	/** A member who joins a Self-mentored Job while a Milestone waits for its handover may be the one to hand it over. */
	@ApplicationModuleListener
	void onCrewMemberAdded(CrewMemberAdded event) {
		if (!this.processedEvents.markProcessed("delivery.DeliveryListeners.onCrewMemberAdded", event.eventId())) {
			return;
		}
		this.jobs.job(event.jobId())
			.filter(HandoverJob::selfMentored)
			.ifPresent(job -> waiting(job).forEach(milestone -> open(job, milestone, event.studentId(), Audience.CREW_ACTIVE)));
	}

	/** A member who left a Self-mentored Job, or was locked, hands nothing over: their to-dos close. */
	@ApplicationModuleListener
	void onCrewMemberRemoved(CrewMemberRemoved event) {
		if (!this.processedEvents.markProcessed("delivery.DeliveryListeners.onCrewMemberRemoved", event.eventId())) {
			return;
		}
		this.jobs.job(event.jobId())
			.filter(HandoverJob::selfMentored)
			.ifPresent(job -> this.handovers.milestones(job.jobId())
				.forEach(milestone -> this.todos.close(Handovers.HAND_OVER, event.studentId(), milestone.milestoneId())));
	}

	/** The Job's Milestones in force that wait for a handover: Funded or Revision Requested. */
	private List<MilestoneInForce> waiting(HandoverJob job) {
		Map<UUID, String> live = this.states.statesOf(job.jobId());
		return this.handovers.milestones(job.jobId())
			.stream()
			.filter(milestone -> Handovers.HANDABLE.contains(live.getOrDefault(milestone.milestoneId(), "PLANNED")))
			.toList();
	}

	private void open(HandoverJob job, MilestoneInForce inForce, UUID account, Audience audience) {
		// FR-87, AD-48: the to-do is due by the due date in force, which the M3 or M4 that reopens it may just have
		// moved; the Agreement's date stands only for a Milestone with no live row.
		MilestoneDetail live = this.states.detailsOf(job.jobId()).get(inForce.milestoneId());
		LocalDate dueOn = live == null || live.dueOn() == null ? inForce.dueOn() : live.dueOn();
		this.todos.open(new TodoRequest(Handovers.HAND_OVER, account, audience, inForce.milestoneId(),
				Map.of("n", String.valueOf(inForce.number())), "/cong-viec/" + job.jobId() + "/ban-giao", due(dueOn),
				job.jobId()));
	}

	/** M5 on an Assessment Job (FR-11): the Mentor may draft follow-up Jobs from the accepted Report. */
	@ApplicationModuleListener
	void onAssessmentAccepted(JobTransitioned event) {
		if (!"M5".equals(event.transition())
				|| !this.processedEvents.markProcessed("delivery.DeliveryListeners.onAssessmentAccepted", event.eventId())) {
			return;
		}
		this.jobs.job(event.jobId()).filter(job -> job.assessment()).ifPresent(this.followUps::openTodo);
	}

	/** The end of the due date in Vietnam time. */
	static @Nullable Instant due(@Nullable LocalDate dueOn) {
		return dueOn == null ? null : dueOn.plusDays(1).atStartOfDay(VIETNAM).toInstant();
	}

}
