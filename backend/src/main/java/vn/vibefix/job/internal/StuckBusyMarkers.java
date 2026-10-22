package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobBusy.Purpose;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.OperationPolicy;

/**
 * A busy marker held longer than any precondition operation can take (AD-28): its default retry budget
 * plus an hour. Every 10 minutes the sweep tells every Administrator, once per marker, with
 * {@value #STUCK}; the marker stays, since only its resolving transaction may clear it.
 */
@Component
class StuckBusyMarkers {

	static final String STUCK = "operations.busy_marker_stuck";

	/** The default policy's waits between its attempts, plus an hour. */
	static final Duration THRESHOLD = budget(OperationPolicy.DEFAULT).plusHours(1);

	/** What the alert calls each purpose's operation. */
	static final Map<Purpose, String> OPERATIONS = Map.of(Purpose.ACCEPTANCE, "hợp nhất bàn giao", Purpose.SETTLEMENT,
			"tất toán", Purpose.FINAL_MERGE, "hợp nhất cuối", Purpose.JOB_SETUP, "chuẩn bị kho", Purpose.JOB_VERIFY,
			"kiểm tra quyền kho");

	private final JdbcClient jdbc;

	private final Administrators administrators;

	private final Notifications notifications;

	private final Clock clock;

	StuckBusyMarkers(JdbcClient jdbc, Administrators administrators, Notifications notifications, Clock clock) {
		this.jdbc = jdbc;
		this.administrators = administrators;
		this.notifications = notifications;
		this.clock = clock;
	}

	@Transactional
	void sweep() {
		record Stuck(UUID jobId, String title, String key, Purpose purpose) {
		}
		List<Stuck> stuck = this.jdbc.sql("""
				SELECT id, title, busy_operation_key, busy_purpose FROM job.job
				WHERE busy_operation_key IS NOT NULL AND busy_since < :before
				""")
			.param("before", Timestamp.from(this.clock.instant().minus(THRESHOLD)))
			.query((rs, n) -> new Stuck(rs.getObject("id", UUID.class), rs.getString("title"),
					rs.getString("busy_operation_key"), Purpose.valueOf(rs.getString("busy_purpose"))))
			.list();
		if (stuck.isEmpty()) {
			return;
		}
		List<Recipient> admins = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (admins.isEmpty()) {
			return;
		}
		for (Stuck marker : stuck) {
			// One alert per marker: the cause is its operation key, which a later marker never reuses.
			UUID cause = UUID.nameUUIDFromBytes(("busy:" + marker.key()).getBytes(StandardCharsets.UTF_8));
			this.notifications.send(new NotificationRequest(STUCK, cause, admins,
					Map.of("job", marker.title(), "operation", OPERATIONS.get(marker.purpose())), "/quan-tri", null, null));
		}
	}

	static Duration budget(OperationPolicy policy) {
		Duration total = Duration.ZERO;
		for (int attempt = 1; attempt < policy.maxAttempts(); attempt++) {
			total = total.plus(policy.delayAfter(attempt));
		}
		return total;
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Sweep {

		private final StuckBusyMarkers markers;

		Sweep(StuckBusyMarkers markers) {
			this.markers = markers;
		}

		@Scheduled(fixedDelayString = "${vibefix.job.busy-sweep:PT10M}", initialDelayString = "${vibefix.job.busy-sweep:PT10M}")
		void run() {
			this.markers.sweep();
		}

	}

}
