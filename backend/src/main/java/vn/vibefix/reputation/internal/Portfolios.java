package vn.vibefix.reputation.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

import vn.vibefix.delivery.HandoverFacts;
import vn.vibefix.job.AgreementSkills;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.MilestoneStates;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.repo.DataDeletions;
import vn.vibefix.reputation.internal.Ratings.Viewer;
import vn.vibefix.reputation.internal.Ratings.ViewerRole;
import vn.vibefix.reputation.internal.Ratings.Window;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.work.WorkFacts;

/**
 * Portfolio Entries (FR-50, AD-33, AD-38): J9 creates one for the Mentor and each Crew member who was
 * ever active when the Job has Accepted work, with facts frozen from records. The Mentor drafts the
 * summary, the Customer approves (possibly edited, with or without the Product name) or declines it,
 * all before the Rating Window ends; otherwise there is none. Each entry is published once its person
 * confirmed the data deletion (FR-66).
 */
@Component
class Portfolios {

	static final String DRAFT = "reputation.draft_portfolio_summary";

	static final String APPROVE = "reputation.approve_portfolio_summary";

	static final String AWAITING = "reputation.portfolio_summary_awaiting_approval";

	static final String PUBLISHED = "reputation.portfolio_entry_published";

	static final Set<String> ACCEPTED = Set.of("ACCEPTED", "SETTLED");

	enum Status {
		DRAFT_DUE, AWAITING_APPROVAL, APPROVED, DECLINED, LAPSED
	}

	record Summary(UUID jobId, Status status, @Nullable String text, boolean showProductName) {

		boolean pending() {
			return this.status == Status.DRAFT_DUE || this.status == Status.AWAITING_APPROVAL;
		}

	}

	record Entry(UUID jobId, UUID accountId, String role, String jobTitle, String productName, List<String> jobTypes,
			List<UUID> skillIds, int milestones, @Nullable Integer workItemsApproved, @Nullable Integer milestonesHandedOver,
			@Nullable Integer reviewsWritten, Instant closedAt, @Nullable Instant publishedAt) {
	}

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final MilestoneStates milestones;

	private final AgreementSkills agreementSkills;

	private final WorkFacts work;

	private final HandoverFacts handovers;

	private final ProductDirectory products;

	private final DataDeletions deletions;

	private final Ratings ratings;

	private final Notifications notifications;

	private final Todos todos;

	private final IdGenerator ids;

	private final Clock clock;

	Portfolios(JdbcClient jdbc, JobPosts posts, MilestoneStates milestones, AgreementSkills agreementSkills, WorkFacts work,
			HandoverFacts handovers, ProductDirectory products, DataDeletions deletions, Ratings ratings,
			Notifications notifications, Todos todos, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.milestones = milestones;
		this.agreementSkills = agreementSkills;
		this.work = work;
		this.handovers = handovers;
		this.products = products;
		this.deletions = deletions;
		this.ratings = ratings;
		this.notifications = notifications;
		this.todos = todos;
		this.ids = ids;
		this.clock = clock;
	}

	/** J9: the entries, the summary to draft, and each entry whose person already confirmed; replay-safe. */
	@Transactional
	void create(UUID jobId) {
		JobPostState post = this.posts.post(jobId).filter(found -> found.mentorId() != null).orElse(null);
		if (post == null || summary(jobId).isPresent()) {
			return;
		}
		int accepted = (int) this.milestones.statesOf(jobId).values().stream().filter(ACCEPTED::contains).count();
		if (accepted == 0) {
			return;
		}
		UUID mentor = post.mentorId();
		List<UUID> skills = this.agreementSkills.skillIdsOf(jobId);
		if (skills.isEmpty()) {
			skills = post.skillIds();
		}
		String product = this.products.find(post.productId()).map(ProductInfo::name).orElse("");
		Instant now = this.clock.instant();
		insert(post, mentor, "MENTOR", product, skills, accepted, null, this.handovers.milestonesHandedOver(jobId),
				this.work.reviewsWritten(jobId, mentor), now);
		Map<UUID, Integer> approved = this.work.approvedItems(jobId);
		List<UUID> crew = this.ratings.crew(jobId);
		for (UUID member : crew) {
			insert(post, member, "CREW", product, skills, accepted, approved.getOrDefault(member, 0), null, null, now);
		}
		this.jdbc.sql("""
				INSERT INTO reputation.portfolio_summary (job_id, product_id, status, created_at)
				VALUES (:job, :product, 'DRAFT_DUE', :now)
				""").param("job", jobId).param("product", post.productId()).param("now", Timestamp.from(now)).update();
		Instant due = this.ratings.window(jobId).map(Window::endsAt).orElse(now.plus(Duration.ofDays(Ratings.DEFAULT_DAYS)));
		this.todos.open(new TodoRequest(DRAFT, mentor, Audience.MENTOR, jobId, Map.of("job", post.title()),
				"/cong-viec/" + jobId, due, jobId));
		List<UUID> people = new ArrayList<>();
		people.add(mentor);
		people.addAll(crew);
		for (UUID person : people) {
			if (this.deletions.confirmed(jobId, person)) {
				publish(jobId, person);
			}
		}
	}

