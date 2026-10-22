package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.vibefix.dispute.internal.TeamIssueAnswers.Split;
import vn.vibefix.dispute.internal.TeamIssueController.TeamIssueSplitView;
import vn.vibefix.dispute.internal.TeamIssueDecisions.Decision;
import vn.vibefix.dispute.internal.TeamIssueDecisions.Recorded;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.dispute.internal.TeamIssues.Subject;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.job.JobPosts;
import vn.vibefix.reputation.CrewRatings;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * ADM-4's "Khiếu nại nội bộ" tab (FR-63, FR-79, AD-20, AD-40, UX-DR84): the escalated Team Issues, the
 * undecided ones first; one with "Nhận xử lý", the split and the Rating its assignee decides from, and the
 * decision form under the step-up. The Crew Chat, the raiser's Work Item histories, and the Crew Terms
 * versions are read from their own modules under the assignee's grant.
 */
@RestController
@RequestMapping("/api/v1/admin/team-issues")
@Access(Gate.ADMINISTRATOR)
class AdminTeamIssueController {

	/** @param phase {@code ESCALATED} until decided, then {@code ENDED} */
	record AdminTeamIssueRow(UUID id, UUID jobId, String jobTitle, String subject, @Nullable Integer milestoneNumber,
			String phase, @Nullable String ending, Instant escalatedAt, boolean assigned, boolean assignedToMe) {
	}

	/** The Team Evidence Package's Rating, for the assignee only; every view is audited. */
	record AdminRatingEvidenceView(int stars, String comment, Instant createdAt, @Nullable Instant hiddenAt) {
	}

	record AdminTeamIssueDecisionView(@Nullable Integer points, @Nullable Boolean ratingRemoved, String reasoning,
			Instant decidedAt) {
	}

	record AdminTeamIssueDetail(UUID id, UUID jobId, String jobTitle, String subject, @Nullable Integer milestoneNumber,
			@Nullable String raiserName, @Nullable String mentorName, String reason, String phase, Instant raisedAt,
			Instant escalatedAt, @Nullable String answerKind, @Nullable Integer answerPoints, boolean assigned,
			boolean assignedToMe, @Nullable TeamIssueSplitView split, @Nullable AdminRatingEvidenceView rating,
			@Nullable AdminTeamIssueDecisionView decision, @Nullable String ending, @Nullable Instant endedAt) {
	}

