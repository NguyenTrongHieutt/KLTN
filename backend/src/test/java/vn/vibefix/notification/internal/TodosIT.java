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
			.andExpect(jsonPath("$.items[0].jobId").value(job.toString()));
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
