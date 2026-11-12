package vn.vibefix.product.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.github.SourceApp;
import vn.vibefix.github.SourceApp.WorkflowVerdict;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.IdGenerator;

/**
 * Each Product's last known Source App and workflow status (AD-45, R7, R12, UX-DR118, Story 23.5c), so CUS-2 shows it
 * without a GitHub call. Whatever check found it records it here: CUS-4's "Kiểm tra lại", the Source checks before
 * Setup and the Final Merge and the re-confirmation (their Source App and workflow part: the admin right is no status),
 * the daily drift read, and a contributor read the binding refused. A GitHub failure records nothing. Each record is
 * written in a transaction of its own, under the Product's row lock, so the refused command that found it does not
 * undo it, and acts only on a change:
 * <ul>
 * <li>into a {@code SOURCE_APP_*} status, {@value #INSTALL_SOURCE_APP} opens, and {@value #ADD_WORKFLOW} closes;</li>
 * <li>into a {@code WORKFLOW_*} status, {@value #ADD_WORKFLOW} opens (without a deadline), and
 * {@value #INSTALL_SOURCE_APP} closes;</li>
 * <li>into a failing status, or between failing ones, the Customer gets {@value #CHECK_FAILED} once, in-app and by
 * email, naming what failed; the same status read again sends nothing;</li>
 * <li>on {@code READY}, or a {@code READY} with another blob, both to-dos close; when the matched blob ends within
 * {@value #LEAD_DAYS} days, {@value #ADD_WORKFLOW} opens again with its end date as the deadline (R12).</li>
 * </ul>
 * Every to-do and notification goes to the Product's Customer as {@link Audience#SUBJECT} of the Product, linking to
 * CUS-4; the {@code CUSTOMER} audience in a Job is Story 23.6's. The daily job ({@link #followEndDate}) reads each
 * recorded blob's end date from the accepted set, with no GitHub call: past it, the Product records
 * {@code WORKFLOW_MISMATCH}; within {@value #LEAD_DAYS} days, the Customer is warned once per (Product, blob); no
 * longer ending, the end-date to-do closes.
 */
@Component
class SourceStatuses {

	static final String INSTALL_SOURCE_APP = "product.install_source_app";

	static final String ADD_WORKFLOW = "product.add_workflow";

	/** EXPERIENCE.md's key: every check that finds the failure runs in {@code product}, so {@code product} sends it. */
	static final String CHECK_FAILED = "repo.workflow_check_failed";

	/** How many days before its end date a workflow file is warned of (R12) {@code [ASSUMPTION]}. */
	static final int LEAD_DAYS = 14;

	private static final ZoneId VIETNAM = Products.VIETNAM;

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** A Product's Source App and workflow status, as {@code product.product.source_status} holds it. */
	enum Status {

		READY, SOURCE_APP_MISSING, SOURCE_APP_WRONG_REPOSITORY, WORKFLOW_MISSING, WORKFLOW_MISMATCH;

		boolean sourceApp() {
			return this == SOURCE_APP_MISSING || this == SOURCE_APP_WRONG_REPOSITORY;
		}

		/** The status a workflow file's verdict names. */
		static Status of(WorkflowVerdict verdict) {
			return switch (verdict) {
				case MATCHES -> READY;
				case MISSING -> WORKFLOW_MISSING;
				case MISMATCH -> WORKFLOW_MISMATCH;
			};
		}

	}

	/**
	 * What {@value #CHECK_FAILED} says failed, which the Customer reads in-app and by email: the title's {@code problem}
	 * (every parameter of a type is in its title, {@code NotificationCatalogTest}).
	 */
	static final Map<Status, String> PROBLEMS = Map.of(
			Status.SOURCE_APP_MISSING,
			"ứng dụng vibefix không dùng được trên dự án gốc (đã bị gỡ, bị tạm ngưng, hoặc đang chờ được duyệt)",
			Status.SOURCE_APP_WRONG_REPOSITORY, "ứng dụng vibefix không còn được cài cho đúng một kho là dự án gốc",
			Status.WORKFLOW_MISSING, "dự án gốc không còn file .github/workflows/vibefix.yml",
			Status.WORKFLOW_MISMATCH,
			"file .github/workflows/vibefix.yml khác bản vibefix công bố, hoặc là bản đã hết hạn");

	/** R12's {@code problem}, with the end date (EXPERIENCE.md "Workflow file nearing its end date"). */
	static final String ENDING = "bản file workflow bạn đang dùng hết hạn ngày %s";

	/** A Product's row as the recorder reads it under its lock. */
	private record Row(UUID customerId, String fullName, Status status, @Nullable String blob) {
	}

	private final JdbcClient jdbc;

	private final SourceApp app;

	private final Todos todos;

	private final Notifications notifications;

	private final IdGenerator ids;

	private final Clock clock;

