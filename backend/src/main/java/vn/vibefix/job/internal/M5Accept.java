package vn.vibefix.job.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * M5 (FR-33, FR-35, AD-5): the Customer accepts the current handover, or Auto-acceptance does (Story
 * 6.4). The Milestone records when and whether automatically, and carries the Crew Terms version its
 * release uses; the Customer and the Mentor get {@code job.milestone_accepted}. {@code delivery} has
 * {@code payment} write the RELEASE entries in the same transaction.
 */
@Component
class M5Accept implements JobLifecycle.Step {

	static final String ACCEPTED = "job.milestone_accepted";

	private final JdbcClient jdbc;

	private final M1Fund numbers;

	static final String AUTO_ACCEPTED = "job.milestone_auto_accepted";

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final ClosureProposals closures;

	M5Accept(JdbcClient jdbc, M1Fund numbers, Notifications notifications, AuditLog auditLog, ClosureProposals closures) {
		this.jdbc = jdbc;
		this.numbers = numbers;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.closures = closures;
	}

	@Override
	public Transition transition() {
		return Transition.M5;
	}

	/** @throws ApiException {@code FORBIDDEN CUSTOMER_ONLY}; {@code STALE STATE_CHANGED} or {@code HANDOVER_CHANGED} */
	@Override
	public void check(Applying applying) {
		requireCustomerOnCurrent(this.jdbc, applying);
	}

	@Override
	public void apply(Applying applying) {
		boolean auto = applying.trigger() == Trigger.AUTO;
		applying.set("accepted_at", applying.now());
		applying.set("auto_accepted", auto);
		applying.fact("milestoneId", applying.milestoneId().toString());
		applying.fact("handoverId", applying.input("handoverId").toString());
		applying.fact("number", String.valueOf(this.numbers.number(applying.job().id(), applying.milestoneId())));
		applying.fact("auto", String.valueOf(auto));
		Integer pin = this.jdbc.sql("SELECT crew_terms_version_no FROM job.milestone WHERE id = :id")
			.param("id", applying.milestoneId())
			.query(Integer.class)
			.optional()
			.orElse(null);
		if (pin != null) {
			applying.fact("crewTermsVersionNo", String.valueOf(pin));
		}
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		boolean auto = applying.trigger() == Trigger.AUTO;
		if (auto) {
			this.auditLog.record(AuditEntry.of(AuditActor.system(), AUTO_ACCEPTED,
					AuditObject.of("job.milestone", applying.milestoneId())).forJob(job.id()));
		}
		this.notifications.send(new NotificationRequest(ACCEPTED, applying.transitionId(),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
						new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)),
				Map.of("n", String.valueOf(this.numbers.number(job.id(), applying.milestoneId())), "job", job.title(), "how",
						auto ? "tự nghiệm thu" : "nghiệm thu"),
				"/cong-viec/" + job.id() + "/ban-giao/" + applying.input("handoverId"), job.id(), null));
		this.closures.proposeAutomatically(job, applying.transitionId());
	}

	/** The actor is the Customer, the Job is Active, and the handover is the Milestone's current one. */
	static void requireCustomerOnCurrent(JdbcClient jdbc, Applying applying) {
		Job job = applying.job();
		if (applying.trigger() == Trigger.USER && !Objects.equals(applying.actor().accountId(), job.customerId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.CUSTOMER_ONLY);
		}
		if (job.state() != JobState.ACTIVE) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		UUID current = jdbc.sql("SELECT handover_id FROM job.milestone WHERE id = :id")
			.param("id", applying.milestoneId())
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (!Objects.equals(current, applying.input("handoverId"))) {
			throw new ApiException(ErrorCode.STALE, JobReason.HANDOVER_CHANGED);
		}
	}

}
