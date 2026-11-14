package vn.vibefix.repo.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobFreezes;
import vn.vibefix.job.JobFreezes.DisputedJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (AD-7, FR-37, FR-38, D10, Story 23.10a): {@value #BEFORE_DAYS} days before the closing-choice
 * deadline, a Customer who has picked neither way of receiving the code is reminded, once. A deadline already inside
 * those days when J8 schedules it gets no reminder. Pausable: it takes the Job row lock first, as
 * {@link FinalMergeDeadline} does, so a frozen Job always pauses it and nothing is sent (AD-4).
 * <p>
 * The choice is settled, and the reminder {@code OBSOLETE}, once the row is no longer open -- the branch was read
 * back or the code receipt recorded -- or once the Customer set the Final Merge run going. Collaborators are looked
 * up on use.
 */
@Component
class ReceiveCodeReminder implements DeadlineHandler {

	static final String KEY = "repo.receive_code_reminder";

	static final DeadlineKind KIND = new DeadlineKind(KEY, true, true);

	static final int BEFORE_DAYS = 2;

	/** How long before the closing-choice deadline the reminder goes out (AD-7, the PRD's reminders). */
	static final Duration BEFORE = Duration.ofDays(BEFORE_DAYS);

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final ObjectProvider<FinalMergeAuthorizations> authorizations;

	private final ObjectProvider<JobFreezes> freezes;

	private final ObjectProvider<FinalMerges> merges;

	private final ObjectProvider<CodeReceipts> receipts;

	private final ObjectProvider<Notifications> notifications;

	ReceiveCodeReminder(ObjectProvider<FinalMergeAuthorizations> authorizations, ObjectProvider<JobFreezes> freezes,
			ObjectProvider<FinalMerges> merges, ObjectProvider<CodeReceipts> receipts,
			ObjectProvider<Notifications> notifications) {
		this.authorizations = authorizations;
		this.freezes = freezes;
		this.merges = merges;
		this.receipts = receipts;
		this.notifications = notifications;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		DisputedJob job = this.freezes.getObject().lock(Objects.requireNonNull(deadline.jobId())).orElse(null);
		if (job != null && job.frozen()) {
			return DeadlineOutcome.REPAUSE;
		}
		Authorization authorization = this.authorizations.getObject().find(deadline.subjectId()).orElse(null);
		if (job == null || authorization == null || !authorization.open() || authorization.deadlineAt() == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		boolean settled = this.merges.getObject().find(authorization.id()).filter(FinalMerges::confirmed).isPresent()
				|| this.receipts.getObject().find(authorization.jobId()).isPresent();
		if (settled) {
			return DeadlineOutcome.OBSOLETE;
		}
		Instant due = Objects.requireNonNull(authorization.deadlineAt());
		this.notifications.getObject()
			.send(new NotificationRequest(KEY, FinalMergeAuthorizations.cause(authorization.id(), "receive-code-reminder"),
					List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
					Map.of("job", job.title(), "date", DAY_MONTH.format(due.atZone(VIETNAM))),
					FinalMergeAuthorizations.link(job.jobId()), job.jobId(), null));
		return DeadlineOutcome.FIRED;
	}

}