	SourceStatuses(JdbcClient jdbc, SourceApp app, Todos todos, Notifications notifications, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.app = app;
		this.todos = todos;
		this.notifications = notifications;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Records what a check found of the live Product, with the time, and acts on a change; a deleted Product records
	 * nothing.
	 * @param blob the accepted blob the file matched, kept only with {@link Status#READY}
	 * @param defaultBranch the default branch the check read, kept as the Product's when it moved; none when the check
	 * read no repository
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void record(UUID productId, Status status, @Nullable String blob, @Nullable String defaultBranch) {
		Row row = lock(productId);
		if (row != null) {
			apply(productId, row, status, blob, defaultBranch);
		}
	}

	/**
	 * The daily job's step for one Product (R12), from the accepted set with no GitHub call: a recorded blob past its
	 * end date records {@link Status#WORKFLOW_MISMATCH}; one ending within {@value #LEAD_DAYS} days keeps
	 * {@value #ADD_WORKFLOW} open with the end date as its deadline, the Customer told once for the (Product, blob);
	 * one no longer ending (the accepted set dropped its end date or moved it later) closes the end-date to-do.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void followEndDate(UUID productId) {
		Row row = lock(productId);
		String blob = row == null ? null : row.blob();
		if (row == null || row.status() != Status.READY || blob == null) {
			return;
		}
		LocalDate endsOn = this.app.endsOn(blob).orElse(null);
		if (endsOn != null && today().isAfter(endsOn)) {
			apply(productId, row, Status.WORKFLOW_MISMATCH, null, null);
			return;
		}
		Optional<LocalDate> ending = ending(blob);
		if (ending.isPresent()) {
			warnEnding(productId, row, blob, ending.get());
		}
		else {
			// A READY Product's open add_workflow is the end-date one.
			this.todos.close(ADD_WORKFLOW, row.customerId(), productId);
		}
	}

	/** The live {@code READY} Products that hold a matched blob, for the daily job. */
	List<UUID> withBlob() {
		return this.jdbc.sql("""
				SELECT id FROM product.product
				WHERE workflow_blob IS NOT NULL AND source_status = 'READY' AND deleted_at IS NULL ORDER BY id
				""").query(UUID.class).list();
	}

	/**
	 * The end date of {@code blob} when it is today or up to {@value #LEAD_DAYS} days away, Vietnam time (R12): what
	 * CUS-4's "Workflow file nearing its end date" alert shows while it holds. A date already past is no longer
	 * "nearing": the daily job records that version as {@code WORKFLOW_MISMATCH}. No GitHub call.
	 */
	Optional<LocalDate> ending(@Nullable String blob) {
		if (blob == null) {
			return Optional.empty();
		}
		LocalDate today = today();
		return this.app.endsOn(blob)
			.filter(endsOn -> !endsOn.isBefore(today) && !endsOn.isAfter(today.plusDays(LEAD_DAYS)));
	}

	private void apply(UUID productId, Row row, Status status, @Nullable String blob, @Nullable String defaultBranch) {
		String kept = status == Status.READY ? blob : null;
		this.jdbc.sql("""
				UPDATE product.product SET source_status = :status, source_checked_at = :now, workflow_blob = :blob,
				    repository_default_branch = coalesce(:branch, repository_default_branch)
				WHERE id = :id
				""")
			.param("status", status.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.param("blob", kept)
			.param("branch", defaultBranch)
			.param("id", productId)
			.update();
		boolean changed = row.status() != status;
		if (status == Status.READY) {
			if (!changed && Objects.equals(row.blob(), kept)) {
				return;
			}
			this.todos.close(INSTALL_SOURCE_APP, row.customerId(), productId);
			// A later check that records a different blob closes the end-date to-do too (R12).
			this.todos.close(ADD_WORKFLOW, row.customerId(), productId);
			if (kept != null) {
				ending(kept).ifPresent(endsOn -> warnEnding(productId, row, kept, endsOn));
			}
			return;
		}
		if (!changed) {
			return;
		}
		if (status.sourceApp()) {
			this.todos.close(ADD_WORKFLOW, row.customerId(), productId);
			this.todos.open(todo(INSTALL_SOURCE_APP, productId, row, null));
		}
		else {
			this.todos.close(INSTALL_SOURCE_APP, row.customerId(), productId);
			if (row.status() == Status.READY) {
				// An end-date to-do gives way to the failing file's, which has no deadline.
				this.todos.close(ADD_WORKFLOW, row.customerId(), productId);
			}
			this.todos.open(todo(ADD_WORKFLOW, productId, row, null));
		}
		notify(productId, row, PROBLEMS.get(status), this.ids.newId());
	}

	private void warnEnding(UUID productId, Row row, String blob, LocalDate endsOn) {
		Instant deadline = endsOn.atTime(LocalTime.of(23, 59)).atZone(VIETNAM).toInstant();
		this.todos.open(todo(ADD_WORKFLOW, productId, row, deadline));
		notify(productId, row, ENDING.formatted(DAY.format(endsOn)),
				UUID.nameUUIDFromBytes(("product.workflow_ends:" + productId + ":" + blob).getBytes(StandardCharsets.UTF_8)));
	}

	private TodoRequest todo(String key, UUID productId, Row row, @Nullable Instant deadline) {
		return new TodoRequest(key, row.customerId(), Audience.SUBJECT, productId, Map.of("repo", row.fullName()),
				link(productId), deadline, null);
	}

	private void notify(UUID productId, Row row, String problem, UUID cause) {
		this.notifications.send(new NotificationRequest(CHECK_FAILED, cause,
				List.of(new Recipient(row.customerId(), Audience.SUBJECT)),
				Map.of("repo", row.fullName(), "problem", problem), link(productId), null, productId));
	}

	private LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), VIETNAM);
	}

	/** CUS-4, where the Customer fixes it. */
	static String link(UUID productId) {
		return "/san-pham/" + productId;
	}

	private @Nullable Row lock(UUID productId) {
		return this.jdbc.sql("""
				SELECT customer_account_id, repository_full_name, source_status, workflow_blob FROM product.product
				WHERE id = :id AND deleted_at IS NULL FOR UPDATE
				""")
			.param("id", productId)
			.query((rs, n) -> new Row(rs.getObject("customer_account_id", UUID.class), rs.getString("repository_full_name"),
					Status.valueOf(rs.getString("source_status")), rs.getString("workflow_blob")))
			.optional()
			.orElse(null);
	}

}
