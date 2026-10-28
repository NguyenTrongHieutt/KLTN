package vn.vibefix.job.internal;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Capabilities;
import vn.vibefix.job.JobPhase;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.KeywordQuery;

/**
 * The Jobs "Tìm việc" lists (FR-12, AD-36, AD-38, AD-43): not hidden, and Open (taking bids) or in Agreement,
 * Starting or Active with Crew recruitment on and a free slot (taking Crew Applications). The list and search never
 * show a Job where the reader holds a role: their own as Customer, theirs as Mentor, or one with a counted Crew row
 * of theirs, left Jobs included. 20 a page, narrowed by phase, Job Type, Skills, budget range and deadline, every
 * filter given at once. Without a keyword they come newest first (when recruitment turned on, else when the Job
 * Post opened); with one, by text relevance and then ID only (SG-43), so a Customer's Rating never changes the
 * order. The phase filter counts {@code ACTIVE} Crew rows in SQL; the phase shown on a row also leaves out the rows
 * of locked accounts ({@code identity.lockedAmong}, one read a page). Nothing here reads {@code matching} (AD-3).
 */
@Component
class JobPostQueries implements JobPosts {

	static final int PAGE_SIZE = 20;

	/**
	 * A listed Job (AD-36), on the alias {@code j}: not hidden, and Open, or in Agreement, Starting or Active with
	 * recruitment on and a free slot.
	 */
	static final String LISTED = "j.hidden_at IS NULL AND (j.state = 'OPEN' OR (j.state IN ('AGREEMENT', 'STARTING',"
			+ " 'ACTIVE') AND j.recruiting AND j.open_slots > 0))";

	/**
	 * The reader holds no role in the Job (AD-43): not its Customer, not its Mentor, and no counted Crew row of theirs
	 * (one not released, or released after it was activated: {@link CrewService#countedRow}).
	 */
	private static final String NO_ROLE = "j.customer_account_id <> :reader"
			+ " AND j.mentor_account_id IS DISTINCT FROM :reader"
			+ " AND NOT EXISTS (SELECT 1 FROM job.crew_row r WHERE r.job_id = j.id AND r.member_account_id = :reader"
			+ " AND (r.status <> 'RELEASED' OR r.activated_at IS NOT NULL))";

	/** The Job's {@code ACTIVE} Crew rows, counted in SQL for the phase filter. */
	private static final String ACTIVE_ROWS = "(SELECT count(*) FROM job.crew_row r WHERE r.job_id = j.id"
			+ " AND r.status = 'ACTIVE')";

	/** A listed Job's columns: the Job's, its recruitment setting, and its {@code ACTIVE} members' accounts. */
	private static final String COLUMNS = Jobs.COLUMNS.strip()
			+ ", recruiting, recruiting_since, open_slots, recruit_skill_ids, recruit_skill_names, recruit_note,"
			+ " ARRAY(SELECT r.member_account_id FROM job.crew_row r WHERE r.job_id = j.id AND r.status = 'ACTIVE'"
			+ " ORDER BY r.member_account_id) AS active_members";

	/** Newest first without a keyword (Story 21.1). */
	private static final String NEWEST = "COALESCE(j.recruiting_since, j.opened_at) DESC, j.id";

	/**
	 * What MEN-2 narrows by; an empty set or {@code null} leaves its filter out.
	 * @param phases the phases to match any of ("Tình trạng")
	 * @param skillIds the Skills to match any of, or {@code null} without a Skill filter; an empty set
	 * matches nothing
	 * @param dueBy the last deadline kept
	 * @param keyword the keyword search on {@code search_vector}, or {@code null} without one
	 */
	record Filter(Collection<JobPhase> phases, Collection<JobType> types, @Nullable Collection<UUID> skillIds,
			Collection<Budget> budgets, @Nullable LocalDate dueBy, @Nullable KeywordQuery keyword) {
	}

	/**
	 * The Mentor's Crew recruitment setting (AD-41), which Story 21.4's commands write.
	 * @param on recruitment is on
	 * @param since when it last turned on
	 * @param openSlots the places still open
	 * @param skillIds the Skills sought, in the Mentor's order
	 * @param skillNames their current names, in the same order
	 */
	record Recruitment(boolean on, @Nullable Instant since, int openSlots, List<UUID> skillIds, List<String> skillNames,
			@Nullable String note) {

		Recruitment {
			skillIds = List.copyOf(skillIds);
			skillNames = List.copyOf(skillNames);
		}

	}

	/**
	 * A listed Job: the Job, its recruitment setting, and the accounts of its {@code ACTIVE} Crew rows, before the
	 * locked ones are left out.
	 */
	record Post(Job job, Recruitment recruitment, List<UUID> activeMembers) {

