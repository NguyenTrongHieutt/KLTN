package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobRules;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * M3 (FR-33, FR-86, AD-11, AD-28, D9): the Milestone's current handover is void, and the Milestone goes back to
 * where it was handed over from: Revision Requested once a Revision Round was used, Funded before that. It gets its
 * rework due date when that is later than its due date (FR-87, {@link DueDates#rework}). Two things apply it.
 * <ul>
 * <li><em>The System</em>, when a Code Job's acceptance merge answered 409 because the handover branch no longer holds
 * the Handover Commit; a push to the Working Repository never applies it. The Customer and the Mentor get
 * {@code job.handover_voided}, and in a Self-mentored Job the Customer and the active members (R66). {@code delivery}
 * voids the handover, moves its to-dos, alerts the Administrators, and closes the handover's pull request.</li>
 * <li><em>The Customer of a Self-mentored Job</em>, who returns the handover with a reason (FR-86, AD-47 exception 2,
 * Story 25.5): no Revision Round is used, the return is counted on the Milestone and refused once the count reaches
 * the Job's copied {@code HANDOVER_RETURN_LIMIT}, and the active members get {@code job.handover_returned} with the
 * reason as written. No Administrator is alerted. It is a command, so a busy or frozen Job refuses it
 * ({@link JobLifecycle}), and nothing is stored as pending (AD-28).</li>
 * </ul>
 * Auto-acceptance ends through its generation check either way.
 */
@Component
class M3Void implements JobLifecycle.Step {

	static final String VOIDED = "job.handover_voided";

	/** FR-86: the Customer of a Self-mentored Job returned the handover; the active members read why. */
	static final String RETURNED = "job.handover_returned";

	/** The rule value's name on {@code job.job.rules} (FR-61, R50). */
	static final String RETURN_RULE = "HANDOVER_RETURN_LIMIT";

	/** {@code HANDOVER_RETURN_LIMIT} for a Job published before the rule existed. */
	static final int DEFAULT_RETURN_LIMIT = 2;

	/** The input that makes an M3 a return: the Customer's reason as written. */
	static final String RETURN_REASON = "returnReason";

	private final JdbcClient jdbc;

	private final M1Fund numbers;

	private final Notifications notifications;

	private final DueDates dueDates;

	private final JobRules rules;

	private final CrewService crews;

	M3Void(JdbcClient jdbc, M1Fund numbers, Notifications notifications, DueDates dueDates, JobRules rules,
			CrewService crews) {
		this.jdbc = jdbc;
		this.numbers = numbers;
		this.notifications = notifications;
		this.dueDates = dueDates;
		this.rules = rules;
		this.crews = crews;
	}

	@Override
	public Transition transition() {
		return Transition.M3;
	}

	/**
	 * @throws ApiException {@code STALE STATE_CHANGED} outside Active; {@code STALE HANDOVER_CHANGED}; and for a
	 * return, {@code NOT_FOUND} in a normal Job, {@code FORBIDDEN CUSTOMER_ONLY}, and {@code GUARD_FAILED
	 * HANDOVER_RETURNS_USED}
	 */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		boolean returned = returned(applying);
		// A person applies M3 only by returning a handover, and the System never returns one.
		if (returned != (applying.trigger() == Trigger.USER)) {
			throw new IllegalArgumentException("M3 by " + applying.trigger() + (returned ? " with" : " without") + " a return");
		}
		if (returned) {
			if (!job.selfMentored()) {
				// FR-86: a normal Job has no return, so the command finds nothing there.
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			if (!Objects.equals(applying.actor().accountId(), job.customerId())) {
				throw new ApiException(ErrorCode.FORBIDDEN, JobReason.CUSTOMER_ONLY);
			}
		}
		if (job.state() != JobState.ACTIVE) {
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
		Counts counts = counts(applying.milestoneId());
		if (returned && counts.returns() >= limit(job.id())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.HANDOVER_RETURNS_USED);
		}
		applying.leadTo(counts.rounds() > 0 ? MilestoneState.REVISION_REQUESTED.name() : MilestoneState.FUNDED.name());
	}

	@Override
	public void apply(Applying applying) {
		applying.fact("milestoneId", applying.milestoneId().toString());
		applying.fact("handoverId", applying.input("handoverId").toString());
		applying.fact("number", String.valueOf(this.numbers.number(applying.job().id(), applying.milestoneId())));
		if (returned(applying)) {
			int used = counts(applying.milestoneId()).returns() + 1;
			applying.set("handover_returns_used", used);
			// The reason is the Customer's text and stays out of the event: delivery keeps it with the handover.
			applying.fact("returned", "true");
			applying.fact("returnsLeft", String.valueOf(Math.max(0, limit(applying.job().id()) - used)));
		}
		this.dueDates.rework(applying, returned(applying) ? DueDates.Cause.RETURN : DueDates.Cause.VOID);
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		String n = String.valueOf(this.numbers.number(job.id(), applying.milestoneId()));
		String link = "/cong-viec/" + job.id() + "/ban-giao/" + applying.input("handoverId");
		if (returned(applying)) {
			// The Customer returned it and reads so on the page; a Job whose members all left tells nobody.
			List<Recipient> members = members(job);
			if (!members.isEmpty()) {
				this.notifications.send(new NotificationRequest(RETURNED, applying.transitionId(), members,
						Map.of("n", n, "reason", String.valueOf(applying.input(RETURN_REASON))), link, job.id(), null));
			}
		}
		else {
			List<Recipient> told = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
			if (job.selfMentored()) {
				// R66, AD-47 exception 12: the members hand over, so they are told as well as the Customer.
				told.addAll(members(job));
			}
			else if (job.mentorId() != null) {
				told.add(new Recipient(job.mentorId(), Audience.MENTOR));
			}
			this.notifications.send(new NotificationRequest(VOIDED, applying.transitionId(), told, Map.of("n", n), link,
					job.id(), null));
		}
		this.dueDates.reworked(applying);
	}

	private static boolean returned(Applying applying) {
		return applying.has(RETURN_REASON);
	}

	/** The Job's copied limit of returns per Milestone (AD-7, R50). */
	private int limit(UUID jobId) {
		return this.rules.value(jobId, RETURN_RULE, DEFAULT_RETURN_LIMIT);
	}

	private List<Recipient> members(Job job) {
		return this.crews.rows(job.id())
			.stream()
			.filter(CrewService.Row::active)
			.map(row -> new Recipient(row.studentId(), Audience.CREW_ACTIVE))
			.toList();
	}

	/** The Milestone's Revision Rounds used and its returns used. */
	private record Counts(int rounds, int returns) {
	}

	private Counts counts(UUID milestoneId) {
		return this.jdbc.sql("SELECT revision_rounds_used, handover_returns_used FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query((rs, n) -> new Counts(rs.getInt("revision_rounds_used"), rs.getInt("handover_returns_used")))
			.single();
	}

}
