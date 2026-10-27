package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.Purpose;
import vn.vibefix.job.internal.JobLifecycle.Planned;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-28: the busy marker. A precondition operation reserves a Job in the state its purpose runs in, at
 * the version it read; another operation is refused with {@code JOB_BUSY}, and so is every J or M
 * transition until the marker is released by the same operation. Neither call changes the version.
 */
class JobBusyIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JobBusy busy;

	@Autowired
	private JobLifecycle lifecycle;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private StuckBusyMarkers markers;

	private UUID job;

	@BeforeEach
	void aStartingCodeJob() {
		this.job = job("STARTING");
	}

	@Test
	void aMarkerRefusesAnotherOperationAndEveryTransitionUntilItsOwnReleaseClearsIt() {
		inTransaction(() -> this.busy.reserve(this.job, Purpose.JOB_SETUP, null, 1, "repo.job_setup:one"));
		inTransaction(() -> this.busy.reserve(this.job, Purpose.JOB_SETUP, null, 1, "repo.job_setup:one"));

		assertThat(this.busy.job(this.job).orElseThrow().busy()).hasValueSatisfying(marker -> {
			assertThat(marker.purpose()).isEqualTo(Purpose.JOB_SETUP);
			assertThat(marker.operationKey()).isEqualTo("repo.job_setup:one");
		});
		assertThat(refused(() -> this.busy.reserve(this.job, Purpose.JOB_VERIFY, null, 1, "repo.job_verify:two")))
			.extracting(ApiException::code)
			.isEqualTo(ErrorCode.JOB_BUSY);
		assertThat(this.lifecycle.refusal(this.job, Trigger.SYSTEM, List.of(Planned.job(Transition.J7))))
			.extracting(ApiException::code)
			.isEqualTo(ErrorCode.JOB_BUSY);
		assertThat(result(() -> this.busy.release(this.job, "repo.job_verify:two"))).isFalse();
		assertThat(result(() -> this.busy.release(this.job, "repo.job_setup:one"))).isTrue();

		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
		assertThat(this.busy.job(this.job).orElseThrow().version()).as("the version is untouched").isOne();
		ApiException j7 = this.lifecycle.refusal(this.job, Trigger.SYSTEM, List.of(Planned.job(Transition.J7)));
		assertThat(j7.code()).as("J7's own guard again").isEqualTo(ErrorCode.GUARD_FAILED);
		assertThat(j7.reason()).contains("REPOSITORIES_NOT_READY");
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action LIKE 'job.busy_%' AND job_id = :job")
			.param("job", this.job).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void aReservationChecksTheVersionTheStateAndTheFreeze() {
		assertThat(refused(() -> this.busy.reserve(this.job, Purpose.JOB_SETUP, null, 2, "k"))).extracting(ApiException::code)
			.isEqualTo(ErrorCode.STALE);
		assertThat(refused(() -> this.busy.reserve(this.job, Purpose.ACCEPTANCE, null, 1, "k")).reason())
			.contains("STATE_CHANGED");
		this.jdbc.sql("UPDATE job.job SET frozen_at = :now WHERE id = :id")
			.param("now", Timestamp.from(this.clock.instant())).param("id", this.job).update();
		assertThat(refused(() -> this.busy.reserve(this.job, Purpose.JOB_SETUP, null, 1, "k")).reason()).contains("JOB_FROZEN");
		assertThatThrownBy(() -> this.busy.reserve(this.job, Purpose.JOB_SETUP, null, 1, "k"))
			.as("only inside a transaction")
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(this.busy.job(this.job).orElseThrow().busy()).isEmpty();
	}

	@Test
	void aMarkerHeldPastItsOperationsBudgetPlusAnHourAlertsEveryAdministratorOnce() {
		UUID admin = administrator();
		String key = "delivery.acceptance_merge:" + UUID.randomUUID();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE' WHERE id = :id").param("id", this.job).update();
		inTransaction(() -> this.busy.reserve(this.job, Purpose.ACCEPTANCE, null, 1, key));
		UUID cause = UUID.nameUUIDFromBytes(("busy:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		this.markers.sweep();
		assertThat(alerts(admin, cause)).as("not stuck yet").isZero();

		this.jdbc.sql("UPDATE job.job SET busy_since = :since WHERE id = :id")
			.param("since", Timestamp.from(this.clock.instant().minus(StuckBusyMarkers.THRESHOLD).minusSeconds(60)))
			.param("id", this.job)
			.update();
		this.markers.sweep();
		this.markers.sweep();

		assertThat(alerts(admin, cause)).isOne();
		assertThat(this.jdbc.sql("SELECT params ->> 'operation' FROM notification.notification WHERE causing_record_id = :cause")
			.param("cause", cause).query(String.class).single()).isEqualTo("hợp nhất bàn giao");
		assertThat(this.busy.job(this.job).orElseThrow().busy()).as("only its own resolution clears it").isPresent();
		assertThat(StuckBusyMarkers.THRESHOLD).as("63.5 minutes of retries plus an hour")
			.isEqualTo(Duration.ofSeconds(3810).plusHours(1));
	}

	private long alerts(UUID admin, UUID cause) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.notification
				WHERE type = 'operations.busy_marker_stuck' AND account_id = :admin AND causing_record_id = :cause
				""").param("admin", admin).param("cause", cause).query(Long.class).single();
	}

	/** An Administrator with credentials, as {@code Administrators.activeIds} counts them. */
	private UUID administrator() {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, :email, 'Quản trị', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", admin).param("email", "qt-" + admin.toString().substring(0, 8) + "@vibefix.vn").update();
		return admin;
	}

	private ApiException refused(Runnable call) {
		try {
			inTransaction(call);
		}
		catch (ApiException refused) {
			return refused;
		}
		throw new AssertionError("Expected a refusal");
	}

	private void inTransaction(Runnable call) {
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> call.run());
	}

	private <T> T result(java.util.function.Supplier<T> call) {
		return new TransactionTemplate(this.transactions).execute(status -> call.get());
	}

	private UUID job(String state) {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		UUID product = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", product).param("owner", customer)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at,
				    mentor_account_id, selected_application_id, selected_at, agreement_version_no)
				VALUES (:id, :product, :owner, :state, 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :lapses, 1, :now, :now,
				    :mentor, :application, :now, 1)
				""")
			.param("id", id).param("product", product).param("owner", customer).param("state", state).param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14)))).param("mentor", mentor)
			.param("application", UUID.randomUUID()).update();
		return id;
	}

}