		Post {
			activeMembers = List.copyOf(activeMembers);
		}

		/** A Mentor while the Job takes bids, else the Crew members its recruitment seeks. */
		JobNeed need() {
			return this.job.state() == JobState.OPEN || this.job.state() == JobState.SELECTING ? JobNeed.MENTOR
					: JobNeed.CREW;
		}

		/** When the Job Post opened. */
		Instant openedAt() {
			Instant opened = this.job.openedAt();
			return opened == null ? this.job.createdAt() : opened;
		}

		/** When recruitment turned on, else when the Job Post opened. */
		Instant listedAt() {
			Instant since = this.recruitment.on() ? this.recruitment.since() : null;
			return since == null ? openedAt() : since;
		}

		/** The phase, counting only the {@code ACTIVE} members not among {@code locked} (AD-41). */
		@Nullable JobPhase phase(Set<UUID> locked) {
			int counted = (int) this.activeMembers.stream().filter(member -> !locked.contains(member)).count();
			return JobPhase.phaseOf(this.job.state().name(), this.recruitment.on(), counted).orElse(null);
		}

	}

	record Page(List<Post> items, int page, long total) {
	}

	private final JdbcClient jdbc;

	private final Capabilities capabilities;

	JobPostQueries(JdbcClient jdbc, Capabilities capabilities) {
		this.jdbc = jdbc;
		this.capabilities = capabilities;
	}

	/** One page of the listed Jobs where {@code reader} holds no role (AD-36, AD-43). */
	@Transactional(readOnly = true)
	Page list(Filter filter, UUID reader, int page) {
		if (filter.skillIds() != null && filter.skillIds().isEmpty()) {
			return new Page(List.of(), page, 0);
		}
		StringBuilder where = new StringBuilder(LISTED).append(" AND ").append(NO_ROLE);
		Map<String, Object> params = new HashMap<>();
		params.put("reader", reader);
		if (!filter.phases().isEmpty()) {
			where.append(" AND (")
				.append(filter.phases()
					.stream()
					.distinct()
					.map(JobPostQueries::phaseCondition)
					.collect(Collectors.joining(" OR ")))
				.append(")");
		}
		if (!filter.types().isEmpty()) {
			where.append(" AND j.job_types && CAST(:types AS TEXT[])");
			params.put("types", filter.types().stream().map(Enum::name).toArray(String[]::new));
		}
		if (filter.skillIds() != null) {
			where.append(" AND j.skill_ids && CAST(:skills AS UUID[])");
			params.put("skills", filter.skillIds().toArray(UUID[]::new));
		}
		if (!filter.budgets().isEmpty()) {
			where.append(" AND j.budget IN (:budgets)");
			params.put("budgets", filter.budgets().stream().map(Enum::name).toList());
		}
		if (filter.dueBy() != null) {
			where.append(" AND j.deadline <= :dueBy");
			params.put("dueBy", Date.valueOf(filter.dueBy()));
		}
		KeywordQuery keyword = filter.keyword();
		if (keyword != null) {
			where.append(" AND ").append(keyword.condition());
			params.putAll(keyword.params());
		}
		String order = keyword == null ? NEWEST : keyword.rank("search_vector") + " DESC, j.id";
		long total = this.jdbc.sql("SELECT count(*) FROM job.job j WHERE " + where)
			.params(params)
			.query(Long.class)
			.single();
		params.put("limit", PAGE_SIZE);
		params.put("offset", (long) page * PAGE_SIZE);
		List<Post> items = this.jdbc
			.sql("SELECT " + COLUMNS + " FROM job.job j WHERE " + where + " ORDER BY " + order
					+ " LIMIT :limit OFFSET :offset")
			.params(params)
			.query(JobPostQueries::post)
			.list();
		return new Page(items, page, total);
	}

	/**
	 * A phase as the filter reads it, from {@code job}'s own columns (SG-66): {@link JobPhase#phaseOf} over the
	 * {@code ACTIVE} rows counted in SQL.
	 */
	private static String phaseCondition(JobPhase phase) {
		return switch (phase) {
			case FINDING_MENTOR -> "j.state IN ('OPEN', 'SELECTING')";
			case FINDING_CREW -> "((j.state = 'AGREEMENT' OR (j.state IN ('STARTING', 'ACTIVE') AND j.recruiting)) AND "
					+ ACTIVE_ROWS + " = 0)";
			case RECRUITING -> "(j.recruiting AND j.state IN ('AGREEMENT', 'STARTING', 'ACTIVE') AND " + ACTIVE_ROWS
					+ " > 0)";
			case AGREEING -> "(j.state = 'AGREEMENT' AND NOT j.recruiting AND " + ACTIVE_ROWS + " > 0)";
		};
	}

