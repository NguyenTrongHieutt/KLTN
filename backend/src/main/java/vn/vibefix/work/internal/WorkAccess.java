package vn.vibefix.work.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;
import vn.vibefix.work.internal.Boards.Board;

/**
 * Who reads a Board (FR-28, AD-20, AD-39, AD-41), read from {@code job}: the Job's Mentor
 * ({@code job.mentorOf}) and its {@code ACTIVE} Crew members ({@code job.crewRelationship}). The
 * Customer, an {@code ASSIGNED} Student, everyone else, and an Administrator find nothing.
 */
@Component
class WorkAccess {

	/** The caller and the Board they reached, with its Job's title. */
	record BoardViewer(Board board, String title, UUID accountId, boolean mentor) {
	}

	private final Boards boards;

	private final JobPosts posts;

	private final Crews crews;

	WorkAccess(Boards boards, JobPosts posts, Crews crews) {
		this.boards = boards;
		this.posts = posts;
		this.crews = crews;
	}

	@Transactional(readOnly = true)
	BoardViewer reader(UUID boardId, Caller caller) {
		boolean mentorRole = caller.hasRole(Role.MENTOR);
		if (!mentorRole && !caller.hasRole(Role.STUDENT)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID account = caller.requireAccountId();
		Board board = this.boards.board(boardId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		JobPostState post = this.posts.post(board.jobId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (mentorRole && account.equals(post.mentorId())) {
			return new BoardViewer(board, post.title(), account, true);
		}
		if (!mentorRole && this.crews.crewRelationship(board.jobId(), account).kind() == Relationship.ACTIVE) {
			return new BoardViewer(board, post.title(), account, false);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

}
