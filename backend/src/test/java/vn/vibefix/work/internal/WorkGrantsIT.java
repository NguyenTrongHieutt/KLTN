package vn.vibefix.work.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Kind;
import vn.vibefix.shared.Role;
import vn.vibefix.support.AdminReadGrantsContract;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/** AD-20, AD-8: {@code work}'s Administrator read grants for Work Items meet the Story 1.3 contract. */
class WorkGrantsIT extends IntegrationTest implements AdminReadGrantsContract {

	@Autowired
	private WorkGrants grants;

	@Autowired
	private WorkAccess access;

	@Autowired
	private WorkReads reads;

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
		return AdminReadScope.WORK_ITEMS;
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
		UUID item = this.jdbc.sql("SELECT id FROM work.work_item WHERE job_id = :job").param("job", job)
			.query(UUID.class).single();
		return new Seeded(job, AdminReadScope.WORK_ITEMS, item);
	}

	@Override
	public void read(UUID adminId, Seeded item) {
		Caller admin = Caller.account(adminId, Kind.ADMINISTRATOR, Role.ADMINISTRATOR);
		WorkAccess.BoardViewer viewer = this.access.reader(item.jobId(), admin);
		this.reads.item(viewer, this.boards.item(item.objectId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
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
