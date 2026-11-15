package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-21, FR-38, D10, AD-7, Story 23.10b): the days before the platform repositories are deleted
 * ({@link Revocations#REMINDERS}), a Customer whose handover work is still only in the Intermediary Repository is
 * reminded to fetch it. {@link Revocations} schedules one Deadline per lead time as the Job ends, with the days as
 * the generation, so each fires once; the subject is the Job.
 * <p>
 * Not pausable: it has to land before the non-pausable {@link RepositoryDeletion}, which a freeze does not move
 * either, and a Dispute changes nothing about code the Customer may already read. Everything is re-checked at fire
 * time -- the Job, the repositories still pending, the code receipt and the Final Merge -- so a receipt or a branch
 * read back in between leaves every reminder still to come {@code OBSOLETE}, and so do repositories already gone.
 * An Assessment Job has no handover work, so it is never scheduled one. Collaborators are looked up on use.
 */
@Component
class DownloadReminder implements DeadlineHandler {

	static final String KEY = "repo.download_reminder";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<RepositoryDeletions> deletions;

	private final ObjectProvider<CodeReceipts> receipts;

	private final ObjectProvider<Deadlines> deadlines;

	private final ObjectProvider<Notifications> notifications;

	DownloadReminder(ObjectProvider<JobBusy> busy, ObjectProvider<RepositoryDeletions> deletions,
			ObjectProvider<CodeReceipts> receipts, ObjectProvider<Deadlines> deadlines,
			ObjectProvider<Notifications> notifications) {
		this.busy = busy;
		this.deletions = deletions;
		this.receipts = receipts;
		this.deadlines = deadlines;
		this.notifications = notifications;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		UUID jobId = deadline.subjectId();
		BusyJob job = this.busy.getObject().job(jobId).orElse(null);
		// The deletion's own Deadline holds the date, so the reminder can never name one the repositories do not go on.
		Instant deleteAt = this.deadlines.getObject().dueAt(RepositoryDeletion.KEY, jobId).orElse(null);
		if (job == null || deleteAt == null || this.deletions.getObject().pending(jobId).isEmpty()
				|| !this.receipts.getObject().unfetched(jobId)) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.notifications.getObject()
			.send(new NotificationRequest(KEY, cause(jobId, deadline.generation()),
					List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
					Map.of("job", job.title(), "date", DAY_MONTH.format(deleteAt.atZone(VIETNAM))),
					FinalMergeAuthorizations.link(jobId), jobId, null));
		return DeadlineOutcome.FIRED;
	}

	/** One cause per Job and lead time, so a replayed poll adds nothing. */
	private static UUID cause(UUID jobId, String generation) {
		return UUID.nameUUIDFromBytes((jobId + ":download_reminder:" + generation).getBytes(StandardCharsets.UTF_8));
	}

}
