package vn.vibefix.delivery.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.delivery.internal.DeliveryAccess.Kind;
import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.job.JobDrafts;
import vn.vibefix.job.MilestoneHandovers.HandoverJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.work.MilestoneWork;
import vn.vibefix.work.MilestoneWork.ReportVersion;

/**
 * Follow-up Jobs (FR-11): once an Assessment's Report is accepted, the Mentor drafts follow-up Jobs, each
 * citing one numbered recommendation of the accepted Report version, and submits them together; the
 * Customer opens each as a Draft Job Post through {@link JobDrafts}, which only their J1 publishes, or
 * discards it. Every command takes the follow-ups' Job row order: the Job's follow-ups are read
 * {@code FOR UPDATE} first.
 */
@Component
class FollowUps {

	static final String DRAFT = "delivery.draft_follow_ups";

	static final String APPROVE = "delivery.approve_follow_ups";

	static final String READY = "delivery.follow_up_drafts_ready";

	static final Set<String> JOB_TYPES = Set.of("ASSESSMENT", "BUG_FIX", "NEW_FEATURE", "MODIFICATION", "IMPROVEMENT",
			"SECURITY_TESTING", "DEPLOYMENT_OPERATIONS", "DOCUMENTATION");

	private static final Pattern SECTION = Pattern.compile("(?m)^##\\s+Đề xuất\\s*$");

	private static final Pattern NEXT_SECTION = Pattern.compile("(?m)^##\\s");

	private static final Pattern ITEM = Pattern.compile("(?m)^(\\d{1,3})\\.\\s+(\\S.*)$");

	/** The accepted handover that pins the Report version the follow-ups cite. */
	record Accepted(UUID handoverId, UUID pageId, int versionNo) {
	}

	/** One numbered recommendation of the accepted Report version. */
	record Recommendation(int number, String text) {
	}

	/** What the Mentor writes for one follow-up. */
	record Content(int recommendationNo, String title, List<String> jobTypes, List<UUID> skillIds, String scope,
			List<String> criteria, int effortDays) {
	}

	record FollowUp(UUID id, UUID jobId, int recommendationNo, String title, List<String> jobTypes, List<UUID> skillIds,
			String scope, List<String> criteria, int effortDays, String status, @Nullable UUID draftJobId) {
	}

	private final JdbcClient jdbc;

	private final MilestoneWork work;

	private final JobDrafts drafts;

	private final Notifications notifications;

	private final Todos todos;

	private final IdGenerator ids;

	private final Clock clock;

	FollowUps(JdbcClient jdbc, MilestoneWork work, JobDrafts drafts, Notifications notifications, Todos todos,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.work = work;
		this.drafts = drafts;
		this.notifications = notifications;
		this.todos = todos;
		this.ids = ids;
		this.clock = clock;
	}