	/**
	 * The decision, after the step-up.
	 * @param points whole percentage points from the Mentor's pinned share to the raiser's, for a share issue
	 * @param removeRating for a Rating issue
	 */
	record TeamIssueDecisionRequest(@Nullable @Min(0) @Max(100) Integer points, @Nullable Boolean removeRating,
			@NotBlank @Size(max = 4000) String reasoning, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	private final TeamIssues issues;

	private final TeamIssueDecisions decisions;

	private final TeamIssueAnswers answers;

	private final CrewRatings ratings;

	private final JobPosts posts;

	private final StudentDirectory students;

	private final MentorDirectory mentors;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	AdminTeamIssueController(TeamIssues issues, TeamIssueDecisions decisions, TeamIssueAnswers answers, CrewRatings ratings,
			JobPosts posts, StudentDirectory students, MentorDirectory mentors, JdbcClient jdbc, CurrentCaller callers) {
		this.issues = issues;
		this.decisions = decisions;
		this.answers = answers;
		this.ratings = ratings;
		this.posts = posts;
		this.students = students;
		this.mentors = mentors;
		this.jdbc = jdbc;
		this.callers = callers;
	}

	/** The escalated issues waiting for a decision, oldest first, then the decided ones, most recent first. */
	@GetMapping
	List<AdminTeamIssueRow> teamIssues() {
		UUID me = this.callers.get().requireAccountId();
		return this.jdbc.sql("""
				SELECT id FROM dispute.team_issue WHERE escalated_at IS NOT NULL
				ORDER BY phase = 'ESCALATED' DESC, CASE WHEN phase = 'ESCALATED' THEN escalated_at END ASC, ended_at DESC, id
				LIMIT 200
				""").query(UUID.class).list().stream().map(id -> {
			TeamIssue issue = this.issues.find(id).orElseThrow();
			UUID assignee = this.decisions.assignee(id);
			return new AdminTeamIssueRow(id, issue.jobId(), title(issue.jobId()), issue.subject().name(),
					issue.milestoneNumber(), issue.phase().name(), issue.ending() == null ? null : issue.ending().name(),
					issue.escalatedAt(), assignee != null, me.equals(assignee));
		}).toList();
	}

	@GetMapping("/{teamIssueId}")
	AdminTeamIssueDetail teamIssue(@PathVariable("teamIssueId") UUID issueId) {
		return detail(issueId, this.callers.get().requireAccountId());
	}

	/** "Nhận xử lý": the viewer takes it, over another Administrator if need be, with its read grant. */
	@PostMapping("/{teamIssueId}/take")
	AdminTeamIssueDetail take(@PathVariable("teamIssueId") UUID issueId) {
		UUID me = this.callers.get().requireAccountId();
		this.decisions.take(issueId, me);
		return detail(issueId, me);
	}

	/** The assignee's decision after the step-up; it is executed at once. */
	@PostMapping("/{teamIssueId}/decision")
	AdminTeamIssueDetail decide(@PathVariable("teamIssueId") UUID issueId, @Valid @RequestBody TeamIssueDecisionRequest body,
			HttpServletRequest request) {
		UUID me = this.callers.get().requireAccountId();
		Decision decision = this.decisions.decide(issueId, me, body.points(), body.removeRating(), body.reasoning(),
				body.stepUpCode());
		if (decision instanceof Decision.Refused refused) {
			if (refused.endSession()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			throw refused.refusal();
		}
		return detail(issueId, me);
	}

	private AdminTeamIssueDetail detail(UUID issueId, UUID me) {
		TeamIssue issue = this.decisions.escalated(issueId);
		UUID assignee = this.decisions.assignee(issueId);
		boolean mine = me.equals(assignee);
		TeamIssueSplitView split = null;
		if (issue.subject() == Subject.SHARE && issue.phase() != Phase.ENDED) {
			Split found = this.answers.split(issue).orElse(null);
			if (found != null) {
				split = new TeamIssueSplitView(found.basisVnd(), found.mentorPercent(), found.raiserPercent(),
						this.answers.cap(issue), found.others());
			}
		}
		AdminRatingEvidenceView rating = issue.ratingId() != null && mine
				? this.ratings.evidence(issue.ratingId(), me)
					.map(found -> new AdminRatingEvidenceView(found.stars(), found.comment(), found.createdAt(), found.hiddenAt()))
					.orElse(null)
				: null;
		Recorded decided = this.decisions.decision(issueId).orElse(null);
		String raiser = issue.raiserId() == null ? null : this.students.cards(List.of(issue.raiserId()))
			.stream()
			.findFirst()
			.map(card -> card.name() != null ? card.name() : "@" + card.githubLogin())
			.orElse(null);
		String mentor = issue.mentorId() == null ? null : this.mentors.cards(List.of(issue.mentorId()))
			.stream()
			.findFirst()
			.map(MentorDirectory.MentorCard::displayName)
			.orElse(null);
		return new AdminTeamIssueDetail(issue.id(), issue.jobId(), title(issue.jobId()), issue.subject().name(),
				issue.milestoneNumber(), raiser, mentor, issue.reason(), issue.phase().name(), issue.raisedAt(),
				issue.escalatedAt(), issue.answerKind() == null ? null : issue.answerKind().name(), issue.answerPoints(),
				assignee != null, mine, split, rating,
				decided == null ? null
						: new AdminTeamIssueDecisionView(decided.points(), decided.ratingRemoved(), decided.reasoning(),
								decided.decidedAt()),
				issue.ending() == null ? null : issue.ending().name(), issue.endedAt());
	}

	private String title(UUID jobId) {
		return this.posts.post(jobId).map(JobPosts.JobPostState::title).orElse("");
	}

}
