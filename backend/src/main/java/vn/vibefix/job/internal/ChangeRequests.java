package vn.vibefix.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.AgreementConfirmed;
import vn.vibefix.job.AgreementConfirmed.ConfirmedMilestone;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.Agreements.Criterion;
import vn.vibefix.job.internal.Agreements.Milestone;
import vn.vibefix.job.internal.Agreements.Problem;
import vn.vibefix.job.internal.AgreementReviews.Confirmed;
import vn.vibefix.job.internal.JobLifecycle.Planned;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * Change Requests (FR-36, AD-29, UX-DR57): the Customer or the Mentor proposes new due dates, and in the
 * full form a new scope, Milestone titles and prices, added or removed Milestones, and added, edited, or
 * removed Acceptance Criteria, never a share or the Crew; the resulting Agreement must pass FR-16. The
 * Mentor states the cost and time impact, the Customer approves and the Mentor confirms on the loaded Job
 * version, and the confirmation cancels the removed Milestones (M6, or M7 with the full refund), changes
 * the live entities without re-keying, writes the next immutable confirmed Agreement version, and
 * publishes it ({@link AgreementConfirmed}) so new Milestones get their ledger accounts and new criteria
 * their Stories. A Held Milestone's price waits for Story 14.2. One is open per Job; it lapses when a
 * touched Milestone is accepted or settled, or the Job enters Closing or Cancelled. Every command takes
 * the Job row lock first (AD-4).
 */
@Component
class ChangeRequests {

	static final String PROPOSED = "job.change_request_proposed";

	static final String IMPACT_ADDED = "job.change_request_impact_added";

	static final String APPLIED = "job.change_request_applied";

	static final String LAPSED = "job.change_request_lapsed";

	static final String ADD_IMPACT = "job.add_change_impact";

	static final String ANSWER = "job.answer_change_request";

