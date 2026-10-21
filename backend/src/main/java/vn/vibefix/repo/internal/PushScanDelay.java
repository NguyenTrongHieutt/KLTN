package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobBusy;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.scanning.ScanStatus;
import vn.vibefix.scanning.Scans;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-8, Conventions' 15-minute target): 10 minutes after a push scan was requested, every
 * Administrator hears of it unless it finished. Not pausable. Collaborators are looked up on use.
 */
@Component
class PushScanDelay implements DeadlineHandler {

	static final String KEY = "repo.push_scan_delay";

	static final String DELAYED = "scanning.push_scan_delayed";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<Scans> scans;

	private final ObjectProvider<JobBusy> busy;

	private final ObjectProvider<Administrators> administrators;

	private final ObjectProvider<Notifications> notifications;

	private final JdbcClient jdbc;

	private final Clock clock;

	PushScanDelay(ObjectProvider<Scans> scans, ObjectProvider<JobBusy> busy, ObjectProvider<Administrators> administrators,
			ObjectProvider<Notifications> notifications, JdbcClient jdbc, Clock clock) {
		this.scans = scans;
		this.busy = busy;
		this.administrators = administrators;
		this.notifications = notifications;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		boolean finished = this.scans.getObject()
			.run(deadline.subjectId())
			.map(run -> run.status() == ScanStatus.SUCCEEDED || run.status() == ScanStatus.FAILED)
			.orElse(true);
		if (finished || deadline.jobId() == null) {
			return DeadlineOutcome.OBSOLETE;
		}
		UUID jobId = deadline.jobId();
		String title = this.busy.getObject().job(jobId).map(JobBusy.BusyJob::title).orElse("");
		List<Recipient> admins = this.administrators.getObject()
			.activeIds()
			.stream()
			.map(id -> new Recipient(id, Audience.ADMIN))
			.toList();
		if (!admins.isEmpty()) {
			this.notifications.getObject()
				.send(new NotificationRequest(DELAYED,
						UUID.nameUUIDFromBytes((deadline.subjectId() + ":delayed").getBytes(StandardCharsets.UTF_8)), admins,
						Map.of("job", title), "/quan-tri/thao-tac-loi", null, null));
		}
		this.jdbc.sql("UPDATE repo.push_scan SET delay_alerted_at = :now WHERE scan_run_id = :run")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("run", deadline.subjectId())
			.update();
		return DeadlineOutcome.FIRED;
	}

}
