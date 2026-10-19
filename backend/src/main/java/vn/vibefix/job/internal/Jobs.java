package vn.vibefix.job.internal;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.scanning.Scans;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * A Customer's Jobs (FR-9): Drafts with the guided answers, Job Types, Skills, budget range, deadline,
 * and screenshots. Assessment never combines with another Job Type, and no Code Job Type is taken
 * while the Product has an open Intake Finding (FR-8). Only the Job's Customer reads or edits it.
 */
@Component
class Jobs {

	/** Skills on a Job Post (FR-9 seed limit). */
	static final int MAX_SKILLS = 10;

	/** The screenshots of one Job (AD-34). */
	static final long FILE_QUOTA_BYTES = 200L * 1024 * 1024;

	static final String SCREENSHOT_PURPOSE = "job.screenshot";

	/** What CUS-6 saves; every field but the title may wait until publishing. */
	record Draft(String title, @Nullable String problem, @Nullable String expected, @Nullable String appUsers,
			@Nullable String doneCheck, Set<JobType> jobTypes, List<UUID> skillIds, @Nullable Budget budget,
			@Nullable LocalDate deadline) {
	}

	record Screenshot(UUID fileId, String fileName, long sizeBytes, Instant addedAt) {
	}

	record SkillOnJob(UUID id, String name, boolean active) {
	}

	record Job(UUID id, UUID productId, UUID customerId, JobState state, String title, @Nullable String problem,
			@Nullable String expected, @Nullable String appUsers, @Nullable String doneCheck, List<JobType> jobTypes,
			List<UUID> skillIds, List<String> skillNames, @Nullable Budget budget, @Nullable LocalDate deadline,
			Instant createdAt, Instant updatedAt) {
	}

	private static final String COLUMNS = """
			id, product_id, customer_account_id, state, title, problem, expected, app_users, done_check, job_types,
			skill_ids, skill_names, budget, deadline, created_at, updated_at
			""";

	private final JdbcClient jdbc;

	private final ProductDirectory products;

	private final Scans scans;

	private final Catalog catalog;

