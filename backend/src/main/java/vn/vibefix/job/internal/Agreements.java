package vn.vibefix.job.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * The Agreement draft (FR-16, AD-29): the Mentor edits live Milestones, Acceptance Criteria, and
 * External Access entries whose UUIDs never change, saving only on "Lưu"; sharing checks the draft and
 * writes the next immutable version for the Customer. Payment shares never appear here (AD-41). An
 * Assessment Job's draft is the FR-10 template, of which only the price and the due date change.
 */
@Component
class Agreements {

	static final String DRAFT_TODO = "job.draft_agreement";

	static final String REVIEW_TODO = "job.review_agreement";

	static final String DRAFT_READY = "job.agreement_draft_ready";

	static final String REVISE_TODO = "job.revise_agreement";

	static final int MAX_MILESTONES = 20;

	static final int MAX_CRITERIA = 30;

	static final int MAX_EXTERNAL_ACCESS = 20;

	static final int MAX_SKILLS = 10;

	enum Environment {
		NON_PRODUCTION, PRODUCTION
	}

	enum AccessLevel {
		READ, WRITE, ADMIN
	}

	/** Why a draft cannot be shared yet (FR-16). */
	enum ProblemCode {
		SCOPE_MISSING, PRICE_MISSING, MILESTONE_MISSING, MILESTONE_INCOMPLETE, MILESTONE_WITHOUT_CRITERION,
		CRITERION_WITHOUT_STEPS, PRICE_SUM_MISMATCH, EXTERNAL_ACCESS_INCOMPLETE
	}

	/** A problem, naming the entity it is about when there is one. */
	record Problem(ProblemCode code, @Nullable UUID milestoneId, @Nullable UUID criterionId,
			@Nullable UUID externalAccessId) {
	}

	record Criterion(UUID id, String statement, String checkSteps) {
	}

	record Milestone(UUID id, String title, @Nullable Long priceVnd, @Nullable LocalDate dueOn, int revisionRounds,
			List<Criterion> criteria) {
	}

	record ExternalAccess(UUID id, String system, String purpose, Environment environment, AccessLevel accessLevel) {
	}

	record SkillName(UUID id, String name) {
	}

	/** The live draft, or a version's content: what an Agreement says, never who is paid what. */
	record Content(String scope, @Nullable Long priceVnd, boolean codeJob, boolean assessment, List<SkillName> skills,
			List<Milestone> milestones, List<ExternalAccess> externalAccess) {
	}

	record Draft(UUID jobId, int revision, Content content) {
	}

	/** One shared version. */
	record Version(int versionNo, Instant sharedAt, Content content) {
	}

	record VersionSummary(int versionNo, Instant sharedAt) {
	}

	/** One entity of a save; {@code id} is the client's once-created UUID. */
	record MilestoneInput(UUID id, String title, @Nullable Long priceVnd, @Nullable LocalDate dueOn, int revisionRounds,
			List<CriterionInput> criteria) {
	}

	record CriterionInput(UUID id, String statement, String checkSteps) {
	}

	record ExternalAccessInput(UUID id, String system, String purpose, Environment environment, AccessLevel accessLevel) {
	}

	record SaveCommand(int revision, String scope, @Nullable Long priceVnd, List<UUID> skillIds,
			List<MilestoneInput> milestones, List<ExternalAccessInput> externalAccess) {
	}

	/** The FR-10 template, from {@code job/assessment-report-template.yaml}. */
	record Template(String milestone, List<Criterion> criteria) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	private final Catalog catalog;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	private final Template template;

	Agreements(JdbcClient jdbc, Catalog catalog, Notifications notifications, Todos todos, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
		this.template = loadTemplate();
	}

