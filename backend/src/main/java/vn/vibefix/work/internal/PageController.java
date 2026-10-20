package vn.vibefix.work.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.work.internal.Pages.Page;
import vn.vibefix.work.internal.Pages.Seen;
import vn.vibefix.work.internal.Pages.Version;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkTextCheck.Resolution;

/**
 * JOB-3 "Tài liệu" (FR-29, FR-47, AD-20, AD-23, UX-DR39): the Documentation Space of a Job with a Board
 * (keyed by the Job), its two-level Page tree, each Page's current version, its version history, saves
 * made on a version, and restores. The Mentor and the active Crew read and write; a former member and a
 * granted Administrator read what they may; everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class PageController {

	/**
	 * A Page's text and place, sent in full on every save.
	 * @param version the version the save was made on; only for a save of an existing Page
	 * @param sensitive {@code CHECK} first; {@code MASK} after the FR-47 warning
	 */
	record PageRequest(@NotBlank @Size(max = 200) String title, @Nullable @Size(max = 100_000) String body,
			@Nullable UUID parentId, @Nullable Integer version, @Nullable Resolution sensitive) {
	}

	/** "Khôi phục", made on the Page's current {@code version}. */
	record RestoreRequest(@NotNull Integer version) {
	}

	/** One Page in the tree. */
	record PageNodeView(UUID id, @Nullable UUID parentId, String title, int versionNo, Instant updatedAt) {
	}

	/**
	 * The Documentation Space.
	 * @param title the Job's title
	 * @param readOnly the viewer cannot write: not the Mentor or an active member, or the Job is not Active
	 */
	record PageTreeView(UUID boardId, String title, boolean mentor, boolean readOnly, List<PageNodeView> pages) {
	}

	/**
	 * A Page at the version the viewer sees.
	 * @param author who wrote that version; empty when the account is gone
	 */
	record PageView(UUID id, UUID boardId, @Nullable UUID parentId, String title, String body, int versionNo,
			String author, Instant at, @Nullable Integer restoredFrom, boolean readOnly) {
	}

	/** One version in "Lịch sử phiên bản". */
	record PageVersionView(int versionNo, String title, String author, Instant at, @Nullable Integer restoredFrom) {
	}

	/** One earlier version, read-only. */
	record PageVersionDetailView(int versionNo, String title, String body, String author, Instant at,
			@Nullable Integer restoredFrom) {
	}

	private final Pages pages;

	private final WorkAccess access;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final CurrentCaller callers;

	PageController(Pages pages, WorkAccess access, MentorDirectory mentors, StudentDirectory students,
			CurrentCaller callers) {
		this.pages = pages;
		this.access = access;
		this.mentors = mentors;
		this.students = students;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/pages")
	PageTreeView pageTree(@PathVariable UUID boardId) {
		BoardViewer viewer = this.access.reader(boardId, this.callers.get(), AdminReadScope.PAGES);
		List<PageNodeView> nodes = this.pages.visible(viewer)
			.stream()
			.map(seen -> new PageNodeView(seen.page().id(), seen.page().parentId(), seen.version().title(),
					seen.version().versionNo(), seen.version().at()))
			.toList();
		return new PageTreeView(boardId, viewer.title(), viewer.mentor(), !viewer.writes(), nodes);
	}

	@PostMapping("/api/v1/boards/{boardId}/pages")
	@ResponseStatus(HttpStatus.CREATED)
	PageView createPage(@PathVariable UUID boardId, @Valid @RequestBody PageRequest body) {
		BoardViewer writer = this.access.writer(boardId, this.callers.get());
		Page page = this.pages.create(writer, body.title().strip(), body(body), body.parentId(), resolution(body));
		return view(writer, this.pages.seen(writer, page));
	}

	@GetMapping("/api/v1/pages/{pageId}")
	PageView page(@PathVariable UUID pageId) {
		Page page = this.pages.page(pageId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer viewer = this.access.reader(page.jobId(), this.callers.get(), AdminReadScope.PAGES);
		return view(viewer, this.pages.seen(viewer, page));
	}

	/** "Lưu": a new version made on {@code version}; a newer one answers {@code STALE} (AD-23). */
	@PutMapping("/api/v1/pages/{pageId}")
	PageView savePage(@PathVariable UUID pageId, @Valid @RequestBody PageRequest body) {
		Page page = this.pages.page(pageId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer writer = this.access.writer(page.jobId(), this.callers.get());
		if (body.version() == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Page saved = this.pages.save(writer, pageId, body.version(), body.title().strip(), body(body), body.parentId(),
				resolution(body));
		return view(writer, this.pages.seen(writer, saved));
	}

	@GetMapping("/api/v1/pages/{pageId}/versions")
	List<PageVersionView> pageVersions(@PathVariable UUID pageId) {
		Page page = this.pages.page(pageId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer viewer = this.access.reader(page.jobId(), this.callers.get(), AdminReadScope.PAGES);
		this.pages.seen(viewer, page);
		List<Version> versions = this.pages.versions(viewer, page);
		Map<UUID, String> names = names(viewer, versions);
		return versions.stream()
			.map(version -> new PageVersionView(version.versionNo(), version.title(), name(names, version.author()),
					version.at(), version.restoredFrom()))
			.toList();
	}

	@GetMapping("/api/v1/pages/{pageId}/versions/{versionNo}")
	PageVersionDetailView pageVersion(@PathVariable UUID pageId, @PathVariable int versionNo) {
		Page page = this.pages.page(pageId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer viewer = this.access.reader(page.jobId(), this.callers.get(), AdminReadScope.PAGES);
		this.pages.seen(viewer, page);
		Version version = this.pages.versions(viewer, page)
			.stream()
			.filter(found -> found.versionNo() == versionNo)
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Map<UUID, String> names = names(viewer, List.of(version));
		return new PageVersionDetailView(version.versionNo(), version.title(), version.body(),
				name(names, version.author()), version.at(), version.restoredFrom());
	}

	/** "Khôi phục": a new version from version {@code versionNo} (FR-29). */
	@PostMapping("/api/v1/pages/{pageId}/versions/{versionNo}/restore")
	PageView restorePage(@PathVariable UUID pageId, @PathVariable int versionNo, @Valid @RequestBody RestoreRequest body) {
		Page page = this.pages.page(pageId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		BoardViewer writer = this.access.writer(page.jobId(), this.callers.get());
		Page restored = this.pages.restore(writer, pageId, body.version(), versionNo);
		return view(writer, this.pages.seen(writer, restored));
	}

	private PageView view(BoardViewer viewer, Seen seen) {
		Map<UUID, String> names = names(viewer, List.of(seen.version()));
		return new PageView(seen.page().id(), seen.page().jobId(), seen.page().parentId(), seen.version().title(),
				seen.version().body(), seen.version().versionNo(), name(names, seen.version().author()), seen.version().at(),
				seen.version().restoredFrom(), !viewer.writes());
	}

	private Map<UUID, String> names(BoardViewer viewer, List<Version> versions) {
		Map<UUID, String> names = new HashMap<>();
		this.mentors.cards(List.of(viewer.mentorId())).stream().findFirst().map(MentorCard::displayName)
			.ifPresent(name -> names.put(viewer.mentorId(), name));
		Set<UUID> students = new HashSet<>();
		versions.stream().map(Version::author).filter(id -> id != null && !id.equals(viewer.mentorId())).forEach(students::add);
		if (!students.isEmpty()) {
			for (StudentCard card : this.students.cards(List.copyOf(students))) {
				names.put(card.accountId(), card.name() != null ? card.name() : "@" + card.githubLogin());
			}
		}
		return names;
	}

	private static String name(Map<UUID, String> names, @Nullable UUID author) {
		return author == null ? "" : names.getOrDefault(author, "");
	}

	private static String body(PageRequest body) {
		return body.body() == null ? "" : body.body();
	}

	private static Resolution resolution(PageRequest body) {
		return body.sensitive() == null ? Resolution.CHECK : body.sensitive();
	}

}
