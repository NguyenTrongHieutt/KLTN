package vn.vibefix.work.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.work.internal.PageTemplates.Template;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkAccess.Kind;
import vn.vibefix.work.internal.WorkAccess.Window;
import vn.vibefix.work.internal.WorkTextCheck.MatchView;
import vn.vibefix.work.internal.WorkTextCheck.Resolution;

/**
 * The Documentation Space's Pages (FR-29, FR-47, AD-20, AD-23, AD-41): a tree of at most two levels per
 * Job, each Page a list of append-only versions. Every save and every restore writes a new version,
 * naming the version it was made on; the title and the body pass the FR-47 check first. Tree changes
 * run under the Board lock. Each viewer sees what they may: the Mentor and the active Crew everything; a
 * former member the Pages with a version inside one of their windows, each as at the end of their last
 * window; an Administrator what a {@code PAGES} grant covers, each read audited; the Customer only
 * customer-visible Pages at their current version (FR-31). A Page may start from a template (FR-30),
 * and only the Mentor changes its visibility; an Assessment Report is always customer-visible.
 */
@Component
class Pages {

	enum Visibility {
		TEAM, CUSTOMER
	}

	record Page(UUID id, UUID jobId, @Nullable UUID parentId, String title, int versionNo, @Nullable UUID createdBy,
			Instant createdAt, Instant updatedAt, @Nullable Template template, Visibility visibility) {
	}

	/** @param restoredFrom the version a restore copied, or {@code null} for a save */
	record Version(UUID pageId, int versionNo, String title, String body, @Nullable UUID author,
			@Nullable Integer restoredFrom, Instant at) {
	}

	/** A Page as the viewer sees it: the version they may read. */
	record Seen(Page page, Version version) {
	}

	private static final String PAGE_COLUMNS = """
			id, job_id, parent_id, title, version_no, created_by_account_id, created_at, updated_at, template, visibility
			""";

	private static final String VERSION_COLUMNS = "page_id, version_no, title, body, author_account_id, restored_from, created_at";

	private final JdbcClient jdbc;

	private final Boards boards;

	private final WorkTextCheck check;

	private final PageTemplates templates;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	Pages(JdbcClient jdbc, Boards boards, WorkTextCheck check, PageTemplates templates, AuditLog auditLog, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.boards = boards;
		this.check = check;
		this.templates = templates;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	// ---- Commands --------------------------------------------------------------------------

	/**
	 * A new Page, from {@code template} when one is named: a blank body takes its skeleton. The Assessment
	 * Report is offered only on an Assessment Job, once, and starts customer-visible (FR-10, FR-31).
	 */
	@Transactional
	Page create(BoardViewer writer, String title, String body, @Nullable UUID parentId, @Nullable Template template,
			Resolution resolution) {
		UUID jobId = writer.board().jobId();
		this.boards.lockBoard(jobId);
		requireParent(jobId, null, parentId);
		if (template != null && template.report()) {
			if (!writer.assessment()) {
				throw new ApiException(ErrorCode.VALIDATION, WorkReason.TEMPLATE_NOT_AVAILABLE);
			}
			if (hasReport(jobId)) {
				throw new ApiException(ErrorCode.CONFLICT, WorkReason.REPORT_EXISTS);
			}
		}
		String start = template != null && body.isBlank() ? this.templates.skeleton(template).body() : body;
		Text text = checked(title, start, resolution);
		UUID id = this.ids.newId();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, parent_id, title, version_no, created_by_account_id, created_at, updated_at,
				    template, visibility)
				VALUES (:id, :job, :parent, :title, 1, :author, :now, :now, :template, :visibility)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("parent", parentId)
			.param("title", text.title())
			.param("author", writer.accountId())
			.param("now", now)
			.param("template", template == null ? null : template.name())
			.param("visibility", (template != null && template.report() ? Visibility.CUSTOMER : Visibility.TEAM).name())
			.update();
		insertVersion(id, 1, text, writer.accountId(), null);
		return page(id).orElseThrow();
	}

	/** Saves a version made on {@code version}; {@code parentId} moves the Page in the tree. */
	@Transactional
	Page save(BoardViewer writer, UUID pageId, int version, String title, String body, @Nullable UUID parentId,
			Resolution resolution) {
		this.boards.lockBoard(writer.board().jobId());
		Page page = locked(writer, pageId, version);
		if (!Objects.equals(parentId, page.parentId())) {
			requireParent(page.jobId(), page.id(), parentId);
		}
		Text text = checked(title, body, resolution);
		return writeVersion(writer, page, parentId, text, null);
	}

