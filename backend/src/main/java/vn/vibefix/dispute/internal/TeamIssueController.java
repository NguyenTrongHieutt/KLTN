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
import vn.vibefix.dispute.internal.TeamIssues.Answer;
import vn.vibefix.dispute.internal.TeamIssues.JobPanel;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * The Team Issue panel (FR-79, AD-40, UX-DR63): a Student reads, on STU-4, the Ratings they may contest on each Job
 * where they were ever active, and raises an issue about one; JOB-1 lists the Job's issues for its Mentor, and only
 * their own for such a Crew member. The Mentor answers on JOB-1; the raiser accepts the answer or asks an
 * Administrator to look on STU-4. Every check on a Job reads the caller's role in it (AD-43); STU-4's panel is open to
 * any unlocked user and lists only the Jobs where the caller was an active Crew member (Story 20.3). The Customer and
 * everyone else find nothing (AD-20).
 */
@RestController
@Access(Gate.SIGNED_IN)
class TeamIssueController {

	/**
	 * One Team Issue.
	 * @param mine raised by the reader
	 * @param raiserName shown to the Mentor; {@code null} once the raiser was erased
	 * @param answerKind {@code KEEP} or {@code WITHDRAW_RATING} once answered
	 * @param ending {@code ACCEPTED}, {@code LAPSED}, {@code DECIDED}, or {@code MOOT} once ended
	 */
	record TeamIssueView(UUID id, UUID jobId, UUID ratingId, @Nullable String raiserName, boolean mine, String reason,
			String phase, Instant raisedAt, Instant answerDueAt, @Nullable Instant escalationDueAt, @Nullable Instant endedAt,
			@Nullable String answerKind, @Nullable Instant answeredAt, @Nullable Instant escalatedAt, @Nullable String ending,
			@Nullable Boolean outcomeRatingRemoved) {
	}

	/**
	 * @param closesAt 14 days after the Rating became visible
	 * @param refusal why the option is disabled: {@code TEAM_ISSUE_ALREADY_RAISED} or {@code TEAM_ISSUE_OPEN}
	 */
	record TeamIssueRatingOptionView(UUID ratingId, int stars, Instant visibleAt, Instant closesAt, @Nullable String refusal,
			@Nullable Instant endedAt) {
	}

	record TeamIssuePanelView(UUID jobId, String title, String mentorName, List<TeamIssueRatingOptionView> ratings,
			List<TeamIssueView> issues) {
	}

	record JobTeamIssuesView(UUID jobId, boolean mentor, List<TeamIssueView> issues) {
	}

	/** "Gửi khiếu nại" about the Rating {@code ratingId}. */
	record RaiseTeamIssueRequest(@NotNull UUID jobId, @NotNull UUID ratingId, @NotBlank @Size(max = 2000) String reason) {
	}

	/** "Gửi câu trả lời". */
	record AnswerTeamIssueRequest(@NotNull Answer kind) {
	}

	private final TeamIssues issues;

	private final TeamIssueAnswers answers;

	private final TeamIssueLocks locks;

	private final JobRoles roles;

	private final PersonDirectory people;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	TeamIssueController(TeamIssues issues, TeamIssueAnswers answers, TeamIssueLocks locks, JobRoles roles,
			PersonDirectory people, ActiveAccount gate, CurrentCaller callers) {
		this.issues = issues;
		this.answers = answers;
		this.locks = locks;
		this.roles = roles;
		this.people = people;
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
		UUID student = member(body.jobId());
		this.gate.requireActiveAccount(student);
		TeamIssue issue = this.issues.raise(student, body.jobId(), body.ratingId(), body.reason());
		return view(issue, student, Map.of());
	}

	/** "Chấp nhận" on STU-4. */
	@PostMapping("/api/v1/team-issues/{teamIssueId}/accept")
	TeamIssueView accept(@PathVariable("teamIssueId") UUID issueId) {
		UUID student = raiser(issueId);
		this.gate.requireActiveAccount(student);
		return view(this.answers.accept(student, issueId), student, Map.of());
	}

