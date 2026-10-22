package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * M3 (FR-32, AD-11): a push after a Code Job's handover moved the Working Repository's head away from its
 * Handover Commit, so the handover is void. Only the Milestone's current handover is voided. The
 * Milestone goes back to where it was handed over from: Revision Requested once a Revision Round was
 * used, Funded before that. The Customer and the Mentor get {@code job.handover_voided};
 * {@code delivery} voids the handover and moves its to-dos, and Auto-acceptance ends through its
 * generation check.
 */
@Component
class M3Void implements JobLifecycle.Step {

	static final String VOIDED = "job.handover_voided";

	private final JdbcClient jdbc;

	private final M1Fund numbers;

	private final Notifications notifications;

	M3Void(JdbcClient jdbc, M1Fund numbers, Notifications notifications) {
		this.jdbc = jdbc;
		this.numbers = numbers;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.M3;
	}

	/** @throws ApiException {@code STALE STATE_CHANGED} outside Active; {@code STALE HANDOVER_CHANGED} */
	@Override
	public void check(Applying applying) {
		if (applying.job().state() != JobState.ACTIVE) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		UUID current = this.jdbc.sql("SELECT handover_id FROM job.milestone WHERE id = :id")
			.param("id", applying.milestoneId())
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (!Objects.equals(current, applying.input("handoverId"))) {
			throw new ApiException(ErrorCode.STALE, JobReason.HANDOVER_CHANGED);
		}
		int used = this.jdbc.sql("SELECT revision_rounds_used FROM job.milestone WHERE id = :id")
			.param("id", applying.milestoneId())
			.query(Integer.class)
			.single();
		applying.leadTo(used > 0 ? MilestoneState.REVISION_REQUESTED.name() : MilestoneState.FUNDED.name());
	}

	@Override
	public void apply(Applying applying) {
		applying.fact("milestoneId", applying.milestoneId().toString());
		applying.fact("handoverId", applying.input("handoverId").toString());
		applying.fact("number", String.valueOf(this.numbers.number(applying.job().id(), applying.milestoneId())));
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		List<Recipient> both = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (job.mentorId() != null) {
			both.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		this.notifications.send(new NotificationRequest(VOIDED, applying.transitionId(), both,
				Map.of("n", String.valueOf(this.numbers.number(job.id(), applying.milestoneId()))),
				"/cong-viec/" + job.id() + "/ban-giao/" + applying.input("handoverId"), job.id(), null));
	}

}
