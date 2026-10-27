package vn.vibefix.work.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.Pages.Page;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;

/**
 * FR-69: a Page is reportable by whoever the Documentation Space shows it to, and a Work Item note by whoever
 * the Board shows it to (a former member only within their window). The queue reads the Page's title or the
 * Work Item's key and title, never the note's text.
 */
@Component
class WorkReportable implements ReportableContent {

	private final JdbcClient jdbc;

	private final WorkAccess access;

	private final Pages pages;

	private final Boards boards;

	private final WorkReads reads;

	WorkReportable(JdbcClient jdbc, WorkAccess access, Pages pages, Boards boards, WorkReads reads) {
		this.jdbc = jdbc;
		this.access = access;
		this.pages = pages;
		this.boards = boards;
		this.reads = reads;
	}

	@Override
	public Set<String> kinds() {
		return Set.of("PAGE", "WORK_ITEM_NOTE");
	}

	/** Runs outside any transaction: a refused read must not mark the caller's transaction rollback-only. */
	@Override
	public Optional<Reported> reportable(String kind, UUID id, Caller caller) {
		if (caller.isAdministrator()) {
			return Optional.empty();
		}
		try {
			return switch (kind) {
				case "PAGE" -> page(id, caller);
				case "WORK_ITEM_NOTE" -> note(id, caller);
				default -> Optional.empty();
			};
		}
		catch (ApiException unseen) {
			return Optional.empty();
		}
	}

	private Optional<Reported> page(UUID pageId, Caller caller) {
		Page page = this.pages.page(pageId).orElse(null);
		if (page == null) {
			return Optional.empty();
		}
		BoardViewer viewer = this.access.pageReader(page.jobId(), caller);
		Pages.Seen seen = this.pages.seen(viewer, page);
		return Optional.of(new Reported(page.jobId(), viewer.board().productId(), page.createdBy(), seen.version().title()));
	}

	private Optional<Reported> note(UUID noteId, Caller caller) {
		Note note = this.jdbc.sql("""
				SELECT work_item_id, job_id, actor_account_id FROM work.work_item_history WHERE id = :id AND action = 'NOTE'
				""")
			.param("id", noteId)
			.query((rs, n) -> new Note(rs.getObject("work_item_id", UUID.class), rs.getObject("job_id", UUID.class),
					rs.getObject("actor_account_id", UUID.class)))
			.optional()
			.orElse(null);
		if (note == null) {
			return Optional.empty();
		}
		BoardViewer viewer = this.access.reader(note.jobId(), caller, AdminReadScope.WORK_ITEM_NOTES);
		Item item = this.reads.item(viewer, this.boards.item(note.itemId()).orElseThrow());
		boolean seen = this.reads.historyOf(viewer, item.id()).stream().anyMatch(entry -> entry.id().equals(noteId));
		return seen ? Optional.of(new Reported(item.jobId(), viewer.board().productId(), note.actor(), item.key() + " " + item.title()))
				: Optional.empty();
	}

	private record Note(UUID itemId, UUID jobId, @Nullable UUID actor) {
	}

}