	/** The person's entry goes public, once; {@code reputation.portfolio_entry_published} tells them. */
	@Transactional
	void publish(UUID jobId, UUID accountId) {
		this.jdbc.sql("""
				UPDATE reputation.portfolio_entry SET published_at = :now
				WHERE job_id = :job AND account_id = :account AND published_at IS NULL
				RETURNING role, job_title
				""")
			.param("job", jobId)
			.param("account", accountId)
			.param("now", Timestamp.from(this.clock.instant()))
			.query((rs, n) -> new String[] { rs.getString("role"), rs.getString("job_title") })
			.optional()
			.ifPresent(row -> this.notifications.send(new NotificationRequest(PUBLISHED, jobId,
					List.of(new Recipient(accountId, "MENTOR".equals(row[0]) ? Audience.MENTOR : Audience.CREW_EVER_ACTIVE)),
					Map.of("job", row[1]), "/cong-viec/" + jobId, jobId, null)));
	}

	/**
	 * The Mentor's draft, once (FR-50).
	 * @throws ApiException {@code FORBIDDEN MENTOR_ONLY}; {@code GUARD_FAILED NO_PORTFOLIO} or
	 * {@code RATING_WINDOW_CLOSED}; {@code CONFLICT SUMMARY_NOT_AWAITING}
	 */
	@Transactional
	void draft(Viewer viewer, String text) {
		if (viewer.role() != ViewerRole.MENTOR) {
			throw new ApiException(ErrorCode.FORBIDDEN, ReputationReason.MENTOR_ONLY);
		}
		Summary summary = locked(viewer.jobId());
		Window window = this.ratings.openWindow(viewer.jobId());
		if (summary.status() != Status.DRAFT_DUE) {
			throw new ApiException(ErrorCode.CONFLICT, ReputationReason.SUMMARY_NOT_AWAITING);
		}
		this.jdbc.sql("""
				UPDATE reputation.portfolio_summary SET status = 'AWAITING_APPROVAL', text = :text, drafted_by = :mentor,
				    drafted_at = :now
				WHERE job_id = :job
				""")
			.param("job", viewer.jobId())
			.param("text", checked(text))
			.param("mentor", viewer.accountId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.close(DRAFT, viewer.accountId(), viewer.jobId());
		if (window.customerId() != null) {
			String date = Ratings.dayMonth(window.endsAt());
			String link = "/cong-viec/" + viewer.jobId();
			this.notifications.send(new NotificationRequest(AWAITING, viewer.jobId(),
					List.of(new Recipient(window.customerId(), Audience.CUSTOMER)),
					Map.of("job", window.jobTitle(), "date", date), link, viewer.jobId(), null));
			this.todos.open(new TodoRequest(APPROVE, window.customerId(), Audience.CUSTOMER, viewer.jobId(),
					Map.of("job", window.jobTitle()), link, window.endsAt(), viewer.jobId()));
		}
	}

	/**
	 * The Customer approves the summary, possibly edited, and says whether the Product name shows.
	 * @throws ApiException {@code FORBIDDEN CUSTOMER_ONLY}; {@code GUARD_FAILED NO_PORTFOLIO} or
	 * {@code RATING_WINDOW_CLOSED}; {@code CONFLICT SUMMARY_NOT_AWAITING}
	 */
	@Transactional
	void approve(Viewer viewer, String text, boolean showProductName) {
		decide(viewer);
		this.jdbc.sql("""
				UPDATE reputation.portfolio_summary SET status = 'APPROVED', text = :text, show_product_name = :show,
				    decided_by = :customer, decided_at = :now
				WHERE job_id = :job
				""")
			.param("job", viewer.jobId())
			.param("text", checked(text))
			.param("show", showProductName)
			.param("customer", viewer.accountId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.close(APPROVE, viewer.accountId(), viewer.jobId());
	}

	/** The Customer declines: no summary. Same refusals as {@link #approve}. */
	@Transactional
	void decline(Viewer viewer) {
		decide(viewer);
		this.jdbc.sql("""
				UPDATE reputation.portfolio_summary SET status = 'DECLINED', text = NULL, decided_by = :customer, decided_at = :now
				WHERE job_id = :job
				""")
			.param("job", viewer.jobId())
			.param("customer", viewer.accountId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.todos.close(APPROVE, viewer.accountId(), viewer.jobId());
	}

	/** The Rating Window's end: a summary not drafted or not decided lapses, and its to-dos close. */
	@Transactional
	boolean lapse(UUID jobId) {
		int lapsed = this.jdbc.sql("""
				UPDATE reputation.portfolio_summary SET status = 'LAPSED', text = NULL
				WHERE job_id = :job AND status IN ('DRAFT_DUE', 'AWAITING_APPROVAL')
				""").param("job", jobId).update();
		this.todos.closeAll(DRAFT, jobId);
		this.todos.closeAll(APPROVE, jobId);
		return lapsed > 0;
	}

	Optional<Summary> summary(UUID jobId) {
		return this.jdbc.sql("SELECT job_id, status, text, show_product_name FROM reputation.portfolio_summary WHERE job_id = :job")
			.param("job", jobId)
			.query(Portfolios::summary)
			.optional();
	}

	List<Summary> summaries(Collection<UUID> jobIds) {
		if (jobIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT job_id, status, text, show_product_name FROM reputation.portfolio_summary WHERE job_id IN (:jobs)
				""").param("jobs", new LinkedHashSet<>(jobIds)).query(Portfolios::summary).list();
	}

	Optional<Entry> entry(UUID jobId, UUID accountId) {
		return this.jdbc.sql("SELECT * FROM reputation.portfolio_entry WHERE job_id = :job AND account_id = :account")
			.param("job", jobId)
			.param("account", accountId)
			.query(Portfolios::entry)
			.optional();
	}

	/** The account's published entries, the latest Job first. */
	List<Entry> published(UUID accountId) {
		return this.jdbc.sql("""
				SELECT * FROM reputation.portfolio_entry WHERE account_id = :account AND published_at IS NOT NULL
				ORDER BY closed_at DESC, job_id
				""").param("account", accountId).query(Portfolios::entry).list();
	}

	/** Which of these Mentors have an entry: a Job closed with Accepted work (FR-14). */
	Set<UUID> withEntries(Collection<UUID> mentorIds) {
		if (mentorIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(this.jdbc.sql("""
				SELECT DISTINCT account_id FROM reputation.portfolio_entry WHERE role = 'MENTOR' AND account_id IN (:ids)
				""").param("ids", new LinkedHashSet<>(mentorIds)).query(UUID.class).list());
	}

	private void insert(JobPostState post, UUID account, String role, String product, List<UUID> skills, int accepted,
			@Nullable Integer approved, @Nullable Integer handedOver, @Nullable Integer reviews, Instant now) {
		this.jdbc.sql("""
				INSERT INTO reputation.portfolio_entry (id, job_id, product_id, account_id, role, job_title, product_name, job_types,
				    skill_ids, milestones_accepted, work_items_approved, milestones_handed_over, reviews_written, closed_at,
				    created_at)
				VALUES (:id, :job, :product, :account, :role, :title, :productName, :types, :skills, :accepted, :approved,
				    :handedOver, :reviews, :now, :now)
				ON CONFLICT (job_id, account_id) DO NOTHING
				""")
			.param("id", this.ids.newId())
			.param("job", post.jobId())
			.param("product", post.productId())
			.param("account", account)
			.param("role", role)
			.param("title", post.title())
			.param("productName", product)
			.param("types", post.jobTypes().toArray(String[]::new))
			.param("skills", skills.toArray(UUID[]::new))
			.param("accepted", accepted)
			.param("approved", approved)
			.param("handedOver", handedOver)
			.param("reviews", reviews)
			.param("now", Timestamp.from(now))
			.update();
	}

	private Summary locked(UUID jobId) {
		return this.jdbc.sql("""
				SELECT job_id, status, text, show_product_name FROM reputation.portfolio_summary WHERE job_id = :job FOR UPDATE
				""")
			.param("job", jobId)
			.query(Portfolios::summary)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, ReputationReason.NO_PORTFOLIO));
	}

	private void decide(Viewer viewer) {
		if (viewer.role() != ViewerRole.CUSTOMER) {
			throw new ApiException(ErrorCode.FORBIDDEN, ReputationReason.CUSTOMER_ONLY);
		}
		Summary summary = locked(viewer.jobId());
		this.ratings.openWindow(viewer.jobId());
		if (summary.status() != Status.AWAITING_APPROVAL) {
			throw new ApiException(ErrorCode.CONFLICT, ReputationReason.SUMMARY_NOT_AWAITING);
		}
	}

	private static String checked(String text) {
		String stripped = text.strip();
		if (stripped.isEmpty() || stripped.length() > 600) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		return stripped;
	}

	private static Summary summary(ResultSet rs, int n) throws SQLException {
		return new Summary(rs.getObject("job_id", UUID.class), Status.valueOf(rs.getString("status")), rs.getString("text"),
				rs.getBoolean("show_product_name"));
	}

	private static Entry entry(ResultSet rs, int n) throws SQLException {
		Timestamp published = rs.getTimestamp("published_at");
		return new Entry(rs.getObject("job_id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("role"),
				rs.getString("job_title"), rs.getString("product_name"), strings(rs.getArray("job_types")),
				uuids(rs.getArray("skill_ids")), rs.getInt("milestones_accepted"), integer(rs, "work_items_approved"),
				integer(rs, "milestones_handed_over"), integer(rs, "reviews_written"), rs.getTimestamp("closed_at").toInstant(),
				published == null ? null : published.toInstant());
	}

	private static @Nullable Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static List<String> strings(Array array) throws SQLException {
		List<String> values = new ArrayList<>();
		for (Object value : (Object[]) array.getArray()) {
			values.add((String) value);
		}
		return List.copyOf(values);
	}

	private static List<UUID> uuids(Array array) throws SQLException {
		List<UUID> values = new ArrayList<>();
		for (Object value : (Object[]) array.getArray()) {
			values.add((UUID) value);
		}
		return List.copyOf(values);
	}

}
