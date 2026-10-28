package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.support.IntegrationTest;

/** AD-27, FR-28, UX-DR14, UX-DR15: the to-do store and its order. */
class TodosIT extends IntegrationTest {

	@Autowired
	private Todos todos;

	@Autowired
	private NotificationInbox inbox;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void toDosAreOrderedByTierThenDeadlineThenAge() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		Instant now = this.clock.instant();
		open("test.other", lan, Map.of(), null);
		this.clock.advance(Duration.ofMinutes(1));
		open("test.blocks", lan, Map.of("name", "no deadline"), null);
		open("test.blocks", lan, Map.of("name", "late"), now.plus(Duration.ofDays(5)));
		open("test.blocks", lan, Map.of("name", "soon"), now.plus(Duration.ofDays(2)));
		open("test.decides", lan, Map.of(), now.plus(Duration.ofDays(9)));
		open("test.decides", lan, Map.of(), now.plus(Duration.ofHours(30)));

		List<NotificationInbox.TodoItem> ordered = this.inbox.todos(lan, null);

		assertThat(ordered).extracting(todo -> todo.key() + " " + todo.params().getOrDefault("name", "-"))
			.containsExactly("test.decides -", "test.decides -", "test.blocks soon", "test.blocks late",
					"test.blocks no deadline", "test.other -");
		assertThat(ordered.get(0).deadlineAt()).isEqualTo(now.plus(Duration.ofHours(30)));
		assertThat(ordered).extracting(NotificationInbox.TodoItem::deadlineNear)
			.as("only an auto-deciding deadline within 48 h warns")
			.containsExactly(true, false, false, false, false, false);
	}

	@Test
	void openingAnOpenToDoChangesNothingAndClosingRemovesItForItsRecipientOnly() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		UUID subject = UUID.randomUUID();
		inTransaction(() -> {
			this.todos.open(request("test.other", lan, subject, Map.of(), null));
			this.todos.open(request("test.other", lan, subject, Map.of(), null));
			this.todos.open(request("test.other", minh, subject, Map.of(), null));
		});
		assertThat(this.inbox.todoCount(lan, null)).isOne();

		inTransaction(() -> this.todos.close("test.other", lan, subject));
		assertThat(this.inbox.todoCount(lan, null)).isZero();
		assertThat(this.inbox.todoCount(minh, null)).isOne();

		inTransaction(() -> this.todos.open(request("test.other", lan, subject, Map.of(), null)));
		assertThat(this.inbox.todoCount(lan, null)).as("open again after closing").isOne();

		inTransaction(() -> this.todos.closeAll("test.other", subject));
		assertThat(this.inbox.todoCount(lan, null) + this.inbox.todoCount(minh, null)).isZero();
	}

	@Test
	void aToDoIsCheckedLikeANotification() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);

		assertThatThrownBy(() -> inTransaction(() -> this.todos.open(new TodoRequest("test.other", lan, Audience.ADMIN,
				UUID.randomUUID(), Map.of(), "/viec-can-lam", null, null))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> inTransaction(() -> this.todos.open(request("test.blocks", lan, UUID.randomUUID(),
				Map.of(), null))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> inTransaction(() -> this.todos.open(request("test.nothing", lan, UUID.randomUUID(),
				Map.of(), null))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theCallerListsTheirOpenToDosWithTheTotalAndOptionallyOneJobs() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		UUID job = UUID.randomUUID();
		open("test.other", lan, Map.of(), null);
		inTransaction(() -> this.todos.open(new TodoRequest("test.decides", lan, Audience.SUBJECT, UUID.randomUUID(),
				Map.of(), "/cong-viec/" + job, this.clock.instant().plus(Duration.ofDays(1)), job)));
		open("test.other", minh, Map.of(), null);

		this.mvc.perform(get("/api/v1/todos").with(authentication(IdentityTestSupport.authentication(lan, Role.CUSTOMER))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].key").value("test.decides"))
			.andExpect(jsonPath("$.items[0].link").value("/cong-viec/" + job))
			.andExpect(jsonPath("$.items[0].deadlineNear").value(true))
			.andExpect(jsonPath("$.items[0].tier").value(1))
			.andExpect(jsonPath("$.items[1].key").value("test.other"));
		this.mvc.perform(get("/api/v1/todos").param("jobId", job.toString())
			.with(authentication(IdentityTestSupport.authentication(lan, Role.CUSTOMER))))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].jobId").value(job.toString()))
			.andExpect(jsonPath("$.items[0].role").doesNotExist());
	}

	@Test
	void aJobsToDoCarriesTheRecipientsRoleInThatJobAndAnAccountToDoNone() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		// Hung is the Customer of one Job and the Mentor of another (Story 20.6, AD-27).
		UUID posted = job(hung, null);
		UUID mentored = job(other, hung);
		inTransaction(() -> {
			this.todos.open(new TodoRequest("job.draft_agreement", hung, Audience.MENTOR, mentored, Map.of("job", "Sửa lỗi"),
					"/cong-viec/" + mentored + "/thoa-thuan", null, mentored));
			this.todos.open(new TodoRequest("repo.reconfirm_source", hung, Audience.CUSTOMER, posted,
					Map.of("job", "Thêm tính năng"), "/cong-viec/" + posted, null, posted));
			this.todos.open(new TodoRequest("identity.link_github", hung, Audience.SUBJECT, hung, Map.of(),
					"/ho-so/xac-minh", null, null));
		});

		assertThat(this.inbox.todos(hung, null)).extracting(todo -> todo.key() + " " + todo.role())
			.containsExactlyInAnyOrder("identity.link_github null", "job.draft_agreement MENTOR",
					"repo.reconfirm_source CUSTOMER");
		assertThat(this.inbox.todos(lan, null)).isEmpty();
		this.mvc.perform(get("/api/v1/todos").param("jobId", mentored.toString())
			.with(authentication(IdentityTestSupport.authentication(hung, Role.MENTOR))))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].role").value("MENTOR"));
	}

	/** A Job in Agreement of {@code customer} on its own Product, with {@code mentor} selected, or an Open one. */
	private UUID job(UUID customer, UUID mentor) {
		UUID product = UUID.randomUUID();
		UUID id = UUID.randomUUID();
		java.sql.Timestamp now = java.sql.Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', :now, :now)
				""").param("id", product).param("owner", customer)
			.param("github", java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :customer, :state, 'Việc', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, :mentor, :application, :selected, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", product)
			.param("customer", customer)
			.param("state", mentor == null ? "OPEN" : "AGREEMENT")
			.param("now", now)
			.param("lapses", java.sql.Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", mentor == null ? null : UUID.randomUUID())
			.param("selected", mentor == null ? null : now)
			.update();
		return id;
	}

	private void open(String key, UUID account, Map<String, String> params, Instant deadline) {
		inTransaction(() -> this.todos.open(request(key, account, UUID.randomUUID(), params, deadline)));
	}

	private static TodoRequest request(String key, UUID account, UUID subject, Map<String, String> params,
			Instant deadline) {
		return new TodoRequest(key, account, Audience.SUBJECT, subject, params, "/viec-can-lam", deadline, null);
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> work.run());
	}

}
