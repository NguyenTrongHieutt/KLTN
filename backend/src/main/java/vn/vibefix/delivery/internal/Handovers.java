package vn.vibefix.delivery.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriterionInForce;
import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.Submitted;
import vn.vibefix.job.MilestoneStates;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.scanning.SensitiveText.Match;
import vn.vibefix.scanning.SensitiveText.Result;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.work.MilestoneWork;
import vn.vibefix.work.MilestoneWork.ItemState;
import vn.vibefix.work.MilestoneWork.ReportVersion;

/**
 * Milestone Handovers (FR-10, FR-32, AD-4, AD-23, AD-33, SG-25): the preflight, the handover itself, and
 * its Delivery Report. A handover needs every Work Item of the Milestone in "Xong" through an approval or
 * a note for it, and each Acceptance Criterion marked with a note; the notes pass FR-47. {@code job}
 * applies M2 first, on the version the form loaded, so nothing is written when it refuses; then the
 * handover, its criteria, and its notes are stored, an Assessment Job's with the Report version current
 * then, and the to-dos move from the Mentor to the Customer.
 */
@Component
class Handovers {

	static final String HAND_OVER = "delivery.hand_over";

	static final String REVIEW_HANDOVER = "delivery.review_handover";

	static final int MAX_NOTE = 2000;

	/** The Milestone states a handover leaves (M2). */
	static final Set<String> HANDABLE = Set.of("FUNDED", "REVISION_REQUESTED");

	/** What the writer chose after the FR-47 warning: check first, or store the masked text. */
	enum Resolution {
		CHECK, MASK
	}

	/** One masked FR-47 match, as the warning lists it. */
	record MatchView(SensitiveText.Kind kind, String type, String masked) {
	}

	/** The Mentor's mark on one criterion. */
	record CriterionMark(UUID criterionId, boolean met, String note) {
	}

	/** The Mentor's note on one Work Item that is not done. */
	record ItemNote(UUID workItemId, String note) {
	}

	record Handover(UUID id, UUID jobId, UUID productId, UUID milestoneId, int number, String milestoneTitle, int round,
			@Nullable UUID mentorId, String status, long jobVersion, @Nullable UUID reportPageId,
			@Nullable Integer reportVersionNo, Instant checkBy, Instant createdAt, @Nullable Instant decidedAt) {
	}

	record Criterion(UUID criterionId, int number, String statement, String checkSteps, boolean met, String note) {
	}

	record Note(UUID workItemId, String key, String title, String status, String note) {
	}

	/**
	 * What the "Tạo bàn giao" form shows before anything is sent.
	 * @param problems the refusals the handover would meet now, as reason codes
	 */
	record Preflight(MilestoneInForce milestone, String state, long jobVersion, boolean assessment,
			List<CriterionInForce> criteria, List<ItemState> items, @Nullable ReportVersion report, List<String> problems) {
	}

	private static final String COLUMNS = """
			id, job_id, product_id, milestone_id, milestone_number, milestone_title, round, mentor_account_id, status,
			job_version, report_page_id, report_version_no, check_by, created_at, decided_at
			""";

	private final JdbcClient jdbc;

	private final MilestoneHandovers jobs;

	private final AgreementCriteria criteria;

	private final MilestoneStates states;

	private final MilestoneWork work;

	private final SensitiveText check;

	private final Todos todos;

	private final IdGenerator ids;

	private final Clock clock;

	Handovers(JdbcClient jdbc, MilestoneHandovers jobs, AgreementCriteria criteria, MilestoneStates states,
			MilestoneWork work, SensitiveText check, Todos todos, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.criteria = criteria;
		this.states = states;
		this.work = work;
		this.check = check;
		this.todos = todos;
		this.ids = ids;
		this.clock = clock;
	}

	/** The first Milestone in force waiting for a handover: Funded or Revision Requested. */
	@Transactional(readOnly = true)
	Optional<MilestoneInForce> waiting(UUID jobId) {
		Map<UUID, String> live = this.states.statesOf(jobId);
		return milestones(jobId).stream()
			.filter(milestone -> HANDABLE.contains(live.getOrDefault(milestone.milestoneId(), "PLANNED")))
			.findFirst();
	}

	/** The form's preflight for one Milestone in force. */
	@Transactional(readOnly = true)
	Preflight preflight(Viewer mentor, UUID milestoneId) {
		UUID jobId = mentor.job().jobId();
		MilestoneInForce milestone = milestone(jobId, milestoneId);
		String state = this.states.statesOf(jobId).getOrDefault(milestoneId, "PLANNED");
		List<String> problems = new ArrayList<>();
		if (!HANDABLE.contains(state)) {
			problems.add(DeliveryReason.MILESTONE_NOT_HELD.name());
		}
		List<ItemState> items = this.work.items(jobId, milestoneId);
		ReportVersion report = mentor.job().assessment() ? this.work.currentReport(jobId).orElse(null) : null;
		if (mentor.job().assessment() && report == null) {
			problems.add(DeliveryReason.REPORT_MISSING.name());
		}
		if (!mentor.job().assessment()) {
			problems.add("HANDOVER_COMMIT_MISSING");
		}
		return new Preflight(milestone, state, mentor.job().version(), mentor.job().assessment(),
				criteriaOf(jobId, milestoneId), items, report, problems);
	}

