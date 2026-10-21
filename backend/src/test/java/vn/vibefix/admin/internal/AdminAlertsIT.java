package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.notification.EmailBudgetAlert;
import vn.vibefix.operations.OperationFailed;
import vn.vibefix.shared.EventPublicationsStalled;
import vn.vibefix.support.IntegrationTest;

/**
 * The PM's C-7 check of 2026-10-21 (UX-DR16, Stories 1.6 and 10.6): a failed External Operation, stalled
 * event publications, and the email budget at its alert share reach every Administrator, in-app and by
 * email, except a failed email operation, which is told in-app only.
 */
class AdminAlertsIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void eachRecordedFailureReachesEveryAdministrator() {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID repo = UUID.randomUUID();
		UUID email = UUID.randomUUID();
		publish(new OperationFailed(UUID.randomUUID(), repo, "repo.invite", "REMOTE_DOWN"));
		publish(new OperationFailed(UUID.randomUUID(), email, AdminAlerts.EMAIL_TYPE, "SMTP_DOWN"));
		publish(new EventPublicationsStalled(UUID.randomUUID(), 3));
		publish(new EmailBudgetAlert(UUID.randomUUID(), LocalDate.parse("2026-01-05"), 240, 300));
		awaitListeners();

		assertThat(alerts("operations.operation_failed", ha)).isOne();
		assertThat(alerts("operations.email_operation_failed", ha)).isOne();
		assertThat(alerts("operations.event_publication_stalled", ha)).isOne();
		assertThat(alerts("notification.email_budget_80", ha)).isOne();
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'sent' || '/' || (params ->> 'budget') FROM notification.notification
				WHERE type = 'notification.email_budget_80' AND account_id = :id
				""").param("id", ha.accountId()).query(String.class).single()).isEqualTo("240/300");
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.notification WHERE type = 'operations.event_publication_stalled'
				AND account_id = :id
				""").param("id", ha.accountId()).query(String.class).single()).isEqualTo("3");
		// One failing SMTP server cannot loop: its alert sends no email, while the others do.
		assertThat(emails("operations.email_operation_failed")).isZero();
		assertThat(emails("operations.operation_failed")).isOne();
	}

	private void publish(Object event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private long alerts(String type, Administrator admin) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :id")
			.param("type", type)
			.param("id", admin.accountId())
			.query(Long.class)
			.single();
	}

	private long emails(String type) {
		return this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation WHERE type = 'notification.email'
				AND payload ->> 'template' = :type
				""").param("type", type).query(Long.class).single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

}
