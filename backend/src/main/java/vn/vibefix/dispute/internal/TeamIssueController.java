package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.dispute.internal.TeamIssues.JobPanel;
import vn.vibefix.dispute.internal.TeamIssues.Subject;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * The Team Issue panel (FR-79, AD-40, UX-DR63): a Student reads, on STU-4, what they may raise on each Job
 * where they were ever active, and raises it; JOB-1 lists the Job's issues for its Mentor, and only their
 * own for such a Crew member. The Customer and everyone else find nothing (AD-20).
 */
@RestController
@Access(Gate.SIGNED_IN)
class TeamIssueController {

	/**
	 * One Team Issue.
	 * @param mine raised by the reader
	 * @param raiserName shown to the Mentor; {@code null} once the raiser was erased
	 */
	record TeamIssueView(UUID id, UUID jobId, String subject, @Nullable UUID milestoneId, @Nullable Integer milestoneNumber,
			@Nullable UUID ratingId, @Nullable String raiserName, boolean mine, String reason, String phase, Instant raisedAt,
			Instant answerDueAt, @Nullable Instant escalationDueAt, @Nullable Instant endedAt) {
	}

	/** @param refusal why the option is disabled: {@code TEAM_ISSUE_ALREADY_RAISED} or {@code TEAM_ISSUE_OPEN} */
	record TeamIssueShareOptionView(UUID milestoneId, int number, String title, @Nullable String refusal,
			@Nullable Instant endedAt) {
	}

	/** @param closesAt 14 days after the Rating became visible */
	record TeamIssueRatingOptionView(UUID ratingId, int stars, Instant visibleAt, Instant closesAt, @Nullable String refusal,
			@Nullable Instant endedAt) {
	}

	record TeamIssuePanelView(UUID jobId, String title, String mentorName, List<TeamIssueShareOptionView> shares,
			List<TeamIssueRatingOptionView> ratings, List<TeamIssueView> issues) {
	}

	record JobTeamIssuesView(UUID jobId, boolean mentor, List<TeamIssueView> issues) {
	}

	/**
	 * "Gửi khiếu nại".
	 * @param milestoneId for a {@code SHARE} issue
	 * @param ratingId for a {@code RATING} issue
	 */
	record RaiseTeamIssueRequest(@NotNull UUID jobId, @NotNull Subject subject, @Nullable UUID milestoneId,
			@Nullable UUID ratingId, @NotBlank @Size(max = 2000) String reason) {
	}

	private final TeamIssues issues;

	private final TeamIssueLocks locks;

	private final JobPosts posts;

	private final StudentDirectory students;

	private final MentorGate gate;

	private final CurrentCaller callers;

	TeamIssueController(TeamIssues issues, TeamIssueLocks locks, JobPosts posts, StudentDirectory students, MentorGate gate,
			CurrentCaller callers) {
		this.issues = issues;
		this.locks = locks;
		this.posts = posts;
		this.students = students;
		this.gate = gate;
		this.callers = callers;
	}

	/** STU-4 "Khiếu nại nội bộ". */
	@GetMapping("/api/v1/team-issues")
	List<TeamIssuePanelView> panel() {
		UUID student = student();
		return this.issues.panel(student).stream().map(panel -> panel(panel, student)).toList();
	}

	@PostMapping("/api/v1/team-issues")
	@ResponseStatus(HttpStatus.CREATED)
	TeamIssueView raise(@Valid @RequestBody RaiseTeamIssueRequest body) {
		UUID student = student();
		UUID target = body.subject() == Subject.SHARE ? body.milestoneId() : body.ratingId();
		if (target == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		this.gate.requireActiveAccount(student);
		TeamIssue issue = this.issues.raise(student, body.jobId(), body.subject(), target, body.reason());
		return view(issue, student, Map.of());
	}

	/** JOB-1: every issue of the Job for its Mentor; a Crew member who was ever active reads their own. */
	@GetMapping("/api/v1/boards/{boardId}/team-issues")
	JobTeamIssuesView ofJob(@PathVariable("boardId") UUID jobId) {
		Caller caller = this.callers.get();
		UUID account = caller.accountId();
		JobPostState job = this.posts.post(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (account != null && caller.hasRole(Role.MENTOR) && account.equals(job.mentorId())) {
			List<TeamIssue> all = this.issues.ofJob(jobId);
			Map<UUID, String> names = new HashMap<>();
			this.students.cards(all.stream().map(TeamIssue::raiserId).filter(Objects::nonNull).distinct().toList())
				.forEach(card -> names.put(card.accountId(), card.name() != null ? card.name() : "@" + card.githubLogin()));
			return new JobTeamIssuesView(jobId, true, all.stream().map(issue -> view(issue, account, names)).toList());
		}
		if (account != null && caller.hasRole(Role.STUDENT) && this.locks.crewJobsOf(account).contains(jobId)) {
			return new JobTeamIssuesView(jobId, false, this.issues.ofJob(jobId)
				.stream()
				.filter(issue -> account.equals(issue.raiserId()))
				.map(issue -> view(issue, account, Map.of()))
				.toList());
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	private UUID student() {
		Caller caller = this.callers.get();
		UUID account = caller.accountId();
		if (account == null || !caller.hasRole(Role.STUDENT)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return account;
	}

	private TeamIssuePanelView panel(JobPanel panel, UUID reader) {
		return new TeamIssuePanelView(panel.jobId(), panel.title(), panel.mentorName(),
				panel.shares()
					.stream()
					.map(share -> new TeamIssueShareOptionView(share.milestoneId(), share.number(), share.title(),
							share.refusal() == null ? null : share.refusal().name(), share.endedAt()))
					.toList(),
				panel.ratings()
					.stream()
					.map(rating -> new TeamIssueRatingOptionView(rating.ratingId(), rating.stars(), rating.visibleAt(),
							rating.closesAt(), rating.refusal() == null ? null : rating.refusal().name(), rating.endedAt()))
					.toList(),
				panel.issues().stream().map(issue -> view(issue, reader, Map.of())).toList());
	}

	private static TeamIssueView view(TeamIssue issue, UUID reader, Map<UUID, String> names) {
		return new TeamIssueView(issue.id(), issue.jobId(), issue.subject().name(), issue.milestoneId(),
				issue.milestoneNumber(), issue.ratingId(), issue.raiserId() == null ? null : names.get(issue.raiserId()),
				reader.equals(issue.raiserId()), issue.reason(), issue.phase().name(), issue.raisedAt(), issue.answerDueAt(),
				issue.escalationDueAt(), issue.endedAt());
	}

}
