package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.identity.PersonDirectory.PersonCard;
import vn.vibefix.job.CrewMemberRemoved;
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
 * <p>
 * A member who leaves, or whose lock counted as leaving, gets one more row per confirmed item beside those, identifier
 * given or not, since the platform cannot know what the Customer granted (FR-66, R15, Story 21.6), each with its own to-do
 * {@value #MEMBER_TODO} naming the item and the member, and reminders after 1 and 7 days. The audit names such a row by
 * its to-do's subject, since it shares its access ID with the row of the Job's end and every other leaver's. An erasure
 * anonymizes such a row and closes its to-do, and it is listed no more: nobody is left to name.
 */
@Component
class RevocationChecklists {

	static final String TODO = "repo.revocation_item";

	static final String MEMBER_TODO = "repo.revocation_item_member";

	static final String PENDING = "repo.revocation_checklist_pending";

	static final String REMINDER = "repo.revocation_checklist_reminder";

	static final List<Duration> REMINDERS = List.of(Duration.ofDays(1), Duration.ofDays(7));

	/** The {@code crew_row_id} of a row J8 or J10 opened, which names no member. */
	static final UUID JOB_END = new UUID(0, 0);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * One checklist item.
	 * @param outcome {@code REMOVED} or {@code ROTATED} once confirmed
	 * @param crewRowId the leaver's Crew row; {@link #JOB_END} on a row of the Job's end
	 * @param memberLogin the leaver's GitHub username, on a leaver's row only
	 */
	record RevocationItem(UUID accessId, String system, String purpose, String environment, String accessLevel,
			List<Identifier> identifiers, String status, @Nullable String outcome, @Nullable Instant confirmedAt,
			UUID crewRowId, @Nullable String memberLogin) {
	}

	private final JdbcClient jdbc;

	private final ExternalAccessItems access;

	private final JobBusy busy;

	private final PersonDirectory people;

	private final Todos todos;

	private final Notifications notifications;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	RevocationChecklists(JdbcClient jdbc, ExternalAccessItems access, JobBusy busy, PersonDirectory people, Todos todos,
			Notifications notifications, Deadlines deadlines, AuditLog auditLog, ProcessedEvents processedEvents, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.busy = busy;
		this.people = people;
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

	@ApplicationModuleListener
	void onCrewMemberRemoved(CrewMemberRemoved event) {
		if (this.processedEvents.markProcessed("repo.RevocationChecklists.onCrewMemberRemoved", event.eventId())) {
			memberLeft(event);
		}
	}

	/**
	 * A leaver's rows, one per confirmed item, each naming the item and the member, with the identifier they entered if
	 * any. Nothing before the Agreement is confirmed. Replay-safe.
	 */
	@Transactional
	void memberLeft(CrewMemberRemoved event) {
		UUID jobId = event.jobId();
		BusyJob job = this.busy.job(jobId).orElse(null);
		List<ExternalAccessItems.Item> items = job == null ? List.of() : this.access.items(jobId);
		String login = items.isEmpty() ? null
				: this.people.cards(List.of(event.studentId())).stream().findFirst().map(PersonCard::githubLogin).orElse(null);
		if (job == null || login == null) {
			return;
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		boolean opened = false;
		for (ExternalAccessItems.Item item : items) {
			List<Identifier> theirs = item.identifiers().stream().filter(identifier -> login.equals(identifier.githubLogin())).toList();
			int inserted = this.jdbc.sql("""
					INSERT INTO repo.revocation_item (job_id, access_id, crew_row_id, member_account_id, member_login, product_id,
					    customer_account_id, system, purpose, environment, access_level, identifiers, status, created_at)
					VALUES (:job, :access, :row, :member, :login, :product, :customer, :system, :purpose, :environment, :level,
					    CAST(:identifiers AS JSONB), 'PENDING', :now)
					ON CONFLICT DO NOTHING
					""")
				.param("job", jobId)
				.param("access", item.id())
				.param("row", event.crewRowId())
				.param("member", event.studentId())
				.param("login", login)
				.param("product", job.productId())
				.param("customer", job.customerId())
				.param("system", item.system())
				.param("purpose", item.purpose())
				.param("environment", item.environment())
				.param("level", item.accessLevel())
				.param("identifiers", JSON.writeValueAsString(theirs))
				.param("now", now)
				.update();
			if (inserted == 1) {
				opened = true;
				UUID subject = memberSubject(item.id(), event.crewRowId());
				this.todos.open(new TodoRequest(MEMBER_TODO, job.customerId(), Audience.CUSTOMER, subject,
						Map.of("system", item.system(), "login", login), link(jobId), null, jobId));
				audit("repo.revocation_item_opened", jobId, subject, null);
			}
		}
		if (opened) {
			// One generation per leaver: a second leaver's reminders, and J8's or J10's, are their own.
			for (Duration after : REMINDERS) {
				this.deadlines.schedule(new DeadlineRequest(RevocationReminder.KEY, jobId, after.toDays() + ":" + event.crewRowId(),
						jobId, this.clock.instant().plus(after)));
			}
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
	 * @param memberLogin the leaver a row names; {@code null} for the row of the Job's end
	 * @throws ApiException {@code NOT_FOUND} for no such item
	 */
	@Transactional
	RevocationItem confirm(UUID jobId, UUID accessId, @Nullable String memberLogin, UUID customerId, String outcome) {
		RevocationItem item = find(jobId, accessId, memberLogin).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if ("CONFIRMED".equals(item.status())) {
			return item;
		}
		this.jdbc.sql("""
				UPDATE repo.revocation_item SET status = 'CONFIRMED', outcome = :outcome, confirmed_at = :now
				WHERE job_id = :job AND access_id = :access AND crew_row_id = :row AND status = 'PENDING'
				""").param("outcome", outcome).param("now", Timestamp.from(this.clock.instant())).param("job", jobId)
			.param("access", accessId).param("row", item.crewRowId()).update();
		UUID subject = memberLogin == null ? accessId : memberSubject(accessId, item.crewRowId());
		this.todos.close(memberLogin == null ? TODO : MEMBER_TODO, customerId, subject);
		audit("repo.revocation_item_confirmed", jobId, subject, customerId);
		return find(jobId, accessId, memberLogin).orElseThrow();
	}

	/** The Job's items, in the order they were opened; an anonymized leaver's row is no longer one. */
	@Transactional(readOnly = true)
	List<RevocationItem> items(UUID jobId) {
		return this.jdbc.sql("""
				SELECT access_id, system, purpose, environment, access_level, identifiers::text AS identifiers, status, outcome,
				    confirmed_at, crew_row_id, member_login
				FROM repo.revocation_item WHERE job_id = :job AND (crew_row_id = :end OR member_login IS NOT NULL)
				ORDER BY created_at, system, access_id, crew_row_id
				""").param("job", jobId).param("end", JOB_END).query((rs, n) -> {
					Timestamp confirmed = rs.getTimestamp("confirmed_at");
					return new RevocationItem(rs.getObject("access_id", UUID.class), rs.getString("system"),
							rs.getString("purpose"), rs.getString("environment"), rs.getString("access_level"),
							JSON.readValue(rs.getString("identifiers"), new TypeReference<List<Identifier>>() {
							}), rs.getString("status"), rs.getString("outcome"),
							confirmed == null ? null : confirmed.toInstant(), rs.getObject("crew_row_id", UUID.class),
							rs.getString("member_login"));
				}).list();
	}

	/**
	 * The reminder: still unticked items tell the Customer how many.
	 * @param generation which reminder this is, once per Customer
	 */
	@Transactional
	boolean remind(UUID jobId, String generation) {
		long pending = this.items(jobId).stream().filter(item -> "PENDING".equals(item.status())).count();
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (pending == 0 || job == null) {
			return false;
		}
		this.notifications.send(new NotificationRequest(REMINDER, cause(jobId, "reminder:" + generation),
				List.of(new Recipient(job.customerId(), Audience.CUSTOMER)),
				Map.of("job", job.title(), "n", String.valueOf(pending)), link(jobId), jobId, null));
		return true;
	}

	private Optional<RevocationItem> find(UUID jobId, UUID accessId, @Nullable String memberLogin) {
		return items(jobId).stream()
			.filter(item -> item.accessId().equals(accessId) && Objects.equals(item.memberLogin(), memberLogin))
			.findFirst();
	}

	/** What names a leaver's to-do for one item: the Customer's to-do for the item itself has the item's ID. */
	static UUID memberSubject(UUID accessId, UUID crewRowId) {
		return UUID.nameUUIDFromBytes((accessId + ":revocation:" + crewRowId).getBytes(StandardCharsets.UTF_8));
	}

	private void audit(String action, UUID jobId, UUID subject, @Nullable UUID customerId) {
		AuditActor actor = customerId == null ? AuditActor.system() : AuditActor.user(customerId);
		this.auditLog.record(AuditEntry.of(actor, action, AuditObject.of("repo.revocation_item", subject)).forJob(jobId));
	}

	private static String link(UUID jobId) {
		return "/cong-viec/" + jobId;
	}

	private static UUID cause(UUID jobId, String fact) {
		return UUID.nameUUIDFromBytes((jobId + ":revocation:" + fact).getBytes(StandardCharsets.UTF_8));
	}

}
