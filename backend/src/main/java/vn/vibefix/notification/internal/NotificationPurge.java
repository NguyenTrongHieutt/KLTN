package vn.vibefix.notification.internal;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes notifications 90 days after they were created and to-dos 90 days after they were closed
 * (UX-DR16, AD-24); runs every 6 hours, off when scheduling is off.
 */
@Component
class NotificationPurge {

	private final JdbcClient jdbc;

	private final Clock clock;

	NotificationPurge(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** @return how many rows were deleted */
	int purge() {
		Timestamp cutoff = Timestamp.from(JdbcNotifications.cutoff(this.clock.instant()));
		int notifications = this.jdbc.sql("DELETE FROM notification.notification WHERE created_at <= :cutoff")
			.param("cutoff", cutoff)
			.update();
		int todos = this.jdbc.sql("DELETE FROM notification.todo WHERE closed_at <= :cutoff")
			.param("cutoff", cutoff)
			.update();
		return notifications + todos;
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final NotificationPurge purge;

		Scheduler(NotificationPurge purge) {
			this.purge = purge;
		}

		@Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
		void run() {
			this.purge.purge();
		}

	}

}
