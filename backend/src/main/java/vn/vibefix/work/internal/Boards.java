package vn.vibefix.work.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriteriaInForce;
import vn.vibefix.job.AgreementCriteria.CriterionInForce;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.WorkItemKey;

/**
 * The Boards and their Work Items (FR-22, AD-33): a Board is created once per Job at J7 with its Job
 * code, and seeding adds one Story per Acceptance Criterion in force that the Board does not have yet,
 * numbered after the Board's last key. Both run under the Product lock and the Board row lock, so
 * seeding twice or at the same time adds nothing.
 */
@Component
class Boards {

	enum Type {
		STORY, TASK, BUG
	}

	enum Status {
		TODO, IN_PROGRESS, IN_REVIEW, DONE
	}

	record Board(UUID jobId, UUID productId, String code) {
	}

	/** One Work Item; {@code criterionId} is set only on a seeded Story. */
	record Item(UUID id, UUID jobId, WorkItemKey key, Type type, String title, String description,
			@Nullable UUID criterionId, @Nullable UUID milestoneId, Status status, @Nullable UUID assigneeId, long version,
			Instant createdAt) {

		boolean seeded() {
			return this.criterionId != null;
		}

	}

	private final JdbcClient jdbc;

	private final AgreementCriteria criteria;

	private final ProductDirectory products;

	private final IdGenerator ids;

	private final Clock clock;

	Boards(JdbcClient jdbc, AgreementCriteria criteria, ProductDirectory products, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.criteria = criteria;
		this.products = products;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Seeds the Job's backlog from the criteria in force (FR-22).
	 * @param create whether to create the Board when the Job has none (J7); a later Agreement version
	 * seeds only an existing Board
	 * @return how many Stories it added
	 */
	@Transactional
	int seed(UUID jobId, boolean create) {
		CriteriaInForce inForce = this.criteria.inForce(jobId).orElse(null);
		if (inForce == null) {
			return 0;
		}
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:product, 0))")
			.param("product", inForce.productId().toString())
			.query((rs, n) -> null)
			.optional();
		Board board = lock(jobId).orElse(null);
		if (board == null) {
			if (!create) {
				return 0;
			}
			board = create(jobId, inForce.productId());
		}
		Set<UUID> seeded = new HashSet<>(this.jdbc.sql("""
				SELECT criterion_id FROM work.work_item WHERE job_id = :job AND criterion_id IS NOT NULL
				""").param("job", jobId).query(UUID.class).list());
		int next = this.jdbc.sql("SELECT next_number FROM work.board WHERE job_id = :job")
			.param("job", jobId)
			.query(Integer.class)
			.single();
		int added = 0;
		Timestamp now = Timestamp.from(this.clock.instant());
		for (CriterionInForce criterion : inForce.criteria()) {
			if (seeded.contains(criterion.criterionId())) {
				continue;
			}
			this.jdbc.sql("""
					INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id,
					    created_at, updated_at)
					VALUES (:id, :job, :number, 'STORY', :title, :description, :criterion, :milestone, :now, :now)
					""")
				.param("id", this.ids.newId())
				.param("job", jobId)
				.param("number", next + added)
				.param("title", criterion.statement())
				.param("description", criterion.checkSteps())
				.param("criterion", criterion.criterionId())
				.param("milestone", criterion.milestoneId())
				.param("now", now)
				.update();
			added++;
		}
		if (added > 0) {
			this.jdbc.sql("UPDATE work.board SET next_number = next_number + :added WHERE job_id = :job")
				.param("added", added)
				.param("job", jobId)
				.update();
		}
		return added;
	}

	@Transactional(readOnly = true)
	Optional<Board> board(UUID jobId) {
		return this.jdbc.sql("SELECT job_id, product_id, code FROM work.board WHERE job_id = :job")
			.param("job", jobId)
			.query(Boards::board)
			.optional();
	}

	/** The Board's Work Items in key order. */
	@Transactional(readOnly = true)
	List<Item> items(Board board) {
		return this.jdbc.sql("""
				SELECT id, job_id, number, type, title, description, criterion_id, milestone_id, status, assignee_account_id,
				       version, created_at
				FROM work.work_item WHERE job_id = :job ORDER BY number
				""").param("job", board.jobId()).query((rs, n) -> item(rs, board.code())).list();
	}

	private Optional<Board> lock(UUID jobId) {
		return this.jdbc.sql("SELECT job_id, product_id, code FROM work.board WHERE job_id = :job FOR UPDATE")
			.param("job", jobId)
			.query(Boards::board)
			.optional();
	}

	private Board create(UUID jobId, UUID productId) {
		String name = this.products.find(productId).map(ProductInfo::name).orElse("");
		Set<String> taken = new HashSet<>(this.jdbc.sql("SELECT code FROM work.board WHERE product_id = :product")
			.param("product", productId)
			.query(String.class)
			.list());
		String code = JobCodes.unique(JobCodes.base(name), taken);
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, created_at) VALUES (:job, :product, :code, :now)
				""")
			.param("job", jobId)
			.param("product", productId)
			.param("code", code)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return new Board(jobId, productId, code);
	}

	private static Board board(ResultSet rs, int n) throws SQLException {
		return new Board(rs.getObject("job_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("code"));
	}

	private static Item item(ResultSet rs, String code) throws SQLException {
		return new Item(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				new WorkItemKey(code, rs.getInt("number")), Type.valueOf(rs.getString("type")), rs.getString("title"),
				rs.getString("description"), rs.getObject("criterion_id", UUID.class),
				rs.getObject("milestone_id", UUID.class), Status.valueOf(rs.getString("status")),
				rs.getObject("assignee_account_id", UUID.class), rs.getLong("version"),
				rs.getTimestamp("created_at").toInstant());
	}

}
