package vn.vibefix.job.internal;

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
 * M4 (FR-33, FR-34): the Customer asks for a revision of the current handover, naming criteria. It needs
 * a Revision Round left and uses one; the Milestone records how many criteria were named, and the Mentor
 * gets {@code job.revision_requested} with the rounds left. {@code delivery} reopens the handover to-do.
 */
@Component
class M4RequestRevision implements JobLifecycle.Step {

	static final String REQUESTED = "job.revision_requested";

	private final JdbcClient jdbc;

	private final M1Fund numbers;

	private final Notifications notifications;

	M4RequestRevision(JdbcClient jdbc, M1Fund numbers, Notifications notifications) {
		this.jdbc = jdbc;
		this.numbers = numbers;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.M4;
	}

	/**
	 * @throws ApiException {@code FORBIDDEN CUSTOMER_ONLY}; {@code STALE STATE_CHANGED} or
	 * {@code HANDOVER_CHANGED}; {@code GUARD_FAILED REVISION_ROUNDS_USED}
	 */
	@Override
	public void check(Applying applying) {
		M5Accept.requireCustomerOnCurrent(this.jdbc, applying);
		Rounds rounds = rounds(applying.milestoneId());
		if (rounds.used() >= rounds.total()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.REVISION_ROUNDS_USED);
		}
	}

	@Override
	public void apply(Applying applying) {
		Rounds rounds = rounds(applying.milestoneId());
		int criteria = (Integer) applying.input("criteria");
		applying.set("revision_rounds_used", rounds.used() + 1);
		applying.set("revision_criteria", criteria);
		applying.fact("milestoneId", applying.milestoneId().toString());
		applying.fact("handoverId", applying.input("handoverId").toString());
		applying.fact("number", String.valueOf(this.numbers.number(applying.job().id(), applying.milestoneId())));
		applying.fact("roundsLeft", String.valueOf(rounds.total() - rounds.used() - 1));
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		Rounds rounds = rounds(applying.milestoneId());
		this.notifications.send(new NotificationRequest(REQUESTED, applying.transitionId(),
				List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("n", String.valueOf(this.numbers.number(job.id(), applying.milestoneId())), "count",
						String.valueOf(applying.input("criteria")), "rounds", String.valueOf(rounds.total() - rounds.used())),
				"/cong-viec/" + job.id() + "/ban-giao/" + applying.input("handoverId"), job.id(), null));
	}

	/** The Milestone's Revision Rounds and how many are used. */
	record Rounds(int total, int used) {
	}

	Rounds rounds(UUID milestoneId) {
		return this.jdbc.sql("SELECT revision_rounds, revision_rounds_used FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query((rs, n) -> new Rounds(rs.getInt("revision_rounds"), rs.getInt("revision_rounds_used")))
			.single();
	}

}