	/**
	 * J4 (FR-16): the draft starts from the Job Post's Skills, the quote's price, and the copied
	 * Revision Rounds; an Assessment Job's from the template; the Mentor's to-do opens. Runs in J4's
	 * transaction.
	 */
	void start(Job job, Instant agreementDueAt) {
		Instant now = this.clock.instant();
		Long quote = this.jdbc.sql("SELECT quote_price_vnd FROM job.job WHERE id = :id")
			.param("id", job.id())
			.query(Long.class)
			.optional()
			.orElse(null);
		this.jdbc.sql("""
				INSERT INTO job.agreement_draft (job_id, price_vnd, skill_ids, skill_names, saved_at)
				VALUES (:job, :price, CAST(:skills AS UUID[]), CAST(:names AS TEXT[]), :now)
				ON CONFLICT (job_id) DO NOTHING
				""")
			.param("job", job.id())
			.param("price", quote)
			.param("skills", job.skillIds().toArray(UUID[]::new))
			.param("names", job.skillNames().toArray(String[]::new))
			.param("now", Timestamp.from(now))
			.update();
		if (assessment(job)) {
			UUID milestone = UUID.randomUUID();
			insertMilestone(job.id(), new MilestoneInput(milestone, this.template.milestone(), quote, null,
					revisionRounds(job.id()), List.of()), 0, now);
			for (int i = 0; i < this.template.criteria().size(); i++) {
				Criterion criterion = this.template.criteria().get(i);
				insertCriterion(job.id(), milestone,
						new CriterionInput(UUID.randomUUID(), criterion.statement(), criterion.checkSteps()), i, now);
			}
		}
		this.todos.open(new TodoRequest(DRAFT_TODO, Objects.requireNonNull(job.mentorId()), Audience.MENTOR, job.id(),
				Map.of("job", job.title()), agreementLink(job.id()), agreementDueAt, job.id()));
	}

	/** The Mentor's live draft. */
	@Transactional(readOnly = true)
	Optional<Draft> draft(Job job) {
		return this.jdbc.sql("SELECT scope, price_vnd, skill_ids, revision FROM job.agreement_draft WHERE job_id = :job")
			.param("job", job.id())
			.query((rs, n) -> new Draft(job.id(), rs.getInt("revision"),
					new Content(rs.getString("scope"), nullableLong(rs, "price_vnd"), job.code(), assessment(job),
							skills(uuids(rs.getArray("skill_ids"))), milestones(job.id()), externalAccess(job.id()))))
			.optional();
	}

	/**
	 * Saves the whole draft at the revision the editor loaded (AD-23); an incomplete draft saves.
	 * @throws ApiException {@code STALE} for another revision or a Job past Agreement;
	 * {@code GUARD_FAILED AGREEMENT_CONFIRMED} once confirmed; {@code VALIDATION} for an entity of another
	 * Job or a limit passed
	 */
	@Transactional
	Draft save(UUID mentorId, UUID jobId, SaveCommand command) {
		Job locked = lockMentorJob(mentorId, jobId);
		requireAgreement(locked);
		requireUnconfirmed(locked);
		Draft current = draft(locked).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (current.revision() != command.revision()) {
			throw new ApiException(ErrorCode.STALE);
		}
		Instant now = this.clock.instant();
		if (current.content().assessment()) {
			saveAssessment(locked, current, command);
		}
		else {
			saveEntities(locked.id(), command, now);
		}
		List<Skill> skills = current.content().assessment() ? List.of() : this.catalog.byIds(command.skillIds());
		if (!current.content().assessment()) {
			if (command.skillIds().size() > MAX_SKILLS || skills.size() != command.skillIds().stream().distinct().count()) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
		}
		Map<UUID, String> names = new HashMap<>();
		skills.forEach(skill -> names.put(skill.id(), skill.name()));
		List<UUID> ids = current.content().assessment() ? current.content().skills().stream().map(SkillName::id).toList()
				: command.skillIds().stream().distinct().toList();
		this.jdbc.sql("""
				UPDATE job.agreement_draft SET scope = :scope, price_vnd = :price, skill_ids = CAST(:skills AS UUID[]),
				    skill_names = CAST(:names AS TEXT[]), revision = revision + 1, saved_at = :now
				WHERE job_id = :job
				""")
			.param("job", locked.id())
			.param("scope", current.content().assessment() ? current.content().scope() : command.scope().strip())
			.param("price", command.priceVnd())
			.param("skills", ids.toArray(UUID[]::new))
			.param("names", ids.stream()
				.map(id -> names.getOrDefault(id, nameIn(current, id)))
				.toArray(String[]::new))
			.param("now", Timestamp.from(now))
			.update();
		return draft(locked).orElseThrow();
	}

