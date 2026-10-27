package vn.vibefix.chat.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.chat.internal.Chats.Chat;
import vn.vibefix.chat.internal.Chats.Kind;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;
import vn.vibefix.support.AdminReadGrantsContract;
import vn.vibefix.support.IntegrationTest;

/** AD-20, AD-8: the Job Chat meets the Administrator read-grant contract. */
class ChatGrantsIT extends IntegrationTest implements AdminReadGrantsContract {

	@Autowired
	private ChatGrants grants;

	@Autowired
	private ChatAccess access;

	@Autowired
	private Chats chats;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public AdminReadGrants grants() {
		return this.grants;
	}

	@Override
	public AdminReadScope scope() {
		return AdminReadScope.JOB_CHAT;
	}

	@Override
	public Seeded seed() {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = ChatTestSupport.mentorWithFreeStudent(this.jdbc, "Đỗ Mạnh Hùng");
		UUID product = ChatTestSupport.product(this.jdbc, customer);
		UUID job = ChatTestSupport.job(this.jdbc, product, customer, "AGREEMENT", mentor, this.clock.instant());
		Chat chat = this.chats.open(job, product, Kind.JOB).orElseThrow();
		this.jdbc.sql("INSERT INTO chat.message (id, chat_id, author_account_id, body, sent_at) VALUES (:id, :chat, :author, 'Chào', :now)")
			.param("id", UUID.randomUUID())
			.param("chat", chat.id())
			.param("author", customer)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return new Seeded(job, scope(), chat.id());
	}

	@Override
	public void read(UUID adminId, Seeded item) {
		Caller admin = Caller.account(adminId, vn.vibefix.shared.Kind.ADMINISTRATOR, Role.ADMINISTRATOR);
		this.access.reader(item.jobId(), Kind.JOB, admin);
	}

	@Override
	public long auditedReads(UUID adminId) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'chat.admin_read' AND actor_account_id = :admin")
			.param("admin", adminId)
			.query(Long.class)
			.single();
	}

	@Override
	public Instant now() {
		return this.clock.instant();
	}

	@Override
	public void advance(Duration duration) {
		this.clock.advance(duration);
	}

}
