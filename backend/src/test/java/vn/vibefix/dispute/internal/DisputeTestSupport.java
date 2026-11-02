package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.job.JobFreezes;
import vn.vibefix.support.MutableClock;
import vn.vibefix.timers.internal.DeadlinePoller;

/** Opens a Dispute for a test in another module, as "Mở tranh chấp" does. */
public final class DisputeTestSupport {

	private DisputeTestSupport() {
	}

	/**
	 * The Customer's Dispute on the Job, joining the caller's transaction if there is one.
	 * @return the Dispute's ID
	 */
	public static UUID open(ApplicationContext context, UUID jobId, UUID customerId) {
		return context.getBean(Disputes.class)
			.open(jobId, customerId, Disputes.Side.CUSTOMER, "Giai đoạn chưa đạt mà vẫn bị đòi nghiệm thu")
			.id();
	}

	/**
	 * A-02, AD-4: the Customer's Dispute racing the poller over the one Deadline due now. The Dispute holds the
	 * Job row, which {@code Disputes.open} takes first, while the poller claims the Deadline and waits for that
	 * row. So the freeze must skip the claimed row: waiting on it would deadlock, and Postgres would abort one
	 * of the two. The handler goes on once the Dispute commits.
	 * @param openedAt what the Clock reads when the Dispute opens, as it may have read just before the poller's
	 * @return the Dispute's ID, once the poller finished
	 */
	public static UUID openRacingThePoller(ApplicationContext context, UUID jobId, UUID customerId, UUID deadlineId,
			Instant openedAt) throws Exception {
		JdbcClient jdbc = context.getBean(JdbcClient.class);
		DeadlinePoller poller = context.getBean(DeadlinePoller.class);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			AtomicReference<Future<Integer>> polling = new AtomicReference<>();
			UUID dispute = context.getBean(TransactionTemplate.class).execute(status -> {
				context.getBean(JobFreezes.class).lock(jobId);
				polling.set(pool.submit(poller::runDue));
				// A row lock shows in the row's xmax.
				await().atMost(Duration.ofSeconds(15))
					.until(() -> jdbc.sql("SELECT xmax::text <> '0' FROM timers.deadline WHERE id = :id")
						.param("id", deadlineId)
						.query(Boolean.class)
						.single());
				context.getBean(MutableClock.class).setInstant(openedAt);
				UUID opened = open(context, jobId, customerId);
				assertThat(jdbc.sql("SELECT status FROM timers.deadline WHERE id = :id")
					.param("id", deadlineId)
					.query(String.class)
					.single()).as("the row the poller holds: never waited on").isEqualTo("PENDING");
				return opened;
			});
			assertThat(polling.get().get(30, TimeUnit.SECONDS)).as("the poller claimed that one Deadline").isOne();
			return dispute;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