	/** The accepted handover of an Assessment Job, when its Report version is pinned. */
	Optional<Accepted> accepted(HandoverJob job) {
		if (!job.assessment()) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				SELECT id, report_page_id, report_version_no FROM delivery.handover
				WHERE job_id = :job AND status = 'ACCEPTED' AND report_page_id IS NOT NULL
				ORDER BY seq DESC LIMIT 1
				""")
			.param("job", job.jobId())
			.query((rs, n) -> new Accepted(rs.getObject("id", UUID.class), rs.getObject("report_page_id", UUID.class),
					rs.getInt("report_version_no")))
			.optional();
	}

	/** The numbered items under "## Đề xuất" of the accepted version, in order. */
	List<Recommendation> recommendations(Accepted accepted) {
		return this.work.report(accepted.pageId(), accepted.versionNo())
			.map(ReportVersion::body)
			.map(FollowUps::parse)
			.orElse(List.of());
	}

	static List<Recommendation> parse(String body) {
		Matcher section = SECTION.matcher(body);
		if (!section.find()) {
			return List.of();
		}
		String rest = body.substring(section.end());
		Matcher next = NEXT_SECTION.matcher(rest);
		String part = next.find() ? rest.substring(0, next.start()) : rest;
		List<Recommendation> items = new ArrayList<>();
		Matcher item = ITEM.matcher(part);
		while (item.find()) {
			items.add(new Recommendation(Integer.parseInt(item.group(1)), item.group(2).strip()));
		}
		return List.copyOf(items);
	}

	/** M5 on an Assessment Job with its Report accepted: the Mentor's to-do. */
	@Transactional
	void openTodo(HandoverJob job) {
		if (job.mentorId() == null || accepted(job).isEmpty()) {
			return;
		}
		this.todos.open(new TodoRequest(DRAFT, job.mentorId(), Audience.MENTOR, job.jobId(), Map.of("job", job.title()),
				"/cong-viec/" + job.jobId(), null, job.jobId()));
	}

	/**
	 * @throws ApiException {@code FORBIDDEN MENTOR_ONLY}; {@code GUARD_FAILED REPORT_NOT_ACCEPTED};
	 * {@code CONFLICT FOLLOW_UPS_SUBMITTED}; {@code VALIDATION RECOMMENDATION_UNKNOWN}
	 */
	@Transactional
	FollowUp create(Viewer viewer, Content content) {
		Accepted accepted = mentorDrafting(viewer);
		checked(accepted, content);
		UUID id = this.ids.newId();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO delivery.follow_up (id, job_id, product_id, handover_id, report_page_id, report_version_no,
				    recommendation_no, title, job_types, skill_ids, scope, criteria, effort_days, status, mentor_account_id,
				    created_at, updated_at)
				VALUES (:id, :job, :product, :handover, :page, :version, :recommendation, :title, :types, :skills, :scope,
				    :criteria, :effort, 'DRAFTING', :mentor, :now, :now)
				""")
			.param("id", id)
			.param("job", viewer.job().jobId())
			.param("product", viewer.job().productId())
			.param("handover", accepted.handoverId())
			.param("page", accepted.pageId())
			.param("version", accepted.versionNo())
			.params(fields(content))
			.param("mentor", viewer.accountId())
			.param("now", now)
			.update();
		return find(id).orElseThrow();
	}

	/** Same refusals as {@link #create}; {@code NOT_FOUND} for another Job's follow-up. */
	@Transactional
	FollowUp update(Viewer viewer, UUID followUpId, Content content) {
		Accepted accepted = mentorDrafting(viewer);
		own(viewer, followUpId);
		checked(accepted, content);
		this.jdbc.sql("""
				UPDATE delivery.follow_up SET recommendation_no = :recommendation, title = :title, job_types = :types,
				    skill_ids = :skills, scope = :scope, criteria = :criteria, effort_days = :effort, updated_at = :now
				WHERE id = :id
				""").param("id", followUpId).params(fields(content)).param("now", Timestamp.from(this.clock.instant())).update();
		return find(followUpId).orElseThrow();
	}

	@Transactional
	void delete(Viewer viewer, UUID followUpId) {
		mentorDrafting(viewer);
		own(viewer, followUpId);
		this.jdbc.sql("DELETE FROM delivery.follow_up WHERE id = :id").param("id", followUpId).update();
	}

	/**
	 * Every draft goes to the Customer at once.
	 * @throws ApiException as {@link #create}; {@code VALIDATION NO_FOLLOW_UPS}
	 */
	@Transactional
	void submit(Viewer viewer) {
		mentorDrafting(viewer);
		HandoverJob job = viewer.job();
		Instant now = this.clock.instant();
		int submitted = this.jdbc.sql("""
				UPDATE delivery.follow_up SET status = 'SUBMITTED', submitted_at = :now, updated_at = :now
				WHERE job_id = :job AND status = 'DRAFTING'
				""").param("job", job.jobId()).param("now", Timestamp.from(now)).update();
		if (submitted == 0) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.NO_FOLLOW_UPS);
		}
		this.todos.close(DRAFT, viewer.accountId(), job.jobId());
		String link = "/cong-viec/" + job.jobId();
		this.notifications.send(new NotificationRequest(READY, job.jobId(), List.of(new Recipient(job.customerId(),
				Audience.CUSTOMER)), Map.of("job", job.title(), "n", String.valueOf(submitted)), link, job.jobId(), null));
		this.todos.open(new TodoRequest(APPROVE, job.customerId(), Audience.CUSTOMER, job.jobId(), Map.of("job", job.title()),
				link, null, job.jobId()));
	}

	/**
	 * The Customer turns a submitted follow-up into a Draft Job Post on the same Product.
	 * @return the Draft's Job ID, for CUS-6
	 * @throws ApiException {@code FORBIDDEN CUSTOMER_ONLY}; {@code CONFLICT FOLLOW_UP_NOT_AWAITING}; as
	 * {@link JobDrafts#createDraft}
	 */
	@Transactional
	UUID open(Viewer viewer, UUID followUpId) {
		FollowUp followUp = awaiting(viewer, followUpId);
		UUID draft = this.drafts.createDraft(viewer.accountId(), viewer.job().productId(), followUp.title(), followUp.scope(),
				String.join("\n", followUp.criteria().stream().map(line -> "- " + line).toList()), followUp.jobTypes(),
				followUp.skillIds());
		decide(viewer, followUpId, "OPENED", draft);
		return draft;
	}

	/** Same refusals as {@link #open}, without the Draft's. */
	@Transactional
	void discard(Viewer viewer, UUID followUpId) {
		awaiting(viewer, followUpId);
		decide(viewer, followUpId, "DISCARDED", null);
	}

	List<FollowUp> of(UUID jobId) {
		return this.jdbc.sql("SELECT * FROM delivery.follow_up WHERE job_id = :job ORDER BY recommendation_no, created_at, id")
			.param("job", jobId)
			.query(FollowUps::followUp)
			.list();
	}

	Optional<FollowUp> find(UUID followUpId) {
		return this.jdbc.sql("SELECT * FROM delivery.follow_up WHERE id = :id")
			.param("id", followUpId)
			.query(FollowUps::followUp)
			.optional();
	}

	/** The Job a follow-up belongs to, for its access check. */
	Optional<UUID> jobOf(UUID followUpId) {
		return this.jdbc.sql("SELECT job_id FROM delivery.follow_up WHERE id = :id")
			.param("id", followUpId)
			.query(UUID.class)
			.optional();
	}

	private Accepted mentorDrafting(Viewer viewer) {
		if (viewer.kind() != Kind.MENTOR) {
			throw new ApiException(ErrorCode.FORBIDDEN, DeliveryReason.MENTOR_ONLY);
		}
		lock(viewer.job().jobId());
		Accepted accepted = accepted(viewer.job())
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.REPORT_NOT_ACCEPTED));
		boolean submitted = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM delivery.follow_up WHERE job_id = :job AND status <> 'DRAFTING')
				""").param("job", viewer.job().jobId()).query(Boolean.class).single();
		if (submitted) {
			throw new ApiException(ErrorCode.CONFLICT, DeliveryReason.FOLLOW_UPS_SUBMITTED);
		}
		return accepted;
	}

	private FollowUp awaiting(Viewer viewer, UUID followUpId) {
		if (viewer.kind() != Kind.CUSTOMER) {
			throw new ApiException(ErrorCode.FORBIDDEN, DeliveryReason.CUSTOMER_ONLY);
		}
		lock(viewer.job().jobId());
		FollowUp followUp = own(viewer, followUpId);
		if (!"SUBMITTED".equals(followUp.status())) {
			throw new ApiException(ErrorCode.CONFLICT, DeliveryReason.FOLLOW_UP_NOT_AWAITING);
		}
		return followUp;
	}

	private void decide(Viewer viewer, UUID followUpId, String status, @Nullable UUID draft) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				UPDATE delivery.follow_up SET status = :status, draft_job_id = :draft, decided_at = :now, updated_at = :now
				WHERE id = :id
				""").param("id", followUpId).param("status", status).param("draft", draft).param("now", now).update();
		boolean left = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM delivery.follow_up WHERE job_id = :job AND status = 'SUBMITTED')
				""").param("job", viewer.job().jobId()).query(Boolean.class).single();
		if (!left) {
			this.todos.close(APPROVE, viewer.accountId(), viewer.job().jobId());
		}
	}

	private FollowUp own(Viewer viewer, UUID followUpId) {
		return find(followUpId).filter(found -> found.jobId().equals(viewer.job().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private void lock(UUID jobId) {
		this.jdbc.sql("SELECT id FROM delivery.follow_up WHERE job_id = :job FOR UPDATE").param("job", jobId)
			.query(UUID.class).list();
	}

	private void checked(Accepted accepted, Content content) {
		if (recommendations(accepted).stream().noneMatch(item -> item.number() == content.recommendationNo())) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.RECOMMENDATION_UNKNOWN);
		}
		Set<String> types = new LinkedHashSet<>(content.jobTypes());
		boolean criteriaOk = !content.criteria().isEmpty() && content.criteria().size() <= 10
				&& content.criteria().stream().allMatch(line -> !line.isBlank() && line.strip().length() <= 300);
		if (types.isEmpty() || !JOB_TYPES.containsAll(types) || (types.contains("ASSESSMENT") && types.size() > 1)
				|| new LinkedHashSet<>(content.skillIds()).size() > 10 || !criteriaOk || content.title().isBlank()
				|| content.scope().isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
	}

	private static Map<String, Object> fields(Content content) {
		Map<String, Object> fields = new java.util.HashMap<>();
		fields.put("recommendation", content.recommendationNo());
		fields.put("title", content.title().strip());
		fields.put("types", new LinkedHashSet<>(content.jobTypes()).toArray(String[]::new));
		fields.put("skills", new LinkedHashSet<>(content.skillIds()).toArray(UUID[]::new));
		fields.put("scope", content.scope().strip());
		fields.put("criteria", content.criteria().stream().map(String::strip).toArray(String[]::new));
		fields.put("effort", content.effortDays());
		return fields;
	}

	private static FollowUp followUp(ResultSet rs, int n) throws SQLException {
		return new FollowUp(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class), rs.getInt("recommendation_no"),
				rs.getString("title"), strings(rs.getArray("job_types")), uuids(rs.getArray("skill_ids")), rs.getString("scope"),
				strings(rs.getArray("criteria")), rs.getInt("effort_days"), rs.getString("status"),
				rs.getObject("draft_job_id", UUID.class));
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