	/** MEN-3: a listed Job, whoever reads it. */
	@Transactional(readOnly = true)
	Optional<Post> find(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM job.job j WHERE j.id = :id AND " + LISTED)
			.param("id", jobId)
			.query(JobPostQueries::post)
			.optional();
	}

	/**
	 * The phase of each of {@code posts}, keyed by Job; a Job whose state label stands alone is left out. The locked
	 * accounts among their {@code ACTIVE} members come from one {@code identity.lockedAmong} read.
	 */
	Map<UUID, JobPhase> phases(List<Post> posts) {
		Set<UUID> members = new LinkedHashSet<>();
		posts.forEach(post -> members.addAll(post.activeMembers()));
		Set<UUID> locked = members.isEmpty() ? Set.of() : this.capabilities.lockedAmong(members).keySet();
		Map<UUID, JobPhase> phases = new HashMap<>();
		for (Post post : posts) {
			JobPhase phase = post.phase(locked);
			if (phase != null) {
				phases.put(post.job().id(), phase);
			}
		}
		return phases;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<JobPostState> post(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.map(JobPostQueries::state);
	}

	@Override
	@Transactional(readOnly = true)
	public List<JobPostState> productJobs(UUID productId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE product_id = :product ORDER BY created_at DESC, id")
			.param("product", productId)
			.query(Jobs::job)
			.list()
			.stream()
			.map(JobPostQueries::state)
			.toList();
	}

	private static JobPostState state(Job job) {
		return new JobPostState(job.id(), job.customerId(), job.productId(), job.title(), job.state().name(),
				job.state() == JobState.OPEN, job.hiddenAt() != null, job.lapsesAt(), job.code(), job.mentorId(),
				job.jobTypes().stream().map(Enum::name).toList(), job.skillIds());
	}

	@Override
	@Transactional(readOnly = true)
	public List<ListedPost> listedPosts(UUID reader) {
		List<Post> posts = this.jdbc
			.sql("SELECT " + COLUMNS + " FROM job.job j WHERE " + LISTED + " AND " + NO_ROLE + " ORDER BY " + NEWEST)
			.param("reader", reader)
			.query(JobPostQueries::post)
			.list();
		Map<UUID, JobPhase> phases = phases(posts);
		return posts.stream().map(post -> {
			Job job = post.job();
			Recruitment recruitment = post.recruitment();
			return new ListedPost(job.id(), job.customerId(), job.productId(), job.title(),
					job.jobTypes().stream().map(Enum::name).toList(), job.skillIds(),
					job.budget() == null ? null : job.budget().name(), job.deadline(), post.openedAt(),
					phases.get(job.id()), post.need(), recruitment.on() ? recruitment.openSlots() : 0,
					recruitment.on() ? recruitment.skillIds() : List.of(), post.listedAt());
		}).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> customerOf(UUID jobId) {
		return this.jdbc.sql("SELECT customer_account_id FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(UUID.class)
			.optional();
	}

	@Override
	public Optional<UUID> listedCustomer(UUID jobId) {
		return find(jobId).map(post -> post.job().customerId());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, UUID> listedCustomers(Collection<UUID> jobIds) {
		if (jobIds.isEmpty()) {
			return Map.of();
		}
		return this.jdbc
			.sql("SELECT j.id, j.customer_account_id FROM job.job j WHERE j.id = ANY (CAST(:ids AS UUID[])) AND "
					+ LISTED)
			.param("ids", jobIds.stream().distinct().toArray(UUID[]::new))
			.query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("customer_account_id", UUID.class)))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/** Whether the screenshot belongs to a listed Job, which MEN-3 shows it on. */
	@Transactional(readOnly = true)
	boolean isListedScreenshot(UUID fileId) {
		return this.jdbc
			.sql("SELECT EXISTS (SELECT 1 FROM job.job_screenshot s JOIN job.job j ON j.id = s.job_id"
					+ " WHERE s.file_id = :file AND " + LISTED + ")")
			.param("file", fileId)
			.query(Boolean.class)
			.single();
	}

	private static Post post(ResultSet rs, int n) throws SQLException {
		Timestamp since = rs.getTimestamp("recruiting_since");
		Recruitment recruitment = new Recruitment(rs.getBoolean("recruiting"), since == null ? null : since.toInstant(),
				rs.getInt("open_slots"), uuids(rs.getArray("recruit_skill_ids")),
				Arrays.asList((String[]) rs.getArray("recruit_skill_names").getArray()), rs.getString("recruit_note"));
		return new Post(Jobs.job(rs, n), recruitment, uuids(rs.getArray("active_members")));
	}

	private static List<UUID> uuids(Array array) throws SQLException {
		return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
	}

}