	static final Set<JobState> RUNNING = Set.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE);

	static final Set<String> FINAL = Set.of("ACCEPTED", "CANCELLED", "SETTLED");

	static final Set<String> HELD = Set.of("FUNDED", "REVISION_REQUESTED", "SUBMITTED");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	enum Party {
		CUSTOMER, MENTOR
	}

	/** One touched Milestone: its number from 1, title, and old and new due dates. */
	record Item(UUID milestoneId, int number, String title, @Nullable LocalDate oldDueOn, LocalDate newDueOn) {
	}

	/** A live Milestone's new title or price, or its removal (FR-36). */
	record MilestoneChange(UUID milestoneId, @Nullable String title, @Nullable Long priceVnd, boolean remove) {
	}

	/** One criterion of an added Milestone. */
	record NewCriterion(UUID id, String statement, String checkSteps) {
	}

	/** A Milestone added after the live ones, Planned, with its criteria. */
	record AddedMilestone(UUID id, String title, long priceVnd, LocalDate dueOn, int revisionRounds,
			List<NewCriterion> criteria) {
	}

	/** A criterion added to a live Milestone. */
	record AddedCriterion(UUID id, UUID milestoneId, String statement, String checkSteps) {
	}

	/** A live criterion's new statement and steps. */
	record EditedCriterion(UUID criterionId, String statement, String checkSteps) {
	}

	/** What a request changes beyond due dates (FR-36): never a share or the Crew. */
	record Changes(@Nullable String scope, List<MilestoneChange> milestones, List<AddedMilestone> addedMilestones,
			List<AddedCriterion> addedCriteria, List<EditedCriterion> editedCriteria, List<UUID> removedCriteria) {

		static final Changes NONE = new Changes(null, List.of(), List.of(), List.of(), List.of(), List.of());

		Changes {
			milestones = milestones == null ? List.of() : List.copyOf(milestones);
			addedMilestones = addedMilestones == null ? List.of() : List.copyOf(addedMilestones);
			addedCriteria = addedCriteria == null ? List.of() : List.copyOf(addedCriteria);
			editedCriteria = editedCriteria == null ? List.of() : List.copyOf(editedCriteria);
			removedCriteria = removedCriteria == null ? List.of() : List.copyOf(removedCriteria);
		}

		boolean isEmpty() {
			return this.scope == null && this.milestones.isEmpty() && this.addedMilestones.isEmpty()
					&& this.addedCriteria.isEmpty() && this.editedCriteria.isEmpty() && this.removedCriteria.isEmpty();
		}

	}

	record ChangeRequest(UUID id, UUID jobId, Party proposer, @Nullable String description, @Nullable String impact,
			List<Item> items, String status, @Nullable Instant customerApprovedAt, @Nullable Instant mentorConfirmedAt,
			@Nullable Integer agreementVersionNo, Instant createdAt, @Nullable Instant closedAt, Changes changes) {
	}

	/** One live Milestone as the form lists it. */
	record LiveMilestone(UUID id, int number, String title, @Nullable LocalDate dueOn, String state) {
	}

	private final JdbcClient jdbc;

	private final Jobs jobs;

	private final AgreementReviews reviews;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final DueDates dueDates;

	private final Agreements agreements;

	private final JobLifecycle lifecycle;

	private final ApplicationEventPublisher events;

	ChangeRequests(JdbcClient jdbc, Jobs jobs, AgreementReviews reviews, Notifications notifications, Todos todos,
			AuditLog auditLog, IdGenerator ids, Clock clock, DueDates dueDates, Agreements agreements, JobLifecycle lifecycle,
			ApplicationEventPublisher events) {
		this.agreements = agreements;
		this.lifecycle = lifecycle;
		this.events = events;
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.reviews = reviews;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.dueDates = dueDates;
	}

	/**
	 * A proposal; the Mentor's carries its impact.
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code GUARD_FAILED NO_CONFIRMED_AGREEMENT} or
	 * {@code HELD_PRICE_CHANGE}; {@code CONFLICT CHANGE_REQUEST_OPEN}; {@code VALIDATION MILESTONE_FINAL};
	 * {@code VALIDATION AGREEMENT_INCOMPLETE} with the FR-16 {@code problems}; {@code VALIDATION}
	 */
	@Transactional
	ChangeRequest propose(UUID jobId, UUID account, Party party, long version, @Nullable String description,
			@Nullable String impact, Map<UUID, LocalDate> dueDates, Changes changes) {
		Job job = locked(jobId, version);
		running(job);
		if (open(jobId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CHANGE_REQUEST_OPEN);
		}
		String text = blankToNull(description);
		String effect = blankToNull(impact);
		if ((party == Party.CUSTOMER && text == null) || (party == Party.MENTOR && effect == null)
				|| (dueDates.isEmpty() && changes.isEmpty()) || (text != null && text.length() > 2000)
				|| (effect != null && effect.length() > 1000)) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Map<UUID, LiveMilestone> live = new HashMap<>();
		milestones(jobId).forEach(milestone -> live.put(milestone.id(), milestone));
		LocalDate today = LocalDate.now(this.clock.withZone(VIETNAM));
		List<Item> items = new ArrayList<>();
		for (Map.Entry<UUID, LocalDate> change : dueDates.entrySet()) {
			LiveMilestone milestone = live.get(change.getKey());
			if (milestone == null) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			if (FINAL.contains(milestone.state())) {
				throw new ApiException(ErrorCode.VALIDATION, JobReason.MILESTONE_FINAL);
			}
			if (!change.getValue().isAfter(today) || change.getValue().equals(milestone.dueOn())) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			items.add(new Item(milestone.id(), milestone.number(), milestone.title(), milestone.dueOn(), change.getValue()));
		}
		items.sort((a, b) -> Integer.compare(a.number(), b.number()));
		Content current = this.reviews.confirmed(jobId).orElseThrow().content();
		check(current, live, changes, today);
		List<Problem> problems = changes.isEmpty() ? List.of() : this.agreements.problems(resulting(current, dueDates, changes));
		if (!problems.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.AGREEMENT_INCOMPLETE,
					Map.of("problems", problems.stream().map(problem -> problem.code().name()).distinct().toList()));
		}
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO job.change_request (id, job_id, proposer, proposer_account_id, description, impact, items, changes,
				    status, created_at)
				VALUES (:id, :job, :proposer, :account, :description, :impact, CAST(:items AS JSONB), CAST(:changes AS JSONB),
				    'OPEN', :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("proposer", party.name())
			.param("account", account)
			.param("description", text)
			.param("impact", effect)
			.param("items", JSON.writeValueAsString(items))
			.param("changes", JSON.writeValueAsString(changes))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		audit(account, "job.change_request_proposed", id, jobId);
		String link = "/cong-viec/" + jobId + "/thoa-thuan";
		UUID mentor = Objects.requireNonNull(job.mentorId());
		if (party == Party.CUSTOMER) {
			this.notifications.send(new NotificationRequest(PROPOSED, id, List.of(new Recipient(mentor, Audience.MENTOR)),
					Map.of("job", job.title()), link, jobId, null));
			this.todos.open(new TodoRequest(ADD_IMPACT, mentor, Audience.MENTOR, id, Map.of("job", job.title()), link, null,
					jobId));
		}
		else {
			this.notifications.send(new NotificationRequest(PROPOSED, id,
					List.of(new Recipient(job.customerId(), Audience.CUSTOMER)), Map.of("job", job.title()), link, jobId, null));
			this.todos.open(new TodoRequest(ANSWER, job.customerId(), Audience.CUSTOMER, id, Map.of("job", job.title()), link,
					null, jobId));
		}
		return find(id).orElseThrow();
	}

	/**
	 * The Mentor's time impact on the Customer's proposal.
	 * @throws ApiException {@code CONFLICT CHANGE_REQUEST_NOT_AWAITING}
	 */
	@Transactional
	ChangeRequest addImpact(UUID requestId, UUID mentor, String impact) {
		ChangeRequest request = existing(requestId);
		Job job = this.jobs.lock(request.jobId()).orElseThrow();
		ChangeRequest current = openOne(requestId);
		String effect = blankToNull(impact);
		if (current.impact() != null || current.proposer() != Party.CUSTOMER) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CHANGE_REQUEST_NOT_AWAITING);
		}
		if (effect == null || effect.length() > 1000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		this.jdbc.sql("UPDATE job.change_request SET impact = :impact WHERE id = :id")
			.param("id", requestId)
			.param("impact", effect)
			.update();
		this.todos.close(ADD_IMPACT, mentor, requestId);
		String link = "/cong-viec/" + job.id() + "/thoa-thuan";
		this.notifications.send(new NotificationRequest(IMPACT_ADDED, requestId,
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)), Map.of("job", job.title()), link, job.id(), null));
		this.todos.open(new TodoRequest(ANSWER, job.customerId(), Audience.CUSTOMER, requestId, Map.of("job", job.title()),
				link, null, job.id()));
		return find(requestId).orElseThrow();
	}

	/**
	 * The Customer's approval on the loaded Job version.
	 * @throws ApiException {@code STALE STATE_CHANGED}; {@code GUARD_FAILED IMPACT_MISSING} or
	 * {@code MILESTONE_SUBMITTED}; {@code CONFLICT CHANGE_REQUEST_NOT_AWAITING}
	 */
	@Transactional
	ChangeRequest approve(UUID requestId, UUID customer, long version) {
		Job job = locked(existing(requestId).jobId(), version);
		ChangeRequest request = openOne(requestId);
		if (request.customerApprovedAt() != null) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CHANGE_REQUEST_NOT_AWAITING);
		}
		if (request.impact() == null) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.IMPACT_MISSING);
		}
		notSubmitted(request);
		this.jdbc.sql("""
				UPDATE job.change_request SET customer_approved_at = :now, customer_account_id = :customer WHERE id = :id
				""").param("id", requestId).param("customer", customer).param("now", Timestamp.from(this.clock.instant())).update();
		audit(customer, "job.change_request_approved", requestId, job.id());
		this.todos.close(ANSWER, customer, requestId);
		UUID mentor = Objects.requireNonNull(job.mentorId());
		this.todos.open(new TodoRequest(ANSWER, mentor, Audience.MENTOR, requestId, Map.of("job", job.title()),
				"/cong-viec/" + job.id() + "/thoa-thuan", null, job.id()));
		return find(requestId).orElseThrow();
	}

	/**
	 * The Mentor's confirmation on the loaded Job version: the request takes effect.
	 * @throws ApiException as {@link #approve}; {@code CONFLICT CHANGE_REQUEST_NOT_AWAITING} before the
	 * Customer's approval; {@code JOB_BUSY} while the Job is busy (AD-28)
	 */
	@Transactional
	ChangeRequest confirm(UUID requestId, UUID mentor, long version) {
		Job job = locked(existing(requestId).jobId(), version);
		running(job);
		if (JobBusyMarkers.isBusy(this.jdbc, job.id())) {
			// AD-28: a Change Request does not take effect while a precondition operation runs.
			throw new ApiException(ErrorCode.JOB_BUSY);
		}
		ChangeRequest request = openOne(requestId);
		if (request.customerApprovedAt() == null) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CHANGE_REQUEST_NOT_AWAITING);
		}
		notSubmitted(request);
		Confirmed confirmed = this.reviews.confirmed(job.id())
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, JobReason.NO_CONFIRMED_AGREEMENT));
		Map<UUID, LocalDate> dueDates = new HashMap<>();
		request.items().forEach(item -> dueDates.put(item.milestoneId(), item.newDueOn()));
		Changes changes = request.changes();
		Content changed = resulting(confirmed.content(), dueDates, changes);
		List<Planned> cascade = removals(job.id(), changes);
		if (!cascade.isEmpty()) {
			ApiException refusal = this.lifecycle.refusal(job.id(), Trigger.USER, cascade);
			if (refusal != null) {
				throw refusal;
			}
			this.lifecycle.tryApplyCascade(job.id(), Trigger.USER, cascade)
				.orElseThrow(() -> new IllegalStateException("The removals of " + requestId + " passed their checks but did not apply"));
		}
		int next = confirmed.versionNo() + 1;
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames, live_access_ids,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				SELECT job_id, :next, draft_version_no, CAST(:content AS JSONB), crew_usernames, live_access_ids, freeze_committed,
				       :customer, :approved, :mentor, :now
				FROM job.confirmed_agreement WHERE job_id = :job AND version_no = :current
				""")
			.param("job", job.id())
			.param("next", next)
			.param("current", confirmed.versionNo())
			.param("content", JSON.writeValueAsString(changed))
			.param("customer", job.customerId())
			.param("approved", Timestamp.from(Objects.requireNonNull(request.customerApprovedAt())))
			.param("mentor", mentor)
			.param("now", Timestamp.from(now))
			.update();
		for (Item item : request.items()) {
			this.jdbc.sql("UPDATE job.milestone SET due_on = :due WHERE id = :id AND job_id = :job")
				.param("id", item.milestoneId())
				.param("job", job.id())
				.param("due", item.newDueOn())
				.update();
			this.dueDates.rescheduled(job.id(), item.milestoneId());
		}
		applyLive(job.id(), changes, now);
		this.jdbc.sql("""
				UPDATE job.job SET agreement_version_no = :next, version = version + 1, updated_at = :now WHERE id = :id
				""").param("id", job.id()).param("next", next).param("now", Timestamp.from(now)).update();
		List<ConfirmedMilestone> published = new ArrayList<>();
		for (Milestone milestone : changed.milestones()) {
			published.add(new ConfirmedMilestone(milestone.id(), published.size() + 1, milestone.title(),
					milestone.priceVnd() == null ? 0 : milestone.priceVnd()));
		}
		this.events.publishEvent(new AgreementConfirmed(this.ids.newId(), job.id(), job.productId(), job.customerId(),
				job.title(), next, published));
		this.jdbc.sql("""
				UPDATE job.change_request SET status = 'APPLIED', mentor_confirmed_at = :now, mentor_account_id = :mentor,
				    agreement_version_no = :next, closed_at = :now
				WHERE id = :id
				""").param("id", requestId).param("mentor", mentor).param("next", next).param("now", Timestamp.from(now)).update();
		audit(mentor, "job.change_request_applied", requestId, job.id());
		this.todos.closeAll(ANSWER, requestId);
		this.notifications.send(new NotificationRequest(APPLIED, requestId, both(job), Map.of("job", job.title()),
				"/cong-viec/" + job.id() + "/thoa-thuan", job.id(), null));
		return find(requestId).orElseThrow();
	}

	/** The proposer withdraws an open request. */
	@Transactional
	ChangeRequest withdraw(UUID requestId, UUID account) {
		ChangeRequest request = existing(requestId);
		this.jobs.lock(request.jobId()).orElseThrow();
		ChangeRequest current = openOne(requestId);
		this.jdbc.sql("UPDATE job.change_request SET status = 'WITHDRAWN', closed_at = :now WHERE id = :id")
			.param("id", requestId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		audit(account, "job.change_request_withdrawn", requestId, current.jobId());
		closeTodos(requestId);
		return find(requestId).orElseThrow();
	}

	/**
	 * The open request lapses: on M5 or M8 of a touched Milestone ({@code milestoneId}), or on J8 or J10
	 * ({@code milestoneId} null).
	 */
	@Transactional
	void lapse(UUID jobId, @Nullable UUID milestoneId) {
		ChangeRequest request = open(jobId).orElse(null);
		if (request == null || (milestoneId != null && !touched(request).contains(milestoneId))) {
			return;
		}
		Job job = this.jobs.lock(jobId).orElseThrow();
		int lapsed = this.jdbc.sql("UPDATE job.change_request SET status = 'LAPSED', closed_at = :now WHERE id = :id AND status = 'OPEN'")
			.param("id", request.id())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		if (lapsed == 0) {
			return;
		}
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "job.change_request_lapsed",
				AuditObject.of("job.change_request", request.id())).forJob(jobId));
		closeTodos(request.id());
		if (job.mentorId() != null) {
			this.notifications.send(new NotificationRequest(LAPSED, request.id(), both(job), Map.of("job", job.title()),
					"/cong-viec/" + jobId + "/thoa-thuan", jobId, null));
		}
	}

	Optional<ChangeRequest> open(UUID jobId) {
		return this.jdbc.sql("SELECT * FROM job.change_request WHERE job_id = :job AND status = 'OPEN'")
			.param("job", jobId)
			.query(ChangeRequests::request)
			.optional();
	}

	List<ChangeRequest> history(UUID jobId) {
		return this.jdbc.sql("SELECT * FROM job.change_request WHERE job_id = :job ORDER BY created_at DESC, id")
			.param("job", jobId)
			.query(ChangeRequests::request)
			.list();
	}

	Optional<ChangeRequest> find(UUID requestId) {
		return this.jdbc.sql("SELECT * FROM job.change_request WHERE id = :id")
			.param("id", requestId)
			.query(ChangeRequests::request)
			.optional();
	}

	/** The Job's live Milestones in order, numbered from 1. */
	List<LiveMilestone> milestones(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id, row_number() OVER (ORDER BY position) AS number, title, due_on, state FROM job.milestone
				WHERE job_id = :job AND removed_at IS NULL ORDER BY position
				""")
			.param("job", jobId)
			.query((rs, n) -> new LiveMilestone(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getString("title"),
					rs.getObject("due_on", LocalDate.class), rs.getString("state")))
			.list();
	}

	private Job locked(UUID jobId, long version) {
		Job job = this.jobs.lock(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (job.version() != version) {
			throw new ApiException(ErrorCode.STALE, JobReason.STATE_CHANGED);
		}
		return job;
	}

	private void running(Job job) {
		if (!RUNNING.contains(job.state()) || job.mentorId() == null || this.reviews.confirmed(job.id()).isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.NO_CONFIRMED_AGREEMENT);
		}
	}

	private ChangeRequest existing(UUID requestId) {
		return find(requestId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private ChangeRequest openOne(UUID requestId) {
		ChangeRequest request = existing(requestId);
		if (!"OPEN".equals(request.status())) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.CHANGE_REQUEST_NOT_AWAITING);
		}
		return request;
	}

	private void notSubmitted(ChangeRequest request) {
		Map<UUID, String> states = new HashMap<>();
		milestones(request.jobId()).forEach(milestone -> states.put(milestone.id(), milestone.state()));
		for (UUID touched : touched(request)) {
			String state = states.get(touched);
			if ("SUBMITTED".equals(state)) {
				throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MILESTONE_SUBMITTED);
			}
			if (state == null || FINAL.contains(state)) {
				throw new ApiException(ErrorCode.VALIDATION, JobReason.MILESTONE_FINAL);
			}
		}
	}

	/** The live Milestones a request touches: its due dates, its Milestone changes, and its criteria's Milestones. */
	Set<UUID> touched(ChangeRequest request) {
		Set<UUID> touched = new HashSet<>();
		request.items().forEach(item -> touched.add(item.milestoneId()));
		Changes changes = request.changes();
		changes.milestones().forEach(change -> touched.add(change.milestoneId()));
		changes.addedCriteria().forEach(added -> touched.add(added.milestoneId()));
		List<UUID> criteria = new ArrayList<>(changes.removedCriteria());
		changes.editedCriteria().forEach(edited -> criteria.add(edited.criterionId()));
		if (!criteria.isEmpty()) {
			touched.addAll(this.jdbc.sql("SELECT milestone_id FROM job.acceptance_criterion WHERE id IN (:ids)")
				.param("ids", criteria)
				.query(UUID.class)
				.list());
		}
		return touched;
	}

	/**
	 * The checks a proposal's changes pass against the live Milestones (FR-36): every reference is live
	 * and on a Milestone that is not final, a Held Milestone's price stays (Story 14.2), and every value
	 * fits the Agreement editor's bounds.
	 */
	private void check(Content current, Map<UUID, LiveMilestone> live, Changes changes, LocalDate today) {
		Map<UUID, UUID> criterionMilestones = new HashMap<>();
		Map<UUID, Long> prices = new HashMap<>();
		for (Milestone milestone : current.milestones()) {
			prices.put(milestone.id(), milestone.priceVnd());
			milestone.criteria().forEach(criterion -> criterionMilestones.put(criterion.id(), milestone.id()));
		}
		Set<UUID> seen = new HashSet<>();
		for (MilestoneChange change : changes.milestones()) {
			LiveMilestone milestone = changeable(live, change.milestoneId());
			if (!seen.add(change.milestoneId()) || (change.title() != null && (change.title().isBlank() || change.title().length() > 120))
					|| (change.priceVnd() != null && (change.priceVnd() < 1000 || change.priceVnd() > 1_000_000_000))) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			if (change.priceVnd() != null && HELD.contains(milestone.state())
					&& !change.priceVnd().equals(prices.get(change.milestoneId()))) {
				throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.HELD_PRICE_CHANGE);
			}
		}
		for (AddedCriterion added : changes.addedCriteria()) {
			changeable(live, added.milestoneId());
			criterion(added.statement(), added.checkSteps());
		}
		for (EditedCriterion edited : changes.editedCriteria()) {
			changeable(live, criterionOn(criterionMilestones, edited.criterionId()));
			criterion(edited.statement(), edited.checkSteps());
		}
		for (UUID removed : changes.removedCriteria()) {
			changeable(live, criterionOn(criterionMilestones, removed));
		}
		for (AddedMilestone added : changes.addedMilestones()) {
			if (added.title().isBlank() || added.title().length() > 120 || added.priceVnd() < 1000
					|| added.priceVnd() > 1_000_000_000 || !added.dueOn().isAfter(today) || added.revisionRounds() < 0
					|| added.revisionRounds() > 10 || live.containsKey(added.id())) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			added.criteria().forEach(criterion -> criterion(criterion.statement(), criterion.checkSteps()));
		}
		if (changes.scope() != null && (changes.scope().isBlank() || changes.scope().length() > 4000)) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
	}

	private static LiveMilestone changeable(Map<UUID, LiveMilestone> live, UUID milestoneId) {
		LiveMilestone milestone = live.get(milestoneId);
		if (milestone == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		if (FINAL.contains(milestone.state())) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.MILESTONE_FINAL);
		}
		return milestone;
	}

	private static UUID criterionOn(Map<UUID, UUID> criterionMilestones, UUID criterionId) {
		UUID milestone = criterionMilestones.get(criterionId);
		if (milestone == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		return milestone;
	}

	private static void criterion(String statement, String checkSteps) {
		if (statement.isBlank() || statement.length() > 500 || checkSteps.isBlank() || checkSteps.length() > 2000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
	}

	/**
	 * The Agreement a request leads to: the new scope, every kept Milestone with its new title, price,
	 * due date, and criteria, then the added Milestones; the price is their sum (FR-16).
	 */
	static Content resulting(Content content, Map<UUID, LocalDate> dueDates, Changes changes) {
		Map<UUID, MilestoneChange> edits = new HashMap<>();
		changes.milestones().forEach(change -> edits.put(change.milestoneId(), change));
		Map<UUID, EditedCriterion> edited = new HashMap<>();
		changes.editedCriteria().forEach(change -> edited.put(change.criterionId(), change));
		Set<UUID> removedCriteria = Set.copyOf(changes.removedCriteria());
		List<Milestone> milestones = new ArrayList<>();
		for (Milestone milestone : content.milestones()) {
			MilestoneChange change = edits.get(milestone.id());
			if (change != null && change.remove()) {
				continue;
			}
			List<Criterion> criteria = new ArrayList<>();
			for (Criterion criterion : milestone.criteria()) {
				if (removedCriteria.contains(criterion.id())) {
					continue;
				}
				EditedCriterion edit = edited.get(criterion.id());
				criteria.add(edit == null ? criterion : new Criterion(criterion.id(), edit.statement(), edit.checkSteps()));
			}
			changes.addedCriteria()
				.stream()
				.filter(added -> added.milestoneId().equals(milestone.id()))
				.forEach(added -> criteria.add(new Criterion(added.id(), added.statement(), added.checkSteps())));
			milestones.add(new Milestone(milestone.id(),
					change != null && change.title() != null ? change.title().strip() : milestone.title(),
					change != null && change.priceVnd() != null ? change.priceVnd() : milestone.priceVnd(),
					dueDates.getOrDefault(milestone.id(), milestone.dueOn()), milestone.revisionRounds(), List.copyOf(criteria)));
		}
		for (AddedMilestone added : changes.addedMilestones()) {
			milestones.add(new Milestone(added.id(), added.title().strip(), added.priceVnd(), added.dueOn(), added.revisionRounds(),
					added.criteria()
						.stream()
						.map(criterion -> new Criterion(criterion.id(), criterion.statement(), criterion.checkSteps()))
						.toList()));
		}
		long price = milestones.stream().mapToLong(milestone -> milestone.priceVnd() == null ? 0 : milestone.priceVnd()).sum();
		return new Content(changes.scope() != null ? changes.scope().strip() : content.scope(),
				changes.isEmpty() ? content.priceVnd() : Long.valueOf(price), content.codeJob(), content.assessment(),
				content.skills(), List.copyOf(milestones), content.externalAccess());
	}

	/** The removed Milestones' cancellations: M6 for a Planned one, M7 with the full refund for a Held one. */
	private List<Planned> removals(UUID jobId, Changes changes) {
		Map<UUID, String> states = new HashMap<>();
		milestones(jobId).forEach(milestone -> states.put(milestone.id(), milestone.state()));
		List<Planned> cascade = new ArrayList<>();
		for (MilestoneChange change : changes.milestones()) {
			String state = states.get(change.milestoneId());
			if (!change.remove() || state == null) {
				continue;
			}
			if ("PLANNED".equals(state)) {
				cascade.add(Planned.milestone(Transition.M6, change.milestoneId(), Map.of(), Trigger.USER));
			}
			else if (HELD.contains(state)) {
				cascade.add(Planned.milestone(Transition.M7, change.milestoneId(), Map.of(), Trigger.USER));
			}
		}
		return cascade;
	}

	/** The live entities follow the request without re-keying; removed ones are marked removed (AD-29). */
	private void applyLive(UUID jobId, Changes changes, Instant now) {
		Timestamp at = Timestamp.from(now);
		for (MilestoneChange change : changes.milestones()) {
			if (change.remove()) {
				this.jdbc.sql("UPDATE job.milestone SET removed_at = :now WHERE id = :id AND job_id = :job")
					.param("now", at).param("id", change.milestoneId()).param("job", jobId).update();
				this.jdbc.sql("""
						UPDATE job.acceptance_criterion SET removed_at = :now WHERE milestone_id = :id AND removed_at IS NULL
						""").param("now", at).param("id", change.milestoneId()).update();
				continue;
			}
			this.jdbc.sql("""
					UPDATE job.milestone SET title = coalesce(:title, title), price_vnd = coalesce(:price, price_vnd)
					WHERE id = :id AND job_id = :job
					""")
				.param("title", change.title() == null ? null : change.title().strip())
				.param("price", change.priceVnd())
				.param("id", change.milestoneId())
				.param("job", jobId)
				.update();
		}
		for (UUID removed : changes.removedCriteria()) {
			this.jdbc.sql("UPDATE job.acceptance_criterion SET removed_at = :now WHERE id = :id AND job_id = :job")
				.param("now", at).param("id", removed).param("job", jobId).update();
		}
		for (EditedCriterion edited : changes.editedCriteria()) {
			this.jdbc.sql("""
					UPDATE job.acceptance_criterion SET statement = :statement, check_steps = :steps WHERE id = :id AND job_id = :job
					""")
				.param("statement", edited.statement().strip())
				.param("steps", edited.checkSteps().strip())
				.param("id", edited.criterionId())
				.param("job", jobId)
				.update();
		}
		for (AddedCriterion added : changes.addedCriteria()) {
			insertCriterion(jobId, added.milestoneId(), added.id(), added.statement(), added.checkSteps(), at);
		}
		for (AddedMilestone added : changes.addedMilestones()) {
			this.jdbc.sql("""
					INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at)
					VALUES (:id, :job, (SELECT coalesce(max(position) + 1, 0) FROM job.milestone WHERE job_id = :job), :title, :price,
					    :due, :rounds, :now)
					""")
				.param("id", added.id())
				.param("job", jobId)
				.param("title", added.title().strip())
				.param("price", added.priceVnd())
				.param("due", added.dueOn())
				.param("rounds", added.revisionRounds())
				.param("now", at)
				.update();
			for (NewCriterion criterion : added.criteria()) {
				insertCriterion(jobId, added.id(), criterion.id(), criterion.statement(), criterion.checkSteps(), at);
			}
		}
	}

	private void insertCriterion(UUID jobId, UUID milestoneId, UUID id, String statement, String checkSteps, Timestamp at) {
		this.jdbc.sql("""
				INSERT INTO job.acceptance_criterion (id, job_id, milestone_id, position, statement, check_steps, created_at)
				VALUES (:id, :job, :milestone,
				    (SELECT coalesce(max(position) + 1, 0) FROM job.acceptance_criterion WHERE milestone_id = :milestone), :statement,
				    :steps, :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("milestone", milestoneId)
			.param("statement", statement.strip())
			.param("steps", checkSteps.strip())
			.param("now", at)
			.update();
	}

	private void closeTodos(UUID requestId) {
		this.todos.closeAll(ADD_IMPACT, requestId);
		this.todos.closeAll(ANSWER, requestId);
	}

	private static List<Recipient> both(Job job) {
		return List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
				new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR));
	}

	private void audit(UUID account, String action, UUID requestId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(account), action, AuditObject.of("job.change_request", requestId))
			.forJob(jobId));
	}

	private static @Nullable String blankToNull(@Nullable String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	private static ChangeRequest request(ResultSet rs, int n) throws SQLException {
		List<Item> items = JSON.readValue(rs.getString("items"), new TypeReference<List<Item>>() {
		});
		Integer version = rs.getObject("agreement_version_no", Integer.class);
		Changes changes = JSON.readValue(rs.getString("changes"), Changes.class);
		return new ChangeRequest(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				Party.valueOf(rs.getString("proposer")), rs.getString("description"), rs.getString("impact"), items,
				rs.getString("status"), instant(rs, "customer_approved_at"), instant(rs, "mentor_confirmed_at"), version,
				rs.getTimestamp("created_at").toInstant(), instant(rs, "closed_at"), changes);
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp at = rs.getTimestamp(column);
		return at == null ? null : at.toInstant();
	}

}
