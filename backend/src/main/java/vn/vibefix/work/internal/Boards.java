package vn.vibefix.work.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriteriaInForce;
import vn.vibefix.job.AgreementCriteria.CriterionInForce;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.WorkItemKey;

/**
 * The Boards and their Work Items (FR-22, FR-23, AD-33): a Board is created once per Job at J7 with
 * its Job code, and seeding adds one Story per Acceptance Criterion in force that the Board does not
 * have yet, numbered after the Board's last key, each with its {@code SEEDED} history entry. Both run
 * under the Product lock and the Board row lock, so seeding twice or at the same time adds nothing.
 */
@Component
class Boards {

	static final JsonMapper JSON = JsonMapper.builder().build();

	enum Type {
		STORY, TASK, BUG
	}

	enum Status {
		TODO, IN_PROGRESS, IN_REVIEW, DONE
	}

	enum Priority {
		HIGH, MEDIUM, LOW
	}

	record Board(UUID jobId, UUID productId, String code) {
	}

	/** One line of a Work Item's criteria checklist. */
	record ChecklistLine(String text, boolean done) {
	}

	/** One Work Item; {@code criterionId} is set only on a seeded Story. */
	record Item(UUID id, UUID jobId, WorkItemKey key, Type type, String title, String description,
			List<ChecklistLine> checklist, @Nullable UUID criterionId, @Nullable UUID milestoneId, Status status,
			@Nullable UUID assigneeId, @Nullable Priority priority, @Nullable BigDecimal estimateHours,
			@Nullable UUID createdBy, long version, Instant createdAt) {

		Item {
			checklist = List.copyOf(checklist);
		}

		boolean seeded() {
			return this.criterionId != null;
		}

	}

	static final String ITEM_COLUMNS = """
			w.id, w.job_id, b.code, w.number, w.type, w.title, w.description, w.checklist::text AS checklist,
			w.criterion_id, w.milestone_id, w.status, w.assignee_account_id, w.priority, w.estimate_hours,
			w.created_by_account_id, w.version, w.created_at
			""";

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
		int added = 0;
		Timestamp now = Timestamp.from(this.clock.instant());
		for (CriterionInForce criterion : inForce.criteria()) {
			if (seeded.contains(criterion.criterionId())) {
				continue;
			}
			UUID id = this.ids.newId();
			int number = nextNumber(jobId);
			this.jdbc.sql("""
					INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id,
					    created_at, updated_at)
					VALUES (:id, :job, :number, 'STORY', :title, :description, :criterion, :milestone, :now, :now)
					""")
				.param("id", id)
				.param("job", jobId)
				.param("number", number)
				.param("title", criterion.statement())
				.param("description", criterion.checkSteps())
				.param("criterion", criterion.criterionId())
				.param("milestone", criterion.milestoneId())
				.param("now", now)
				.update();
			Map<String, Object> changes = new LinkedHashMap<>();
			changes.put("criterionId", Map.of("to", criterion.criterionId().toString()));
			history(id, jobId, null, "SEEDED", changes, null);
			added++;
		}
		return added;
	}

	/** Takes the Board's next key number; the Board row is locked for the rest of the transaction. */
	int nextNumber(UUID jobId) {
		return this.jdbc.sql("""
				UPDATE work.board SET next_number = next_number + 1 WHERE job_id = :job RETURNING next_number - 1
				""").param("job", jobId).query(Integer.class).single();
	}

	/**
	 * Appends a history entry (AD-39): who, what changed from what to what, and the assignee it gave.
	 * @return the entry's ID
	 */
	UUID history(UUID itemId, UUID jobId, @Nullable UUID actor, String action, Map<String, Object> changes,
			@Nullable UUID assignedTo) {
		return history(itemId, jobId, actor, action, changes, assignedTo, null, null);
	}

	/**
	 * Appends a history entry with a review's note or comment and Self-check answers (FR-25).
	 * @return the entry's ID
	 */
	UUID history(UUID itemId, UUID jobId, @Nullable UUID actor, String action, Map<String, Object> changes,
			@Nullable UUID assignedTo, @Nullable String comment, @Nullable Map<String, Boolean> selfCheck) {
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, assigned_to,
				    comment, self_check, at)
				VALUES (:id, :item, :job, :actor, :action, CAST(:changes AS JSONB), :assigned, :comment,
				    CAST(:selfCheck AS JSONB), :now)
				""")
			.param("id", id)
			.param("item", itemId)
			.param("job", jobId)
			.param("actor", actor)
			.param("action", action)
			.param("changes", JSON.writeValueAsString(changes))
			.param("assigned", assignedTo)
			.param("comment", comment)
			.param("selfCheck", selfCheck == null ? null : JSON.writeValueAsString(selfCheck))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return id;
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
		return this.jdbc.sql("SELECT " + ITEM_COLUMNS
				+ " FROM work.work_item w JOIN work.board b ON b.job_id = w.job_id WHERE w.job_id = :job ORDER BY w.number")
			.param("job", board.jobId())
			.query(Boards::item)
			.list();
	}

	@Transactional(readOnly = true)
	Optional<Item> item(UUID itemId) {
		return this.jdbc.sql("SELECT " + ITEM_COLUMNS
				+ " FROM work.work_item w JOIN work.board b ON b.job_id = w.job_id WHERE w.id = :id")
			.param("id", itemId)
			.query(Boards::item)
			.optional();
	}

	/** The Work Item, locked for the rest of the transaction. */
	Optional<Item> lockItem(UUID itemId) {
		return this.jdbc.sql("SELECT " + ITEM_COLUMNS
				+ " FROM work.work_item w JOIN work.board b ON b.job_id = w.job_id WHERE w.id = :id FOR UPDATE OF w")
			.param("id", itemId)
			.query(Boards::item)
			.optional();
	}

	/** The Board, locked for the rest of the transaction: Page tree changes run under it. */
	Optional<Board> lockBoard(UUID jobId) {
		return lock(jobId);
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

	static Item item(ResultSet rs, int n) throws SQLException {
		String priority = rs.getString("priority");
		return new Item(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				new WorkItemKey(rs.getString("code"), rs.getInt("number")), Type.valueOf(rs.getString("type")),
				rs.getString("title"), rs.getString("description"),
				JSON.readValue(rs.getString("checklist"), new TypeReference<List<ChecklistLine>>() {
				}), rs.getObject("criterion_id", UUID.class), rs.getObject("milestone_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getObject("assignee_account_id", UUID.class),
				priority == null ? null : Priority.valueOf(priority), rs.getBigDecimal("estimate_hours"),
				rs.getObject("created_by_account_id", UUID.class), rs.getLong("version"),
				rs.getTimestamp("created_at").toInstant());
	}

}