	/** What keeps the draft from being shared, in the order the editor lists them. */
	List<Problem> problems(Content content) {
		List<Problem> problems = new ArrayList<>();
		if (!content.assessment() && content.scope().isBlank()) {
			problems.add(new Problem(ProblemCode.SCOPE_MISSING, null, null, null));
		}
		if (content.priceVnd() == null) {
			problems.add(new Problem(ProblemCode.PRICE_MISSING, null, null, null));
		}
		if (content.milestones().isEmpty()) {
			problems.add(new Problem(ProblemCode.MILESTONE_MISSING, null, null, null));
		}
		long sum = 0;
		for (Milestone milestone : content.milestones()) {
			if (milestone.title().isBlank() || milestone.priceVnd() == null || milestone.dueOn() == null) {
				problems.add(new Problem(ProblemCode.MILESTONE_INCOMPLETE, milestone.id(), null, null));
			}
			if (milestone.criteria().isEmpty()) {
				problems.add(new Problem(ProblemCode.MILESTONE_WITHOUT_CRITERION, milestone.id(), null, null));
			}
			for (Criterion criterion : milestone.criteria()) {
				if (criterion.statement().isBlank() || criterion.checkSteps().isBlank()) {
					problems.add(new Problem(ProblemCode.CRITERION_WITHOUT_STEPS, milestone.id(), criterion.id(), null));
				}
			}
			sum += milestone.priceVnd() == null ? 0 : milestone.priceVnd();
		}
		if (content.priceVnd() != null && !content.milestones().isEmpty() && sum != content.priceVnd()) {
			problems.add(new Problem(ProblemCode.PRICE_SUM_MISMATCH, null, null, null));
		}
		for (ExternalAccess access : content.externalAccess()) {
			if (access.system().isBlank() || access.purpose().isBlank()) {
				problems.add(new Problem(ProblemCode.EXTERNAL_ACCESS_INCOMPLETE, null, null, access.id()));
			}
		}
		return problems;
	}

