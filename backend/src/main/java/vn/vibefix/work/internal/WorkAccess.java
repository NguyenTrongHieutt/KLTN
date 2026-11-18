package vn.vibefix.work.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.shared.AdminReadGrant;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.work.internal.Boards.Board;

/**
 * Who reads and writes a Board (FR-23, FR-28, AD-20, AD-39, AD-41), by the role in the Job that
 * {@code job.roleOf} reads (AD-43):
 * <ul>
 * <li>the Job's Mentor and its {@code ACTIVE} Crew members read everything and write while the Job is Active;</li>
 * <li>a {@code FORMER} member, a locked one included, reads, read-only, only what their windows allow
 * ({@link WorkReads});</li>
 * <li>an Administrator reads only what an open {@code WORK_ITEMS} grant covers, each read audited.</li>
 * </ul>
 * An {@code ASSIGNED} Student and everyone else find nothing. The Job's Customer reaches no Work Item
 * either (AD-39): they reach only JOB-1's progress, through {@link #progressReader}.
 */
@Component
class WorkAccess {

	enum Kind {
		MENTOR, CREW, FORMER, ADMIN,

		/** The Job's Customer: JOB-1's progress only, never the Board (AD-39). */
		CUSTOMER
	}

	/** A span of a former member's time on the Crew, both ends included. */
	record Window(Instant from, Instant to) {

		boolean contains(Instant at) {
			return !at.isBefore(this.from) && !at.isAfter(this.to);
		}

	}

	/**
	 * The caller and the Board they reached.
	 * @param title the Job's title
	 * @param jobState the Job's state name
	 * @param windows a former member's windows; empty for everyone else
	 * @param grants an Administrator's open grants on the Job; empty for everyone else
	 */
	record BoardViewer(Board board, String title, String jobState, List<String> jobTypes, UUID mentorId, UUID accountId,
			Kind kind, List<Window> windows, List<AdminReadGrant> grants) {

		BoardViewer {
			jobTypes = List.copyOf(jobTypes);
			windows = List.copyOf(windows);
			grants = List.copyOf(grants);
		}

		/** An Assessment Job (FR-10), which JOB-1 tells by showing N-12 and the team's read-only copy link. */
		boolean assessment() {
			return this.jobTypes.contains("ASSESSMENT");
		}

		boolean mentor() {
			return this.kind == Kind.MENTOR;
		}

		/** Whether the caller may write: the Mentor or an active member, while the Job is Active. */
		boolean writes() {
			return (this.kind == Kind.MENTOR || this.kind == Kind.CREW) && "ACTIVE".equals(this.jobState);
		}

	}

	private final Boards boards;

	private final JobPosts posts;

	private final Crews crews;

	private final WorkGrants grants;

	private final Clock clock;

	private final JobRoles roles;

	WorkAccess(Boards boards, JobPosts posts, Crews crews, WorkGrants grants, Clock clock, JobRoles roles) {
		this.roles = roles;
		this.boards = boards;
		this.posts = posts;
		this.crews = crews;
		this.grants = grants;
		this.clock = clock;
	}

	/** A reader of the Board's Work Items; an Administrator needs a {@code WORK_ITEMS} grant. */
	@Transactional(readOnly = true)
	BoardViewer reader(UUID boardId, Caller caller) {
		return reader(boardId, caller, AdminReadScope.WORK_ITEMS);
	}

	/** A reader of the Board's content of {@code scope}; an Administrator needs a grant with an item of it. */
	@Transactional(readOnly = true)
	BoardViewer reader(UUID boardId, Caller caller, AdminReadScope scope) {
		boolean admin = caller.isAdministrator();
		if (caller.isAnonymous()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID account = caller.requireAccountId();
		Board board = this.boards.board(boardId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// A granted Administrator also reads the bare row a Product's deletion leaves while a split is open (AD-24, AD-40).
		JobPostState post = (admin ? this.posts.postUnderGrant(board.jobId()) : this.posts.post(board.jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (post.mentorId() == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (admin) {
			Instant now = this.clock.instant();
			List<AdminReadGrant> open = this.grants.grants(account, board.jobId())
				.stream()
				.filter(grant -> grant.endsAt() == null || now.isBefore(grant.endsAt()))
				.filter(grant -> grant.items().stream().anyMatch(item -> item.scope() == scope))
				.toList();
			if (open.isEmpty()) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			return viewer(board, post, account, Kind.ADMIN, List.of(), open);
		}
		JobRole role = this.roles.roleOf(board.jobId(), caller);
		if (role.isMentor()) {
			return viewer(board, post, account, Kind.MENTOR, List.of(), List.of());
		}
		return switch (role.crewKind()) {
			case ACTIVE -> viewer(board, post, account, Kind.CREW, List.of(), List.of());
			case FORMER -> {
				List<Window> windows = windows(board.jobId(), account);
				if (windows.isEmpty()) {
					throw new ApiException(ErrorCode.NOT_FOUND);
				}
				yield viewer(board, post, account, Kind.FORMER, windows, List.of());
			}
			default -> throw new ApiException(ErrorCode.NOT_FOUND);
		};
	}

	/**
	 * A reader of JOB-1's progress (FR-28, AD-39): as {@link #reader}, and also the Job's Customer, who
	 * reads the Milestones but no Work Item. It reads no Page: the Documentation Space is retired
	 * (Story 24.1), so a Customer never reaches Board content through it.
	 */
	@Transactional(readOnly = true)
	BoardViewer progressReader(UUID boardId, Caller caller) {
		Board board = this.boards.board(boardId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!this.roles.roleOf(board.jobId(), caller).isCustomer()) {
			return reader(boardId, caller);
		}
		UUID account = caller.requireAccountId();
		JobPostState post = this.posts.post(board.jobId())
			.filter(found -> found.mentorId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return viewer(board, post, account, Kind.CUSTOMER, List.of(), List.of());
	}

	/** A caller who may write the Board now. */
	@Transactional(readOnly = true)
	BoardViewer writer(UUID boardId, Caller caller) {
		BoardViewer viewer = reader(boardId, caller);
		if (viewer.kind() != Kind.MENTOR && viewer.kind() != Kind.CREW) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!viewer.writes()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.BOARD_READ_ONLY);
		}
		return viewer;
	}

	private BoardViewer viewer(Board board, JobPostState post, UUID account, Kind kind, List<Window> windows,
			List<AdminReadGrant> grants) {
		return new BoardViewer(board, post.title(), post.state(), post.jobTypes(), post.mentorId(), account, kind, windows,
				grants);
	}

	/** Each of the Student's rows on the Job that was ever {@code ACTIVE}, when both bounds are known. */
	private List<Window> windows(UUID jobId, UUID student) {
		return this.crews.crewOf(jobId, Selection.EVER_ACTIVE)
			.stream()
			.filter(member -> member.studentId().equals(student) && member.signedAt() != null && member.windowEnd() != null)
			.map(member -> new Window(member.signedAt(), member.windowEnd()))
			.toList();
	}

}
