package vn.vibefix.delivery.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ProcessedEvents;

/**
 * M1 and M4 make a Milestone wait for its handover (FR-32, UX-DR15): the Mentor gets
 * {@code delivery.hand_over}, due at the end of the Milestone's due date in Vietnam time, which stands
 * for {@code effectiveDueAt} until FR-68's extensions exist. M2 closes it in {@link Handovers}. Each is
 * replay-safe through {@link ProcessedEvents}. M5 on an Assessment Job opens the Mentor's follow-up to-do
 * (FR-11).
 */
@Component
class DeliveryListeners {

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final Handovers handovers;

	private final FollowUps followUps;

	private final MilestoneHandovers jobs;

	private final Todos todos;

	private final ProcessedEvents processedEvents;

	DeliveryListeners(Handovers handovers, FollowUps followUps, MilestoneHandovers jobs, Todos todos,
			ProcessedEvents processedEvents) {
		this.handovers = handovers;
		this.followUps = followUps;
		this.jobs = jobs;
		this.todos = todos;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean waits = "M1".equals(event.transition()) || "M4".equals(event.transition());
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
			this.todos.open(new TodoRequest(Handovers.HAND_OVER, job.mentorId(), Audience.MENTOR, milestone,
					Map.of("n", String.valueOf(inForce.number())), "/cong-viec/" + job.jobId() + "/ban-giao", due(inForce.dueOn()),
					job.jobId()));
		});
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
