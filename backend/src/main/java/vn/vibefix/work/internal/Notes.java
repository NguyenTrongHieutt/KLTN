package vn.vibefix.work.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkTextCheck.MatchView;
import vn.vibefix.work.internal.WorkTextCheck.Resolution;

/**
 * Work Item notes (FR-47, FR-76, AD-39): each passes the FR-47 check, then is appended to the Work
 * Item's history with its author and time. Nothing edits or deletes a note; "Sửa lại" adds a new one
 * that quotes one of the author's earlier notes on the same Work Item.
 */
@Component
class Notes {

	static final int MAX_LENGTH = 4000;

	private final Boards boards;

	private final JdbcClient jdbc;

	private final WorkTextCheck check;

	Notes(Boards boards, JdbcClient jdbc, WorkTextCheck check) {
		this.boards = boards;
		this.jdbc = jdbc;
		this.check = check;
	}

	/**
	 * Adds a note, or a correction quoting {@code quotes}.
	 * @throws ApiException {@code NOT_FOUND}; {@code VALIDATION NOTE_EMPTY} or {@code QUOTE_UNKNOWN};
	 * {@code FORBIDDEN NOT_AUTHOR} for someone else's note; {@code GUARD_FAILED SENSITIVE_CONTENT} under
	 * {@code CHECK}
	 */
	@Transactional
	void add(BoardViewer writer, UUID itemId, String text, @Nullable UUID quotes, Resolution resolution) {
		this.boards.item(itemId)
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		String note = text.strip();
		if (note.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.NOTE_EMPTY);
		}
		if (note.length() > MAX_LENGTH) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		if (quotes != null) {
			UUID author = this.jdbc.sql("""
					SELECT actor_account_id FROM work.work_item_history WHERE id = :id AND work_item_id = :item AND action = 'NOTE'
					""")
				.param("id", quotes)
				.param("item", itemId)
				.query((rs, n) -> rs.getObject("actor_account_id", UUID.class))
				.optional()
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION, WorkReason.QUOTE_UNKNOWN));
			if (!writer.accountId().equals(author)) {
				throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.NOT_AUTHOR);
			}
		}
		List<MatchView> matches = new ArrayList<>();
		String masked = this.check.masked(note, matches);
		WorkTextCheck.requireResolved(matches, resolution);
		this.boards.note(itemId, writer.board().jobId(), writer.accountId(), masked, quotes);
	}

}