	private final StoredFiles files;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	Jobs(JdbcClient jdbc, ProductDirectory products, Scans scans, Catalog catalog, StoredFiles files, AuditLog auditLog,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.products = products;
		this.scans = scans;
		this.catalog = catalog;
		this.files = files;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Drafts a Job on the Customer's Product.
	 * @throws ApiException {@code NOT_FOUND} for a Product that is not the Customer's; as {@link #check}
	 */
	@Transactional
	UUID create(UUID customerId, UUID productId, Draft draft) {
		ProductInfo product = this.products.find(productId)
			.filter(found -> found.customerId().equals(customerId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		check(product.id(), draft, List.of());
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		List<Skill> skills = this.catalog.byIds(draft.skillIds());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    done_check, job_types, skill_ids, skill_names, budget, deadline, created_at, updated_at)
				VALUES (:id, :product, :customer, 'DRAFT', :title, :problem, :expected, :appUsers, :doneCheck,
				    CAST(:types AS TEXT[]), CAST(:skillIds AS UUID[]), CAST(:skillNames AS TEXT[]), :budget, :deadline,
				    :now, :now)
				""")
			.param("id", id)
			.param("product", product.id())
			.param("customer", customerId)
			.params(fields(draft, skills))
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(customerId), "job.drafted", AuditObject.of("job.job", id)));
		return id;
	}

	/**
	 * Saves the Customer's Draft.
	 * @throws ApiException {@code NOT_FOUND} for a Job that is not the Customer's; {@code CONFLICT
	 * JOB_NOT_DRAFT} once published; as {@link #check}
	 */
	@Transactional
	void update(UUID customerId, UUID jobId, Draft draft) {
		Job job = lockDraft(customerId, jobId);
		check(job.productId(), draft, job.skillIds());
		List<Skill> skills = this.catalog.byIds(draft.skillIds());
		this.jdbc.sql("""
				UPDATE job.job
				SET title = :title, problem = :problem, expected = :expected, app_users = :appUsers,
				    done_check = :doneCheck, job_types = CAST(:types AS TEXT[]), skill_ids = CAST(:skillIds AS UUID[]),
				    skill_names = CAST(:skillNames AS TEXT[]), budget = :budget, deadline = :deadline, updated_at = :now
				WHERE id = :id
				""")
			.param("id", jobId)
			.params(fields(draft, skills))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	/**
	 * Refuses a Draft that breaks a rule: Assessment with another type, a Code Job Type while an Intake
	 * Finding is open, more than 10 Skills, or an added Skill that is inactive.
	 */
	private void check(UUID productId, Draft draft, List<UUID> kept) {
		if (draft.jobTypes().contains(JobType.ASSESSMENT) && draft.jobTypes().size() > 1) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.ASSESSMENT_ALONE);
		}
		boolean code = draft.jobTypes().stream().anyMatch(JobType::code);
		if (code && this.scans.openFindingCounts(List.of(productId)).getOrDefault(productId, 0) > 0) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.OPEN_FINDINGS);
		}
		Set<UUID> chosen = new LinkedHashSet<>(draft.skillIds());
		this.catalog.requireAtMost(chosen.size(), MAX_SKILLS);
		Set<UUID> added = new LinkedHashSet<>(chosen);
		added.removeAll(kept);
		this.catalog.requireActive(added);
	}

	private static Map<String, Object> fields(Draft draft, List<Skill> skills) {
		Map<String, Object> fields = new java.util.HashMap<>();
		fields.put("title", draft.title().strip());
		fields.put("problem", blankToNull(draft.problem()));
		fields.put("expected", blankToNull(draft.expected()));
		fields.put("appUsers", blankToNull(draft.appUsers()));
		fields.put("doneCheck", blankToNull(draft.doneCheck()));
		fields.put("types", "{" + String.join(",", draft.jobTypes().stream().sorted().map(Enum::name).toList()) + "}");
		fields.put("skillIds", skills.stream().map(Skill::id).toArray(UUID[]::new));
		fields.put("skillNames", skills.stream().map(Skill::name).toArray(String[]::new));
		fields.put("budget", draft.budget() == null ? null : draft.budget().name());
		fields.put("deadline", draft.deadline() == null ? null : Date.valueOf(draft.deadline()));
		return fields;
	}

	private static @Nullable String blankToNull(@Nullable String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	/** The Customer's Jobs, most recently changed first (CUS-7). */
	@Transactional(readOnly = true)
	List<Job> list(UUID customerId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM job.job WHERE customer_account_id = :customer ORDER BY updated_at DESC, id
				""").param("customer", customerId).query(Jobs::job).list();
	}

	/** The Customer's Job. */
	@Transactional(readOnly = true)
	Optional<Job> find(UUID customerId, UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM job.job WHERE id = :id AND customer_account_id = :customer")
			.param("id", jobId)
			.param("customer", customerId)
			.query(Jobs::job)
			.optional();
	}

	/** The Job's Skills with their stored names and whether each is still active. */
	List<SkillOnJob> skills(Job job) {
		Map<UUID, Boolean> active = new java.util.HashMap<>();
		this.catalog.byIds(job.skillIds()).forEach(skill -> active.put(skill.id(), skill.active()));
		List<SkillOnJob> skills = new ArrayList<>();
		for (int i = 0; i < job.skillIds().size(); i++) {
			UUID id = job.skillIds().get(i);
			skills.add(new SkillOnJob(id, job.skillNames().get(i), active.getOrDefault(id, false)));
		}
		return skills;
	}

	@Transactional(readOnly = true)
	List<Screenshot> screenshots(UUID jobId) {
		return this.jdbc.sql("""
				SELECT file_id, file_name, size_bytes, added_at FROM job.job_screenshot WHERE job_id = :job
				ORDER BY added_at, file_id
				""")
			.param("job", jobId)
			.query((rs, n) -> new Screenshot(rs.getObject("file_id", UUID.class), rs.getString("file_name"),
					rs.getLong("size_bytes"), rs.getTimestamp("added_at").toInstant()))
			.list();
	}

	/** Checks a screenshot, outside any transaction: an image goes through the sandbox (AD-21). */
	PreparedFile prepareScreenshot(UUID customerId, String fileName, byte[] content) {
		return this.files.prepare(new FileUpload(SCREENSHOT_PURPOSE, customerId, fileName, content,
				Set.of(FileKind.IMAGE, FileKind.PDF)));
	}

	/**
	 * Adds a checked screenshot to the Customer's Draft, within 200 MB per Job.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT JOB_NOT_DRAFT}; {@code VALIDATION JOB_FILE_QUOTA}
	 */
	@Transactional
	Screenshot addScreenshot(UUID customerId, UUID jobId, PreparedFile file) {
		lockDraft(customerId, jobId);
		long used = this.jdbc.sql("SELECT coalesce(sum(size_bytes), 0) FROM job.job_screenshot WHERE job_id = :job")
			.param("job", jobId)
			.query(Long.class)
			.single();
		if (used + file.size() > FILE_QUOTA_BYTES) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.JOB_FILE_QUOTA);
		}
		StoredFile stored = this.files.store(file);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job_screenshot (job_id, file_id, file_name, size_bytes, added_at)
				VALUES (:job, :file, :name, :size, :now)
				""")
			.param("job", jobId)
			.param("file", stored.id())
			.param("name", stored.fileName())
			.param("size", stored.size())
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("UPDATE job.job SET updated_at = :now WHERE id = :id")
			.param("id", jobId)
			.param("now", Timestamp.from(now))
			.update();
		return new Screenshot(stored.id(), stored.fileName(), stored.size(), now);
	}

	/** Removes a screenshot from the Customer's Draft and deletes the file. */
	@Transactional
	void removeScreenshot(UUID customerId, UUID jobId, UUID fileId) {
		lockDraft(customerId, jobId);
		int removed = this.jdbc.sql("DELETE FROM job.job_screenshot WHERE job_id = :job AND file_id = :file")
			.param("job", jobId)
			.param("file", fileId)
			.update();
		if (removed == 0) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.files.delete(fileId);
	}

	/** Whether {@code accountId} is the Customer of the Job holding the screenshot. */
	@Transactional(readOnly = true)
	boolean ownsScreenshot(UUID fileId, UUID accountId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.job_screenshot s JOIN job.job j ON j.id = s.job_id
				               WHERE s.file_id = :file AND j.customer_account_id = :account)
				""").param("file", fileId).param("account", accountId).query(Boolean.class).single();
	}

	private Job lockDraft(UUID customerId, UUID jobId) {
		Job job = this.jdbc.sql("SELECT " + COLUMNS + " FROM job.job WHERE id = :id AND customer_account_id = :customer"
				+ " FOR UPDATE")
			.param("id", jobId)
			.param("customer", customerId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (job.state() != JobState.DRAFT) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.JOB_NOT_DRAFT);
		}
		return job;
	}

	private static Job job(ResultSet rs, int n) throws SQLException {
		Date deadline = rs.getDate("deadline");
		String budget = rs.getString("budget");
		return new Job(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
				rs.getObject("customer_account_id", UUID.class), JobState.valueOf(rs.getString("state")),
				rs.getString("title"), rs.getString("problem"), rs.getString("expected"), rs.getString("app_users"),
				rs.getString("done_check"),
				Arrays.stream(strings(rs.getArray("job_types"))).map(JobType::valueOf).sorted().toList(),
				Arrays.stream((Object[]) rs.getArray("skill_ids").getArray()).map(UUID.class::cast).toList(),
				Arrays.asList(strings(rs.getArray("skill_names"))), budget == null ? null : Budget.valueOf(budget),
				deadline == null ? null : deadline.toLocalDate(), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}

	private static String[] strings(Array array) throws SQLException {
		return (String[]) array.getArray();
	}

}