	/** "Nhờ quản trị viên xem xét" on STU-4. */
	@PostMapping("/api/v1/team-issues/{teamIssueId}/escalate")
	TeamIssueView escalate(@PathVariable("teamIssueId") UUID issueId) {
		UUID student = raiser(issueId);
		this.gate.requireActiveAccount(student);
		return view(this.answers.escalate(student, issueId), student, Map.of());
	}

	/** The Mentor's one answer on JOB-1. */
	@PostMapping("/api/v1/boards/{boardId}/team-issues/{teamIssueId}/answer")
	TeamIssueView answer(@PathVariable("boardId") UUID jobId, @PathVariable("teamIssueId") UUID issueId,
			@Valid @RequestBody AnswerTeamIssueRequest body) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isMentor()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		TeamIssue issue = this.answers.answer(mentor, jobId, issueId, body.kind());
		return view(issue, mentor, names(List.of(issue)));
	}

	/** JOB-1: every issue of the Job for its Mentor; a Crew member who was ever active reads their own. */
	@GetMapping("/api/v1/boards/{boardId}/team-issues")
	JobTeamIssuesView ofJob(@PathVariable("boardId") UUID jobId) {
		Caller caller = this.callers.get();
		UUID account = caller.accountId();
		JobRole role = this.roles.roleOf(jobId, caller);
		if (account != null && role.isMentor()) {
			List<TeamIssue> all = this.issues.ofJob(jobId);
			Map<UUID, String> names = names(all);
			return new JobTeamIssuesView(jobId, true, all.stream().map(issue -> view(issue, account, names)).toList());
		}
		if (account != null && role.isCrew() && this.locks.crewJobsOf(account).contains(jobId)) {
			return new JobTeamIssuesView(jobId, false, this.issues.ofJob(jobId)
				.stream()
				.filter(issue -> account.equals(issue.raiserId()))
				.map(issue -> view(issue, account, Map.of()))
				.toList());
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	/**
	 * A user on STU-4, outside any Job, unlocked (AD-19, read on every request); the panel holds only the caller's
	 * own Crew Jobs. An Administrator finds nothing.
	 */
	private UUID student() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID account = caller.requireAccountId();
		this.gate.requireActiveAccount(account);
		return account;
	}

	/** A Crew member of the Job, by their role in it (AD-43); anyone else finds nothing. */
	private UUID member(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isCrew()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

	/**
	 * A Crew member of the issue's Job, by their role in it (AD-43), for the raiser's own commands; the command
	 * then finds nothing unless the caller raised the issue.
	 */
	private UUID raiser(UUID issueId) {
		return member(this.issues.find(issueId)
			.map(TeamIssue::jobId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	private TeamIssuePanelView panel(JobPanel panel, UUID reader) {
		return new TeamIssuePanelView(panel.jobId(), panel.title(), panel.mentorName(),
				panel.ratings()
					.stream()
					.map(rating -> new TeamIssueRatingOptionView(rating.ratingId(), rating.stars(), rating.visibleAt(),
							rating.closesAt(), rating.refusal() == null ? null : rating.refusal().name(), rating.endedAt()))
					.toList(),
				panel.issues().stream().map(issue -> view(issue, reader, Map.of())).toList());
	}

	private Map<UUID, String> names(List<TeamIssue> issues) {
		Map<UUID, String> names = new HashMap<>();
		this.people.cards(issues.stream().map(TeamIssue::raiserId).filter(Objects::nonNull).distinct().toList())
			.forEach(card -> names.put(card.accountId(), card.shownName()));
		return names;
	}

	private static TeamIssueView view(TeamIssue issue, UUID reader, Map<UUID, String> names) {
		return new TeamIssueView(issue.id(), issue.jobId(), issue.ratingId(),
				issue.raiserId() == null ? null : names.get(issue.raiserId()), reader.equals(issue.raiserId()), issue.reason(),
				issue.phase().name(), issue.raisedAt(), issue.answerDueAt(), issue.escalationDueAt(), issue.endedAt(),
				issue.answerKind() == null ? null : issue.answerKind().name(), issue.answeredAt(), issue.escalatedAt(),
				issue.ending() == null ? null : issue.ending().name(), issue.outcomeRatingRemoved());
	}

}
