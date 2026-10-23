package vn.vibefix.work.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewRelationship;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.shared.AdminReadGrant;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;
import vn.vibefix.work.internal.Boards.Board;

/**
 * Who reads and writes a Board (FR-23, FR-28, AD-20, AD-39, AD-41), read from {@code job}:
 * <ul>
 * <li>the Job's Mentor ({@code job.mentorOf}) and its {@code ACTIVE} Crew members
 * ({@code job.crewRelationship}) read everything and write while the Job is Active;</li>
 * <li>an {@code INACTIVE} or {@code FORMER} member reads, read-only, only what their windows allow
 * ({@link WorkReads});</li>
 * <li>an Administrator reads only what an open {@code WORK_ITEMS} grant covers, each read audited.</li>
 * </ul>
 * The Customer, an {@code ASSIGNED} Student, and everyone else find nothing. A Documentation Space's
 * customer-visible Pages are also read by the Job's Customer and, once that Customer shares the Job with a
 * new Job, by the new Job's Mentor and {@code ACTIVE} Crew until it ends (FR-70).
 */
@Component
class WorkAccess {

	enum Kind {
		MENTOR, CREW, FORMER, ADMIN,

		/** The Job's Customer: only customer-visible Pages, never the Board (FR-31, AD-39). */
		CUSTOMER,

		/**
		 * The Mentor or an {@code ACTIVE} Crew member of a new Job this Job was shared with: as the Customer,
		 * only customer-visible Pages at their current version (FR-70).
		 */
		SHARED
	}

	/** The new Job's states in which a share no longer opens anything (FR-70). */
	static final Set<String> ENDED = Set.of("CLOSED", "CANCELLED");

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

		/** An Assessment Job (FR-10), whose Documentation Space offers the Assessment Report. */
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

	private final JdbcClient jdbc;

	private final Clock clock;

	WorkAccess(Boards boards, JobPosts posts, Crews crews, WorkGrants grants, JdbcClient jdbc, Clock clock) {
		this.boards = boards;
		this.posts = posts;
		this.crews = crews;
		this.grants = grants;
		this.jdbc = jdbc;
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
		boolean mentorRole = caller.hasRole(Role.MENTOR);
		boolean admin = caller.hasRole(Role.ADMINISTRATOR);
		if (!mentorRole && !admin && !caller.hasRole(Role.STUDENT)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID account = caller.requireAccountId();
		Board board = this.boards.board(boardId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		JobPostState post = this.posts.post(board.jobId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
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
		if (mentorRole) {
			if (!account.equals(post.mentorId())) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			return viewer(board, post, account, Kind.MENTOR, List.of(), List.of());
		}
		CrewRelationship relationship = this.crews.crewRelationship(board.jobId(), account);
		return switch (relationship.kind()) {
			case ACTIVE -> viewer(board, post, account, Kind.CREW, List.of(), List.of());
			case INACTIVE, FORMER -> {
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
	 * A reader of the Documentation Space: as {@link #reader} with a {@code PAGES} grant, and also the
	 * Job's Customer, who sees only customer-visible Pages (FR-31), and the assignees of a new Job it was
	 * shared with (FR-70). A caller who reads the Job anyway keeps that access.
	 */
	@Transactional(readOnly = true)
	BoardViewer pageReader(UUID boardId, Caller caller) {
		if (!caller.hasRole(Role.CUSTOMER)) {
			try {
				return reader(boardId, caller, AdminReadScope.PAGES);
			}
			catch (ApiException notReader) {
				// reader runs on this bean, not through its proxy, so nothing is marked for rollback
				return sharedReader(boardId, caller).orElseThrow(() -> notReader);
			}
		}
		UUID account = caller.requireAccountId();
		Board board = this.boards.board(boardId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		JobPostState post = this.posts.post(board.jobId())
			.filter(found -> found.customerId().equals(account) && found.mentorId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return viewer(board, post, account, Kind.CUSTOMER, List.of(), List.of());
	}

	/**
	 * The earlier Jobs shared with this Job, each as its shared reader sees it, oldest share first: only for
	 * the Job's Mentor or an {@code ACTIVE} Crew member, read now (AD-41); empty once the Job is Closed or
	 * Cancelled (FR-70).
	 */
	@Transactional(readOnly = true)
	List<BoardViewer> sharedReaders(UUID jobId, Caller caller) {
		UUID account = caller.requireAccountId();
		JobPostState post = this.posts.post(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!assignee(post, caller, account)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (ENDED.contains(post.state())) {
			return List.of();
		}
		return this.jdbc.sql("SELECT source_job_id FROM work.page_share WHERE job_id = :job ORDER BY created_at, source_job_id")
			.param("job", jobId)
			.query(UUID.class)
			.list()
			.stream()
			.flatMap(source -> sharedViewer(source, account).stream())
			.toList();
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

	/** A reader of the Job's customer-visible Pages through a share to a running Job they are an assignee of. */
	private Optional<BoardViewer> sharedReader(UUID sourceJobId, Caller caller) {
		if (!caller.hasRole(Role.MENTOR) && !caller.hasRole(Role.STUDENT)) {
			return Optional.empty();
		}
		UUID account = caller.requireAccountId();
		boolean shared = this.jdbc.sql("SELECT job_id FROM work.page_share WHERE source_job_id = :source")
			.param("source", sourceJobId)
			.query(UUID.class)
			.list()
			.stream()
			.flatMap(job -> this.posts.post(job).stream())
			.anyMatch(post -> !ENDED.contains(post.state()) && assignee(post, caller, account));
		return shared ? sharedViewer(sourceJobId, account) : Optional.empty();
	}

	private Optional<BoardViewer> sharedViewer(UUID sourceJobId, UUID account) {
		Optional<Board> board = this.boards.board(sourceJobId);
		Optional<JobPostState> post = this.posts.post(sourceJobId);
		return board.isEmpty() || post.isEmpty() ? Optional.empty()
				: Optional.of(viewer(board.get(), post.get(), account, Kind.SHARED, List.of(), List.of()));
	}

	/** The Job's Mentor, or one of its {@code ACTIVE} Crew members, read through {@code job} now (AD-41). */
	private boolean assignee(JobPostState post, Caller caller, UUID account) {
		if (post.mentorId() == null) {
			return false;
		}
		if (caller.hasRole(Role.MENTOR)) {
			return account.equals(post.mentorId());
		}
		return caller.hasRole(Role.STUDENT)
				&& this.crews.crewRelationship(post.jobId(), account).kind() == Relationship.ACTIVE;
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