	/**
	 * "Gửi bàn giao": M2 on {@code version}, then the handover with its marks and notes.
	 * @throws ApiException {@code NOT_FOUND} for a Milestone not in force; {@code GUARD_FAILED
	 * MILESTONE_NOT_HELD}, {@code WORK_ITEMS_NOT_DONE} (with {@code n}), {@code REPORT_MISSING}, or
	 * {@code SENSITIVE_CONTENT} (with the matches, under {@code CHECK}); {@code VALIDATION
	 * CRITERION_MARK_MISSING}; and M2's refusals, {@code STALE} among them
	 */
	@Transactional
	Handover create(Viewer mentor, UUID milestoneId, long version, List<CriterionMark> marks, List<ItemNote> notes,
			Resolution resolution) {
		UUID jobId = mentor.job().jobId();
		MilestoneInForce milestone = milestone(jobId, milestoneId);
		if (!HANDABLE.contains(this.states.statesOf(jobId).getOrDefault(milestoneId, "PLANNED"))) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.MILESTONE_NOT_HELD);
		}
		Map<UUID, CriterionMark> byCriterion = new HashMap<>();
		marks.forEach(mark -> byCriterion.put(mark.criterionId(), mark));
		List<CriterionInForce> criteria = criteriaOf(jobId, milestoneId);
		for (CriterionInForce criterion : criteria) {
			CriterionMark mark = byCriterion.get(criterion.criterionId());
			if (mark == null || mark.note().isBlank()) {
				throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.CRITERION_MARK_MISSING);
			}
		}
		Map<UUID, String> noted = new HashMap<>();
		notes.stream().filter(note -> !note.note().isBlank()).forEach(note -> noted.put(note.workItemId(), note.note()));
		List<ItemState> unfinished = this.work.items(jobId, milestoneId).stream().filter(item -> !item.approved()).toList();
		long unexplained = unfinished.stream().filter(item -> !noted.containsKey(item.id())).count();
		if (unexplained > 0) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.WORK_ITEMS_NOT_DONE,
					Map.of("n", unexplained));
		}
		ReportVersion report = null;
		if (mentor.job().assessment()) {
			report = this.work.currentReport(jobId)
				.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.REPORT_MISSING));
		}
		List<MatchView> matches = new ArrayList<>();
		Map<UUID, String> criterionNotes = new HashMap<>();
		for (CriterionInForce criterion : criteria) {
			criterionNotes.put(criterion.criterionId(), masked(byCriterion.get(criterion.criterionId()).note(), matches));
		}
		Map<UUID, String> itemNotes = new HashMap<>();
		for (ItemState item : unfinished) {
			itemNotes.put(item.id(), masked(noted.get(item.id()), matches));
		}
		if (!matches.isEmpty() && resolution == Resolution.CHECK) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DeliveryReason.SENSITIVE_CONTENT, Map.of("matches", matches));
		}
		UUID id = this.ids.newId();
		Submitted submitted = this.jobs.submit(jobId, milestoneId, mentor.accountId(), version, id);
		int round = this.jdbc.sql("SELECT count(*) FROM delivery.handover WHERE milestone_id = :milestone")
			.param("milestone", milestoneId)
			.query(Integer.class)
			.single() + 1;
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, report_page_id, report_version_no, check_by, created_at)
				VALUES (:id, :job, :product, :milestone, :number, :title, :round, :mentor, 'SUBMITTED', :version, :transition,
				    :page, :pageVersion, :checkBy, :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("product", mentor.job().productId())
			.param("milestone", milestoneId)
			.param("number", milestone.number())
			.param("title", milestone.title())
			.param("round", round)
			.param("mentor", mentor.accountId())
			.param("version", submitted.version())
			.param("transition", submitted.transitionId())
			.param("page", report == null ? null : report.pageId())
			.param("pageVersion", report == null ? null : report.versionNo())
			.param("checkBy", Timestamp.from(submitted.checkBy()))
			.param("now", Timestamp.from(now))
			.update();
		for (CriterionInForce criterion : criteria) {
			this.jdbc.sql("""
					INSERT INTO delivery.handover_criterion (handover_id, criterion_id, number, statement, check_steps, met, note)
					VALUES (:id, :criterion, :number, :statement, :steps, :met, :note)
					""")
				.param("id", id)
				.param("criterion", criterion.criterionId())
				.param("number", criterion.number())
				.param("statement", criterion.statement())
				.param("steps", criterion.checkSteps())
				.param("met", byCriterion.get(criterion.criterionId()).met())
				.param("note", criterionNotes.get(criterion.criterionId()))
				.update();
		}
		for (ItemState item : unfinished) {
			this.jdbc.sql("""
					INSERT INTO delivery.handover_item_note (handover_id, work_item_id, item_key, title, status, note)
					VALUES (:id, :item, :key, :title, :status, :note)
					""")
				.param("id", id)
				.param("item", item.id())
				.param("key", item.key())
				.param("title", item.title())
				.param("status", item.status())
				.param("note", itemNotes.get(item.id()))
				.update();
		}
		this.todos.close(HAND_OVER, mentor.accountId(), milestoneId);
		this.todos.open(new TodoRequest(REVIEW_HANDOVER, mentor.job().customerId(), Audience.CUSTOMER, id,
				Map.of("n", String.valueOf(milestone.number())), "/cong-viec/" + jobId + "/ban-giao/" + id,
				submitted.checkBy(), jobId));
		return find(id).orElseThrow();
	}

	/** The Job's handovers, oldest first. */
	@Transactional(readOnly = true)
	List<Handover> of(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM delivery.handover WHERE job_id = :job ORDER BY seq")
			.param("job", jobId)
			.query(Handovers::handover)
			.list();
	}

	@Transactional(readOnly = true)
	Optional<Handover> find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM delivery.handover WHERE id = :id")
			.param("id", id)
			.query(Handovers::handover)
			.optional();
	}

	@Transactional(readOnly = true)
	List<Criterion> criteria(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT criterion_id, number, statement, check_steps, met, note FROM delivery.handover_criterion
				WHERE handover_id = :id ORDER BY number
				""")
			.param("id", handoverId)
			.query((rs, n) -> new Criterion(rs.getObject("criterion_id", UUID.class), rs.getInt("number"),
					rs.getString("statement"), rs.getString("check_steps"), rs.getBoolean("met"), rs.getString("note")))
			.list();
	}

	@Transactional(readOnly = true)
	List<Note> notes(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT work_item_id, item_key, title, status, note FROM delivery.handover_item_note
				WHERE handover_id = :id ORDER BY item_key
				""")
			.param("id", handoverId)
			.query((rs, n) -> new Note(rs.getObject("work_item_id", UUID.class), rs.getString("item_key"),
					rs.getString("title"), rs.getString("status"), rs.getString("note")))
			.list();
	}

	/** The pinned Assessment Report version of a handover. */
	@Transactional(readOnly = true)
	Optional<ReportVersion> report(Handover handover) {
		if (handover.reportPageId() == null || handover.reportVersionNo() == null) {
			return Optional.empty();
		}
		return this.work.report(handover.reportPageId(), handover.reportVersionNo());
	}

	List<MilestoneInForce> milestones(UUID jobId) {
		return this.criteria.inForce(jobId).map(inForce -> inForce.milestones()).orElse(List.of());
	}

	private MilestoneInForce milestone(UUID jobId, UUID milestoneId) {
		return milestones(jobId).stream()
			.filter(found -> found.milestoneId().equals(milestoneId))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private List<CriterionInForce> criteriaOf(UUID jobId, UUID milestoneId) {
		return this.criteria.inForce(jobId)
			.map(inForce -> inForce.criteria().stream().filter(c -> c.milestoneId().equals(milestoneId)).toList())
			.orElse(List.of());
	}

	/** The note stripped and, with its matches added, masked (FR-47). */
	private String masked(String note, List<MatchView> matches) {
		String text = note.strip();
		if (text.length() > MAX_NOTE) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Result result = this.check.check(text);
		if (!result.found()) {
			return text;
		}
		for (Match match : result.matches()) {
			matches.add(new MatchView(match.kind(), match.type(), match.masked()));
		}
		return result.maskedText();
	}

	private static Handover handover(ResultSet rs, int n) throws SQLException {
		Integer reportVersion = rs.getObject("report_version_no", Integer.class);
		Timestamp decided = rs.getTimestamp("decided_at");
		return new Handover(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getInt("milestone_number"), rs.getString("milestone_title"), rs.getInt("round"),
				rs.getObject("mentor_account_id", UUID.class), rs.getString("status"), rs.getLong("job_version"),
				rs.getObject("report_page_id", UUID.class), reportVersion,
				Objects.requireNonNull(rs.getTimestamp("check_by")).toInstant(), rs.getTimestamp("created_at").toInstant(),
				decided == null ? null : decided.toInstant());
	}

}