	/**
	 * "Gửi cho khách hàng" (FR-16): the complete draft becomes the next shared version, with no further
	 * gate; the Customer is told and their review to-do opens. It answers every open change request
	 * (Story 4.4) and ends the Mentor's revision to-do.
	 * @throws ApiException {@code STALE}; {@code GUARD_FAILED AGREEMENT_CONFIRMED} once confirmed;
	 * {@code VALIDATION AGREEMENT_INCOMPLETE} with {@code problems}
	 */
	@Transactional
	Version share(UUID mentorId, UUID jobId, int revision) {
		Job locked = lockMentorJob(mentorId, jobId);
		requireAgreement(locked);
		requireUnconfirmed(locked);
		Draft draft = draft(locked).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (draft.revision() != revision) {
			throw new ApiException(ErrorCode.STALE);
		}
		List<Problem> problems = problems(draft.content());
		if (!problems.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.AGREEMENT_INCOMPLETE, Map.of("problems", problems));
		}
		int next = this.jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM job.agreement_version WHERE job_id = :job")
			.param("job", locked.id())
			.query(Integer.class)
			.single();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, :no, 'SHARED', CAST(:content AS JSONB), :author, :now)
				""")
			.param("job", locked.id())
			.param("no", next)
			.param("content", JSON.writeValueAsString(draft.content()))
			.param("author", mentorId)
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "job.agreement_shared", AuditObject.of("job.job", locked.id()))
			.forJob(locked.id()));
		this.notifications.send(new NotificationRequest(DRAFT_READY, UUID.nameUUIDFromBytes((locked.id() + ":" + next).getBytes()),
				List.of(new Recipient(locked.customerId(), Audience.CUSTOMER)), Map.of("job", locked.title()),
				agreementLink(locked.id()), locked.id(), null));
		this.jdbc.sql("UPDATE job.agreement_change_request SET resolved_at = :now WHERE job_id = :job AND resolved_at IS NULL")
			.param("job", locked.id())
			.param("now", Timestamp.from(now))
			.update();
		this.todos.closeAll(DRAFT_TODO, locked.id());
		this.todos.closeAll(REVISE_TODO, locked.id());
		this.todos.closeAll(REVIEW_TODO, locked.id());
		Instant due = this.jdbc.sql("SELECT agreement_due_at FROM job.job WHERE id = :id")
			.param("id", locked.id())
			.query(Timestamp.class)
			.optional()
			.map(Timestamp::toInstant)
			.orElse(null);
		this.todos.open(new TodoRequest(REVIEW_TODO, locked.customerId(), Audience.CUSTOMER, locked.id(),
				Map.of("job", locked.title()), agreementLink(locked.id()), due, locked.id()));
		return new Version(next, now, draft.content());
	}

	/** The shared versions, oldest first. */
	@Transactional(readOnly = true)
	List<VersionSummary> versions(UUID jobId) {
		return this.jdbc.sql("SELECT version_no, created_at FROM job.agreement_version WHERE job_id = :job ORDER BY version_no")
			.param("job", jobId)
			.query((rs, n) -> new VersionSummary(rs.getInt("version_no"), rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	/** A shared version, the latest when {@code versionNo} is {@code null}. */
	@Transactional(readOnly = true)
	Optional<Version> version(UUID jobId, @Nullable Integer versionNo) {
		String which = versionNo == null ? "(SELECT max(version_no) FROM job.agreement_version WHERE job_id = :job)"
				: ":no";
		return this.jdbc.sql("SELECT version_no, created_at, content::text AS content FROM job.agreement_version "
				+ "WHERE job_id = :job AND version_no = " + which)
			.param("job", jobId)
			.params(versionNo == null ? Map.of() : Map.of("no", versionNo))
			.query((rs, n) -> new Version(rs.getInt("version_no"), rs.getTimestamp("created_at").toInstant(),
					JSON.readValue(rs.getString("content"), Content.class)))
			.optional();
	}

	boolean shared(UUID jobId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.agreement_version WHERE job_id = :job)")
			.param("job", jobId)
			.query(Boolean.class)
			.single();
	}

	/** The Mentor's Job, locked for the edit (AD-4); anyone else's is {@code NOT_FOUND}. */
	private Job lockMentorJob(UUID mentorId, UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id FOR UPDATE")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.filter(job -> mentorId.equals(job.mentorId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	static String agreementLink(UUID jobId) {
		return "/cong-viec/" + jobId + "/thoa-thuan";
	}

	static boolean assessment(Job job) {
		return job.jobTypes().contains(JobType.ASSESSMENT);
	}

	private void saveAssessment(Job job, Draft current, SaveCommand command) {
		Milestone milestone = current.content().milestones().getFirst();
		MilestoneInput input = command.milestones().stream()
			.filter(found -> found.id().equals(milestone.id()))
			.findFirst()
			.orElse(null);
		this.jdbc.sql("UPDATE job.milestone SET price_vnd = :price, due_on = :due WHERE id = :id AND job_id = :job")
			.param("id", milestone.id())
			.param("job", job.id())
			.param("price", input == null ? milestone.priceVnd() : input.priceVnd())
			.param("due", input == null ? date(milestone.dueOn()) : date(input.dueOn()))
			.update();
	}

	private void saveEntities(UUID jobId, SaveCommand command, Instant now) {
		if (command.milestones().size() > MAX_MILESTONES || command.externalAccess().size() > MAX_EXTERNAL_ACCESS
				|| command.milestones().stream().anyMatch(milestone -> milestone.criteria().size() > MAX_CRITERIA)) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Map<UUID, UUID> milestoneOwners = owners("job.milestone", command.milestones().stream().map(MilestoneInput::id).toList());
		Map<UUID, UUID> criterionOwners = owners("job.acceptance_criterion",
				command.milestones().stream().flatMap(milestone -> milestone.criteria().stream()).map(CriterionInput::id).toList());
		Map<UUID, UUID> accessOwners = owners("job.external_access",
				command.externalAccess().stream().map(ExternalAccessInput::id).toList());
		for (Map<UUID, UUID> owners : List.of(milestoneOwners, criterionOwners, accessOwners)) {
			if (owners.values().stream().anyMatch(owner -> !owner.equals(jobId))) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
		}
		Set<UUID> seen = new HashSet<>();
		Set<UUID> keptCriteria = new HashSet<>();
		for (int m = 0; m < command.milestones().size(); m++) {
			MilestoneInput milestone = command.milestones().get(m);
			if (!seen.add(milestone.id())) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			if (milestoneOwners.containsKey(milestone.id())) {
				this.jdbc.sql("""
						UPDATE job.milestone SET position = :position, title = :title, price_vnd = :price, due_on = :due,
						    revision_rounds = :rounds, removed_at = NULL
						WHERE id = :id
						""")
					.param("id", milestone.id())
					.param("position", m)
					.param("title", milestone.title().strip())
					.param("price", milestone.priceVnd())
					.param("due", date(milestone.dueOn()))
					.param("rounds", milestone.revisionRounds())
					.update();
			}
			else {
				insertMilestone(jobId, milestone, m, now);
			}
			for (int c = 0; c < milestone.criteria().size(); c++) {
				CriterionInput criterion = milestone.criteria().get(c);
				if (!seen.add(criterion.id())) {
					throw new ApiException(ErrorCode.VALIDATION);
				}
				keptCriteria.add(criterion.id());
				if (criterionOwners.containsKey(criterion.id())) {
					this.jdbc.sql("""
							UPDATE job.acceptance_criterion SET milestone_id = :milestone, position = :position,
							    statement = :statement, check_steps = :steps, removed_at = NULL
							WHERE id = :id
							""")
						.param("id", criterion.id())
						.param("milestone", milestone.id())
						.param("position", c)
						.param("statement", criterion.statement().strip())
						.param("steps", criterion.checkSteps().strip())
						.update();
				}
				else {
					insertCriterion(jobId, milestone.id(), criterion, c, now);
				}
			}
		}
		for (int a = 0; a < command.externalAccess().size(); a++) {
			ExternalAccessInput access = command.externalAccess().get(a);
			if (!seen.add(access.id())) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			this.jdbc.sql("""
					INSERT INTO job.external_access (id, job_id, position, system, purpose, environment, access_level, created_at)
					VALUES (:id, :job, :position, :system, :purpose, :environment, :level, :now)
					ON CONFLICT (id) DO UPDATE SET position = excluded.position, system = excluded.system,
					    purpose = excluded.purpose, environment = excluded.environment, access_level = excluded.access_level,
					    removed_at = NULL
					""")
				.param("id", access.id())
				.param("job", jobId)
				.param("position", a)
				.param("system", access.system().strip())
				.param("purpose", access.purpose().strip())
				.param("environment", access.environment().name())
				.param("level", access.accessLevel().name())
				.param("now", Timestamp.from(now))
				.update();
		}
		Timestamp removed = Timestamp.from(now);
		List<UUID> milestones = command.milestones().stream().map(MilestoneInput::id).toList();
		this.jdbc.sql("UPDATE job.milestone SET removed_at = :now WHERE job_id = :job AND removed_at IS NULL"
				+ (milestones.isEmpty() ? "" : " AND id NOT IN (:kept)"))
			.param("job", jobId)
			.param("now", removed)
			.params(milestones.isEmpty() ? Map.of() : Map.of("kept", milestones))
			.update();
		this.jdbc.sql("UPDATE job.acceptance_criterion SET removed_at = :now WHERE job_id = :job AND removed_at IS NULL"
				+ (keptCriteria.isEmpty() ? "" : " AND id NOT IN (:kept)"))
			.param("job", jobId)
			.param("now", removed)
			.params(keptCriteria.isEmpty() ? Map.of() : Map.of("kept", List.copyOf(keptCriteria)))
			.update();
		List<UUID> accesses = command.externalAccess().stream().map(ExternalAccessInput::id).toList();
		this.jdbc.sql("UPDATE job.external_access SET removed_at = :now WHERE job_id = :job AND removed_at IS NULL"
				+ (accesses.isEmpty() ? "" : " AND id NOT IN (:kept)"))
			.param("job", jobId)
			.param("now", removed)
			.params(accesses.isEmpty() ? Map.of() : Map.of("kept", accesses))
			.update();
	}

	/** Which Job each known ID belongs to; IDs never seen are absent (new entities). */
	private Map<UUID, UUID> owners(String table, List<UUID> ids) {
		Map<UUID, UUID> owners = new HashMap<>();
		if (ids.isEmpty()) {
			return owners;
		}
		this.jdbc.sql("SELECT id, job_id FROM " + table + " WHERE id IN (:ids)")
			.param("ids", ids)
			.query(rs -> {
				owners.put(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class));
			});
		return owners;
	}

	private void insertMilestone(UUID jobId, MilestoneInput milestone, int position, Instant now) {
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at)
				VALUES (:id, :job, :position, :title, :price, :due, :rounds, :now)
				""")
			.param("id", milestone.id())
			.param("job", jobId)
			.param("position", position)
			.param("title", milestone.title().strip())
			.param("price", milestone.priceVnd())
			.param("due", date(milestone.dueOn()))
			.param("rounds", milestone.revisionRounds())
			.param("now", Timestamp.from(now))
			.update();
	}

	private void insertCriterion(UUID jobId, UUID milestoneId, CriterionInput criterion, int position, Instant now) {
		this.jdbc.sql("""
				INSERT INTO job.acceptance_criterion (id, job_id, milestone_id, position, statement, check_steps, created_at)
				VALUES (:id, :job, :milestone, :position, :statement, :steps, :now)
				""")
			.param("id", criterion.id())
			.param("job", jobId)
			.param("milestone", milestoneId)
			.param("position", position)
			.param("statement", criterion.statement().strip())
			.param("steps", criterion.checkSteps().strip())
			.param("now", Timestamp.from(now))
			.update();
	}

	private List<Milestone> milestones(UUID jobId) {
		Map<UUID, List<Criterion>> criteria = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT id, milestone_id, statement, check_steps FROM job.acceptance_criterion
				WHERE job_id = :job AND removed_at IS NULL ORDER BY position, created_at, id
				""").param("job", jobId).query(rs -> {
			criteria.computeIfAbsent(rs.getObject("milestone_id", UUID.class), id -> new ArrayList<>())
				.add(new Criterion(rs.getObject("id", UUID.class), rs.getString("statement"), rs.getString("check_steps")));
		});
		return this.jdbc.sql("""
				SELECT id, title, price_vnd, due_on, revision_rounds FROM job.milestone
				WHERE job_id = :job AND removed_at IS NULL ORDER BY position, created_at, id
				""").param("job", jobId).query((rs, n) -> {
			UUID id = rs.getObject("id", UUID.class);
			Date due = rs.getDate("due_on");
			return new Milestone(id, rs.getString("title"), nullableLong(rs, "price_vnd"), due == null ? null : due.toLocalDate(),
					rs.getInt("revision_rounds"), List.copyOf(criteria.getOrDefault(id, List.of())));
		}).list();
	}

	private List<ExternalAccess> externalAccess(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id, system, purpose, environment, access_level FROM job.external_access
				WHERE job_id = :job AND removed_at IS NULL ORDER BY position, created_at, id
				""").param("job", jobId).query((rs, n) -> new ExternalAccess(rs.getObject("id", UUID.class),
						rs.getString("system"), rs.getString("purpose"), Environment.valueOf(rs.getString("environment")),
						AccessLevel.valueOf(rs.getString("access_level"))))
			.list();
	}

	/** The Skills by their current names; a version keeps the names of its moment (AD-38). */
	private List<SkillName> skills(List<UUID> ids) {
		Map<UUID, String> names = new HashMap<>();
		this.catalog.byIds(ids).forEach(skill -> names.put(skill.id(), skill.name()));
		return ids.stream().filter(names::containsKey).map(id -> new SkillName(id, names.get(id))).toList();
	}

	private String nameIn(Draft draft, UUID id) {
		return draft.content().skills().stream().filter(skill -> skill.id().equals(id)).map(SkillName::name).findFirst().orElse("");
	}

	private int revisionRounds(UUID jobId) {
		return this.jdbc.sql("SELECT coalesce((rules ->> 'REVISION_ROUNDS')::int, 2) FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Integer.class)
			.single();
	}

	private static void requireAgreement(Job job) {
		if (job.state() != JobState.AGREEMENT) {
			throw new ApiException(ErrorCode.STALE);
		}
	}

	/** A confirmed Agreement changes only through a Change Request (FR-17, Epic 5). */
	private static void requireUnconfirmed(Job job) {
		if (job.agreementVersionNo() != null) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.AGREEMENT_CONFIRMED);
		}
	}

	private static @Nullable Date date(@Nullable LocalDate date) {
		return date == null ? null : Date.valueOf(date);
	}

	private static List<UUID> uuids(Array array) throws SQLException {
		return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
	}

	private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	@SuppressWarnings("unchecked")
	private static Template loadTemplate() {
		try (InputStream in = Agreements.class.getClassLoader().getResourceAsStream("job/assessment-report-template.yaml")) {
			if (in == null) {
				throw new IllegalStateException("job/assessment-report-template.yaml is missing");
			}
			Map<String, Object> document = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
			List<Criterion> criteria = new ArrayList<>();
			for (Object entry : (List<Object>) document.get("criteria")) {
				Map<String, Object> criterion = (Map<String, Object>) entry;
				criteria.add(new Criterion(new UUID(0, criteria.size()), String.valueOf(criterion.get("statement")),
						String.valueOf(criterion.get("steps"))));
			}
			return new Template(String.valueOf(document.get("milestone")), List.copyOf(criteria));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
