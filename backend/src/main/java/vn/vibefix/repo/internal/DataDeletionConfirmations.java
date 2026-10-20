package vn.vibefix.repo.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
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

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.notification.Todos;
import vn.vibefix.repo.DataDeletionConfirmed;
import vn.vibefix.repo.DataDeletions;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The post-Job data-deletion confirmations (FR-66, AD-19, AD-41): the duty falls on the Job's Mentor and
 * on every Crew member who was ever active, whatever their row's status now. Each item is confirmed
 * once, with the person and the time, and audited; both together complete it, close the to-do, and are
 * audited too. Only after J8 or J10.
 */
@Component
class DataDeletionConfirmations implements DataDeletions {

	static final String TODO = "repo.confirm_data_deletion";

	static final Set<String> ENDED = Set.of("CLOSING", "CLOSED", "CANCELLED");

	enum Item {
		NON_CODE_DATA, PREVIEWS
	}

	/** One person with the duty and what they confirmed. */
	record Duty(UUID accountId, boolean mentor, Map<Item, Instant> confirmed) {

		boolean complete() {
			return this.confirmed.size() == Item.values().length;
		}

	}

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final Crews crews;

	private final Todos todos;

	private final AuditLog auditLog;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	DataDeletionConfirmations(JdbcClient jdbc, JobPosts posts, Crews crews, Todos todos, AuditLog auditLog, Clock clock,
			ApplicationEventPublisher events, IdGenerator ids) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.crews = crews;
		this.todos = todos;
		this.auditLog = auditLog;
		this.clock = clock;
		this.events = events;
		this.ids = ids;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean confirmed(UUID jobId, UUID accountId) {
		return confirmedItems(jobId, accountId).size() == Item.values().length;
	}

	/** Everyone with the duty: the Mentor first, then each Crew member who was ever active. */
	@Transactional(readOnly = true)
	List<Duty> duties(JobPostState job) {
		Set<UUID> people = new LinkedHashSet<>();
		if (job.mentorId() != null) {
			people.add(job.mentorId());
		}
		this.crews.crewOf(job.jobId(), Selection.EVER_ACTIVE).stream().map(CrewMember::studentId).forEach(people::add);
		List<Duty> duties = new ArrayList<>();
		for (UUID person : people) {
			duties.add(new Duty(person, person.equals(job.mentorId()), confirmedItems(job.jobId(), person)));
		}
		return duties;
	}

	/** The person's duty on the Job, when they have one. */
	@Transactional(readOnly = true)
	Optional<Duty> duty(JobPostState job, UUID accountId) {
		return duties(job).stream().filter(duty -> duty.accountId().equals(accountId)).findFirst();
	}

	/**
	 * Confirms one item, once.
	 * @throws ApiException {@code FORBIDDEN NOT_A_DUTY}; {@code GUARD_FAILED JOB_NOT_ENDED}
	 */
	@Transactional
	Duty confirm(JobPostState job, UUID accountId, Item item) {
		Duty duty = duty(job, accountId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, RepoReason.NOT_A_DUTY));
		if (!ENDED.contains(job.state())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, RepoReason.JOB_NOT_ENDED);
		}
		if (duty.confirmed().containsKey(item)) {
			return duty;
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO repo.data_deletion_confirmation (job_id, product_id, account_id, item, confirmed_at)
				VALUES (:job, :product, :account, :item, :now) ON CONFLICT DO NOTHING
				""")
			.param("job", job.jobId())
			.param("product", job.productId())
			.param("account", accountId)
			.param("item", item.name())
			.param("now", Timestamp.from(now))
			.update();
		audit(accountId, "repo.data_deletion_item_confirmed", job.jobId());
		Duty updated = duty(job, accountId).orElseThrow();
		if (updated.complete()) {
			audit(accountId, "repo.data_deletion_confirmed", job.jobId());
			this.todos.close(TODO, accountId, job.jobId());
			this.events.publishEvent(new DataDeletionConfirmed(this.ids.newId(), job.jobId(), accountId));
		}
		return updated;
	}

	/** The Job with a Board's people, when it has a Mentor. */
	@Transactional(readOnly = true)
	Optional<JobPostState> job(UUID jobId) {
		return this.posts.post(jobId).filter(post -> post.mentorId() != null);
	}

	private Map<Item, Instant> confirmedItems(UUID jobId, UUID accountId) {
		Map<Item, Instant> items = new EnumMap<>(Item.class);
		this.jdbc.sql("""
				SELECT item, confirmed_at FROM repo.data_deletion_confirmation WHERE job_id = :job AND account_id = :account
				""")
			.param("job", jobId)
			.param("account", accountId)
			.query((rs, n) -> items.put(Item.valueOf(rs.getString("item")), rs.getTimestamp("confirmed_at").toInstant()))
			.list();
		return items;
	}

	private void audit(@Nullable UUID account, String action, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(Objects.requireNonNull(account)), action,
				AuditObject.of("job.job", jobId)).forJob(jobId));
	}

}
