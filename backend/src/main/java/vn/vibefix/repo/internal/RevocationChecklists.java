package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.ExternalAccessItems;
import vn.vibefix.job.ExternalAccessItems.Identifier;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.ProcessedEvents;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * The Revocation Checklist (FR-66, AD-7): at J8 or J10 on a Job with External Access items, the Customer
 * gets one item per access they granted outside GitHub, each audited as opened, with the to-do
 * {@value #TODO} and the notice {@value #PENDING}; reminders follow after 1 and 7 days while any stays
 * unticked ({@value #REMINDER}, a non-pausable Deadline). The Customer confirms each as removed or rotated;
 * closure never waits for them.
 */
@Component
class RevocationChecklists {

	static final String TODO = "repo.revocation_item";

	static final String PENDING = "repo.revocation_checklist_pending";

	static final String REMINDER = "repo.revocation_checklist_reminder";

	static final List<Duration> REMINDERS = List.of(Duration.ofDays(1), Duration.ofDays(7));

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** One checklist item; {@code outcome} is {@code REMOVED} or {@code ROTATED} once confirmed. */
	record RevocationItem(UUID accessId, String system, String purpose, String environment, String accessLevel,
			List<Identifier> identifiers, String status, @Nullable String outcome, @Nullable Instant confirmedAt) {
	}

	private final JdbcClient jdbc;

	private final ExternalAccessItems access;

	private final JobBusy busy;

	private final Todos todos;

	private final Notifications notifications;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	RevocationChecklists(JdbcClient jdbc, ExternalAccessItems access, JobBusy busy, Todos todos, Notifications notifications,
			Deadlines deadlines, AuditLog auditLog, ProcessedEvents processedEvents, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.busy = busy;
		this.todos = todos;
		this.notifications = notifications;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean ends = "J8".equals(event.transition()) || "J10".equals(event.transition());
		if (ends && this.processedEvents.markProcessed("repo.RevocationChecklists.onJobTransitioned", event.eventId())) {
			open(event.jobId());
		}
	}

	/** Opens the checklist once; a Job without External Access items gets none. */
	@Transactional
	void open(UUID jobId) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		List<ExternalAccessItems.Item> items = job == null ? List.of() : this.access.items(jobId);
		if (job == null || items.isEmpty()) {
			return;
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		int opened = 0;
		for (ExternalAccessItems.Item item : items) {
			int inserted = this.jdbc.sql("""
					INSERT INTO repo.revocation_item (job_id, access_id, product_id, customer_account_id, system, purpose,
					    environment, access_level, identifiers, status, created_at)
					VALUES (:job, :access, :product, :customer, :system, :purpose, :environment, :level,
					    CAST(:identifiers AS JSONB), 'PENDING', :now)
					ON CONFLICT DO NOTHING
					""")
				.param("job", jobId)
				.param("access", item.id())
				.param("product", job.productId())
				.param("customer", job.customerId())
				.param("system", item.system())
				.param("purpose", item.purpose())
				.param("environment", item.environment())
				.param("level", item.accessLevel())
				.param("identifiers", JSON.writeValueAsString(item.identifiers()))
				.param("now", now)
				.update();
			if (inserted == 1) {
				opened++;
				this.todos.open(new TodoRequest(TODO, job.customerId(), Audience.CUSTOMER, item.id(),
						Map.of("system", item.system()), link(jobId), null, jobId));
				audit("repo.revocation_item_opened", jobId, item.id(), null);
			}
		}
		if (opened == 0) {
			return;
		}
		this.notifications.send(new NotificationRequest(PENDING, cause(jobId, "pending"),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)), Map.of("job", job.title()), link(jobId), jobId, null));
		for (Duration after : REMINDERS) {
			this.deadlines.schedule(new DeadlineRequest(RevocationReminder.KEY, jobId, String.valueOf(after.toDays()), jobId,
					this.clock.instant().plus(after)));
		}
	}

	/**
	 * "Đã gỡ quyền" or "Đã đổi khóa": the item is done, audited with its time, and its to-do closes.
	 * Confirming twice changes nothing.
	 * @throws ApiException {@code NOT_FOUND} for no such item
	 */
	@Transactional
	RevocationItem confirm(UUID jobId, UUID accessId, UUID customerId, String outcome) {
		RevocationItem item = find(jobId, accessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if ("CONFIRMED".equals(item.status())) {
			return item;
		}
		this.jdbc.sql("""
				UPDATE repo.revocation_item SET status = 'CONFIRMED', outcome = :outcome, confirmed_at = :now
				WHERE job_id = :job AND access_id = :access AND status = 'PENDING'
				""").param("outcome", outcome).param("now", Timestamp.from(this.clock.instant())).param("job", jobId)
			.param("access", accessId).update();
		this.todos.close(TODO, customerId, accessId);
		audit("repo.revocation_item_confirmed", jobId, accessId, customerId);
		return find(jobId, accessId).orElseThrow();
	}

	/** The Job's items, in the order they were opened. */
	@Transactional(readOnly = true)
	List<RevocationItem> items(UUID jobId) {
		return this.jdbc.sql("""
				SELECT access_id, system, purpose, environment, access_level, identifiers::text AS identifiers, status, outcome,
				    confirmed_at
				FROM repo.revocation_item WHERE job_id = :job ORDER BY created_at, system, access_id
				""").param("job", jobId).query((rs, n) -> {
					Timestamp confirmed = rs.getTimestamp("confirmed_at");
					return new RevocationItem(rs.getObject("access_id", UUID.class), rs.getString("system"),
							rs.getString("purpose"), rs.getString("environment"), rs.getString("access_level"),
							JSON.readValue(rs.getString("identifiers"), new TypeReference<List<Identifier>>() {
							}), rs.getString("status"), rs.getString("outcome"),
							confirmed == null ? null : confirmed.toInstant());
				}).list();
	}

	/** The reminder: still unticked items tell the Customer how many. */
	@Transactional
	boolean remind(UUID jobId, String day) {
		long pending = this.items(jobId).stream().filter(item -> "PENDING".equals(item.status())).count();
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (pending == 0 || job == null) {
			return false;
		}
		this.notifications.send(new NotificationRequest(REMINDER, cause(jobId, "reminder:" + day),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
				Map.of("job", job.title(), "n", String.valueOf(pending)), link(jobId), jobId, null));
		return true;
	}

	private java.util.Optional<RevocationItem> find(UUID jobId, UUID accessId) {
		return items(jobId).stream().filter(item -> item.accessId().equals(accessId)).findFirst();
	}

	private void audit(String action, UUID jobId, UUID accessId, @Nullable UUID customerId) {
		AuditActor actor = customerId == null ? AuditActor.system() : AuditActor.user(customerId);
		this.auditLog.record(AuditEntry.of(actor, action, AuditObject.of("repo.revocation_item", accessId)).forJob(jobId));
	}

	private static String link(UUID jobId) {
		return "/cong-viec/" + jobId;
	}

	private static UUID cause(UUID jobId, String fact) {
		return UUID.nameUUIDFromBytes((jobId + ":revocation:" + fact).getBytes(StandardCharsets.UTF_8));
	}

}
