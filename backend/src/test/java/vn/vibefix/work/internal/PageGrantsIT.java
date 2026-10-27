package vn.vibefix.work.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Kind;
import vn.vibefix.support.AdminReadGrantsContract;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/** AD-20, AD-8: {@code work}'s Administrator read grants for Pages meet the Story 1.3 contract. */
class PageGrantsIT extends IntegrationTest implements AdminReadGrantsContract {

	@Autowired
	private WorkGrants grants;

	@Autowired
	private WorkAccess access;

	@Autowired
	private Pages pages;

	@Autowired
	private Boards boards;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public AdminReadGrants grants() {
		return this.grants;
	}

	@Override
	public AdminReadScope scope() {
		return AdminReadScope.PAGES;
	}

	@Override
	public Seeded seed() {
		UUID owner = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID mentor = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, owner, "Sổ đặt bàn Quán Mây");
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, owner, mentor, List.of(UUID.randomUUID()),
				List.of(List.of(UUID.randomUUID())), this.clock.instant());
		this.boards.seed(job, true);
		UUID page = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_by_account_id, created_at, updated_at)
				VALUES (:id, :job, 'Kế hoạch', 1, :mentor, :now, :now)
				""").param("id", page).param("job", job).param("mentor", mentor).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, created_at)
				VALUES (:id, 1, 'Kế hoạch', 'Nội dung', :mentor, :now)
				""").param("id", page).param("mentor", mentor).param("now", now).update();
		return new Seeded(job, AdminReadScope.PAGES, page);
	}

	@Override
	public void read(UUID adminId, Seeded item) {
		Caller admin = Caller.account(adminId, Kind.ADMINISTRATOR);
		WorkAccess.BoardViewer viewer = this.access.reader(item.jobId(), admin, AdminReadScope.PAGES);
		this.pages.seen(viewer, this.pages.page(item.objectId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	@Override
	public long auditedReads(UUID adminId) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'work.admin_read' AND actor_account_id = :admin")
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