	/** "Khôi phục": a new version with the text of version {@code from}, made on {@code version}. */
	@Transactional
	Page restore(BoardViewer writer, UUID pageId, int version, int from) {
		Page page = locked(writer, pageId, version);
		Version earlier = version(pageId, from).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return writeVersion(writer, page, page.parentId(), new Text(earlier.title(), earlier.body()), from);
	}

	/** "Chỉ nhóm" or "Khách hàng xem được": only the Mentor, never for the Assessment Report (FR-31). */
	@Transactional
	Page setVisibility(BoardViewer writer, UUID pageId, Visibility visibility) {
		Page page = this.jdbc.sql("SELECT " + PAGE_COLUMNS + " FROM work.page WHERE id = :id FOR UPDATE")
			.param("id", pageId)
			.query(Pages::page)
			.optional()
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!writer.mentor()) {
			throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.MENTOR_ONLY);
		}
		if (page.visibility() == visibility) {
			return page;
		}
		if (page.template() != null && page.template().report()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.REPORT_ALWAYS_VISIBLE);
		}
		this.jdbc.sql("UPDATE work.page SET visibility = :visibility WHERE id = :id")
			.param("id", pageId)
			.param("visibility", visibility.name())
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(writer.accountId()), "work.page_visibility_changed",
				AuditObject.of("work.page", pageId)).forJob(page.jobId()));
		return page(pageId).orElseThrow();
	}

	/** Whether the Job already has its Assessment Report. */
	@Transactional(readOnly = true)
	boolean hasReport(UUID jobId) {
		return this.jdbc.sql("SELECT count(*) FROM work.page WHERE job_id = :job AND template = 'ASSESSMENT_REPORT'")
			.param("job", jobId)
			.query(Long.class)
			.single() > 0;
	}

	private Page writeVersion(BoardViewer writer, Page page, @Nullable UUID parentId, Text text,
			@Nullable Integer restoredFrom) {
		int next = page.versionNo() + 1;
		this.jdbc.sql("""
				UPDATE work.page SET title = :title, parent_id = :parent, version_no = :next, updated_at = :now WHERE id = :id
				""")
			.param("id", page.id())
			.param("title", text.title())
			.param("parent", parentId)
			.param("next", next)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		insertVersion(page.id(), next, text, writer.accountId(), restoredFrom);
		return page(page.id()).orElseThrow();
	}

	private Page locked(BoardViewer writer, UUID pageId, int version) {
		Page page = this.jdbc.sql("SELECT " + PAGE_COLUMNS + " FROM work.page WHERE id = :id FOR UPDATE")
			.param("id", pageId)
			.query(Pages::page)
			.optional()
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (page.versionNo() != version) {
			throw new ApiException(ErrorCode.STALE);
		}
		return page;
	}

	/** The tree has two levels: a parent is a top-level Page of the Job, and a Page with children stays on top. */
	private void requireParent(UUID jobId, @Nullable UUID pageId, @Nullable UUID parentId) {
		if (parentId == null) {
			return;
		}
		Page parent = page(parentId).filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION, WorkReason.PAGE_PARENT_UNKNOWN));
		boolean hasChildren = pageId != null && this.jdbc.sql("SELECT count(*) FROM work.page WHERE parent_id = :id")
			.param("id", pageId)
			.query(Long.class)
			.single() > 0;
		if (parent.parentId() != null || parentId.equals(pageId) || hasChildren) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.PAGE_TOO_DEEP);
		}
	}

	private record Text(String title, String body) {
	}

	private Text checked(String title, String body, Resolution resolution) {
		List<MatchView> matches = new ArrayList<>();
		String maskedTitle = this.check.masked(title, matches);
		String maskedBody = this.check.maskedLong(body, matches);
		WorkTextCheck.requireResolved(matches, resolution);
		return new Text(maskedTitle, maskedBody);
	}

	private void insertVersion(UUID pageId, int versionNo, Text text, UUID author, @Nullable Integer restoredFrom) {
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, restored_from, created_at)
				VALUES (:page, :no, :title, :body, :author, :from, :now)
				""")
			.param("page", pageId)
			.param("no", versionNo)
			.param("title", text.title())
			.param("body", text.body())
			.param("author", author)
			.param("from", restoredFrom)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	// ---- Reads -----------------------------------------------------------------------------

	@Transactional(readOnly = true)
	Optional<Page> page(UUID pageId) {
		return this.jdbc.sql("SELECT " + PAGE_COLUMNS + " FROM work.page WHERE id = :id")
			.param("id", pageId)
			.query(Pages::page)
			.optional();
	}

	@Transactional(readOnly = true)
	Optional<Version> version(UUID pageId, int versionNo) {
		return this.jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM work.page_version WHERE page_id = :page AND version_no = :no")
			.param("page", pageId)
			.param("no", versionNo)
			.query(Pages::version)
			.optional();
	}

	/** The Pages the viewer sees, in tree order (parents in creation order, each with its children). */
	@Transactional
	List<Seen> visible(BoardViewer viewer) {
		List<Page> pages = this.jdbc.sql("SELECT " + PAGE_COLUMNS + " FROM work.page WHERE job_id = :job ORDER BY seq")
			.param("job", viewer.board().jobId())
			.query(Pages::page)
			.list();
		List<Seen> seen = new ArrayList<>();
		for (Page page : pages) {
			Optional<Version> version = seenVersion(viewer, page);
			version.ifPresent(found -> seen.add(new Seen(page, found)));
		}
		if (viewer.kind() == Kind.ADMIN) {
			audit(viewer, AuditObject.of("work.board", viewer.board().jobId()));
		}
		Set<UUID> ids = seen.stream().map(entry -> entry.page().id()).collect(Collectors.toSet());
		List<Seen> ordered = new ArrayList<>();
		for (Seen top : seen) {
			if (top.page().parentId() == null || !ids.contains(top.page().parentId())) {
				ordered.add(top);
				seen.stream().filter(child -> top.page().id().equals(child.page().parentId())).forEach(ordered::add);
			}
		}
		return ordered;
	}

	/** One Page as the viewer sees it, or {@code NOT_FOUND}; an Administrator's read is audited. */
	@Transactional
	Seen seen(BoardViewer viewer, Page page) {
		Version version = seenVersion(viewer, page).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (viewer.kind() == Kind.ADMIN) {
			audit(viewer, AuditObject.of("work.page", page.id()));
		}
		return new Seen(page, version);
	}

	/** The Page's versions the viewer sees, newest first. */
	@Transactional(readOnly = true)
	List<Version> versions(BoardViewer viewer, Page page) {
		Instant cutoff = viewer.kind() == Kind.FORMER ? cutoff(viewer) : null;
		return allVersions(page.id()).stream()
			.filter(version -> cutoff == null || !version.at().isAfter(cutoff))
			.sorted((a, b) -> Integer.compare(b.versionNo(), a.versionNo()))
			.toList();
	}

	private Optional<Version> seenVersion(BoardViewer viewer, Page page) {
		return switch (viewer.kind()) {
			case MENTOR, CREW -> version(page.id(), page.versionNo());
			case CUSTOMER -> page.visibility() == Visibility.CUSTOMER ? version(page.id(), page.versionNo()) : Optional.empty();
			case FORMER -> {
				List<Version> versions = allVersions(page.id());
				boolean touched = versions.stream()
					.anyMatch(version -> viewer.windows().stream().anyMatch(window -> window.contains(version.at())));
				Instant cutoff = cutoff(viewer);
				yield touched ? versions.stream().filter(version -> !version.at().isAfter(cutoff))
					.max((a, b) -> Integer.compare(a.versionNo(), b.versionNo())) : Optional.empty();
			}
			case ADMIN -> {
				Set<UUID> authors = new HashSet<>();
				allVersions(page.id()).stream().map(Version::author).filter(Objects::nonNull).forEach(authors::add);
				Instant now = this.clock.instant();
				boolean granted = viewer.grants()
					.stream()
					.anyMatch(grant -> grant.permits(viewer.accountId(), page.jobId(), AdminReadScope.PAGES, page.id(), authors, now));
				yield granted ? version(page.id(), page.versionNo()) : Optional.empty();
			}
		};
	}

	private List<Version> allVersions(UUID pageId) {
		return this.jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM work.page_version WHERE page_id = :page ORDER BY version_no")
			.param("page", pageId)
			.query(Pages::version)
			.list();
	}

	private static Instant cutoff(BoardViewer viewer) {
		return viewer.windows().stream().map(Window::to).max(Instant::compareTo).orElseThrow();
	}

	private void audit(BoardViewer viewer, AuditObject object) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(viewer.accountId()), "work.admin_read", object)
			.forJob(viewer.board().jobId()));
	}

	private static Page page(ResultSet rs, int n) throws SQLException {
		String template = rs.getString("template");
		return new Page(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("parent_id", UUID.class), rs.getString("title"), rs.getInt("version_no"),
				rs.getObject("created_by_account_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant(), template == null ? null : Template.valueOf(template),
				Visibility.valueOf(rs.getString("visibility")));
	}

	private static Version version(ResultSet rs, int n) throws SQLException {
		int from = rs.getInt("restored_from");
		return new Version(rs.getObject("page_id", UUID.class), rs.getInt("version_no"), rs.getString("title"),
				rs.getString("body"), rs.getObject("author_account_id", UUID.class), rs.wasNull() ? null : from,
				rs.getTimestamp("created_at").toInstant());
	}

}
