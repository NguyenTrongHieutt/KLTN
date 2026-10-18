package vn.vibefix.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.support.TransactionTemplate;

import eventfixtures.ProbeEvent;
import eventfixtures.ProbeListener;
import vn.vibefix.VibefixApplication;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * AD-9: a publication still incomplete when the application stops is delivered again when it
 * starts. Two starts of the whole application on their own database: the first leaves a failed
 * publication behind, the second completes it, with the maintenance poller scheduled.
 */
class EventRepublishOnRestartIT {

	private static final PostgresTestDatabase database = PostgresTestDatabase.instance();

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void aFailedPublicationIsDeliveredAgainAfterARestart() throws Exception {
		String db = database.createScratchDatabase();
		ProbeListener.reset(Integer.MAX_VALUE);
		try (ConfigurableApplicationContext first = start(db)) {
			first.getBean(TransactionTemplate.class)
				.executeWithoutResult(status -> first.publishEvent(new ProbeEvent(UUID.randomUUID(), "restart")));
			await().atMost(WAIT).until(() -> "FAILED".equals(status(db)));
		}

		ProbeListener.reset(0);
		try (ConfigurableApplicationContext second = start(db, true)) {
			await().atMost(WAIT).until(() -> "COMPLETED".equals(status(db)));
			assertThat(scheduledMethods(second)).as("the pollers run when scheduling is on")
				.contains(EventPublicationScheduler.class.getName() + ".maintain",
						"vn.vibefix.operations.internal.OperationScheduler.poll");
		}

		assertThat(ProbeListener.applied.get()).isOne();
	}

	private static ConfigurableApplicationContext start(String db) {
		return start(db, false);
	}

	private static ConfigurableApplicationContext start(String db, boolean scheduling) {
		return new SpringApplicationBuilder(VibefixApplication.class, ProbeListener.class).profiles("dev")
			.properties("management.health.sandbox.enabled=false", "vibefix.scheduling.enabled=" + scheduling,
					"server.port=0")
			.run(database.applicationArguments(db));
	}

	/** Each scheduled method as {@code class.method}, the name its runnable reports. */
	private static List<String> scheduledMethods(ConfigurableApplicationContext context) {
		return context.getBeansOfType(ScheduledTaskHolder.class)
			.values()
			.stream()
			.flatMap(holder -> holder.getScheduledTasks().stream())
			.map(task -> task.getTask().getRunnable().toString())
			.toList();
	}

	private static String status(String db) throws SQLException {
		try (Connection connection = database.superuserConnection(db);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT status FROM platform.event_publication WHERE event_type = '"
						+ ProbeEvent.class.getName() + "'")) {
			return rows.next() ? rows.getString(1) : null;
		}
	}

}
