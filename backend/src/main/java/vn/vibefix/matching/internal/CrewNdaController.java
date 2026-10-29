package vn.vibefix.matching.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.CrewPicks.Assignment;
import vn.vibefix.job.CrewRecruitment;
import vn.vibefix.job.CrewRecruitment.Recruitment;
import vn.vibefix.job.JobRules;
import vn.vibefix.matching.internal.CrewApplications.CrewApplication;
import vn.vibefix.matching.internal.CrewApplications.EndReason;
import vn.vibefix.matching.internal.CrewApplications.Status;
import vn.vibefix.matching.internal.SourceContributors.Contribution;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * MEN-3's Commitment panel for a picked person (FR-81, AD-20, AD-41, UX-DR103, N-5, N-14, N-19, N-23, Story 21.5): the
 * person reads their pick on the Job, with the assignment projection (the title, the scope, and the Milestones and
 * prices of the Agreement in force or the live draft) and the split rules while it waits for the signature, and signs
 * ("Ký và tham gia") or declines ("Từ chối"); after the pick ends, the panel shows why. Only the picked person finds any
 * of it, by their own Crew Application: the Customer, the Mentor, a member, and everyone else get {@code NOT_FOUND}, and
 * nothing else of the Job is read here (AD-41). The commands need an unlocked account (AD-19), and signing a linked
 * GitHub account too (FR-80), whose Source Repository contributions are read before the signature's transaction (A-03).
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewNdaController {

	/** The rule names of the split rules, as the Job copied them when it was published (FR-61, FR-83). */
	static final String CAP_RULE = "MENTOR_SHARE_CAP_PERCENT";

	static final String DRAFT_RULE = "SPLIT_DRAFT_DAYS";

	static final String CONFIRMATION_RULE = "SPLIT_CONFIRMATION_DAYS";

	static final String EDIT_RULE = "SPLIT_EDIT_LIMIT";

	/** "Ký và tham gia": the NDA and commitment texts the panel showed, N-5 and, for a Code Job, N-23. */
	record CommitmentSignRequest(@NotBlank String ndaVersion, @NotBlank String commitmentVersion,
			boolean publicCodeAcknowledged, boolean commitEmailNoticeAcknowledged) {
	}

	/** One Milestone of the assignment projection, with its price; never a share. */
	record CommitmentMilestoneView(String title, @Nullable LocalDate dueOn, @Nullable Long priceVnd) {
	}

	/**
	 * The split rules the person accepts by signing (FR-81, FR-83), as the Job copied them when it was published.
	 * @param mentorShareCapPercent the Mentor's largest share of a Milestone while a member is on the Crew
	 * @param draftDays the days after a Milestone's release by which the Mentor drafts its split
	 * @param confirmationDays the days each party has to confirm a split version
	 * @param editLimit the split versions the Mentor may save after the first
	 */
	record SplitRulesView(int mentorShareCapPercent, int draftDays, int confirmationDays, int editLimit) {
	}

	/**
	 * What a pick waiting for its signature shows (UX-DR103): the assignment projection, the split rules, the NDA and
	 * commitment versions, and the notices.
	 * @param scope the Agreement's scope, absent before the Mentor drafted it
	 * @param codeJob a Code Job: N-23 is acknowledged before signing, once per account and notice version
	 * @param githubLogin the person's GitHub username, which N-19 says the Customer learns on signing
	 */
	record CommitmentPanelView(@Nullable String scope, List<CommitmentMilestoneView> milestones, boolean codeJob,
			boolean commitEmailNoticeAcknowledged, @Nullable String githubLogin, String ndaVersion, String commitmentVersion,
			SplitRulesView splitRules) {
	}

	/**
	 * The person's latest pick on the Job.
	 * @param status {@code PICKED} while it waits, {@code JOINED} once signed, else how it ended
	 * @param endReason {@code PICK_WITHDRAWN}, {@code COMMITMENT_DECLINED}, {@code COMMITMENT_LAPSED},
	 * {@code PICK_ENDED} (J8 or J10), {@code APPLICANT_UNAVAILABLE} or {@code SIGNED}
	 * @param panel the Commitment panel while the pick waits; absent once it ended
	 */
	record CommitmentView(UUID applicationId, UUID jobId, String jobTitle, String mentorName, Status status,
			@Nullable EndReason endReason, Instant pickedAt, Instant signDueAt, @Nullable CommitmentPanelView panel) {
	}

	private final CrewCommitments commitments;

	private final CrewSignatures signatures;

	private final SourceContributors contributors;

	private final CrewRecruitment recruitment;

	private final CrewApplications applications;

	private final JobRules rules;

	private final GitHubGate gitHub;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	CrewNdaController(CrewCommitments commitments, CrewSignatures signatures, SourceContributors contributors,
			CrewRecruitment recruitment, CrewApplications applications, JobRules rules, GitHubGate gitHub,
			ActiveAccount gate, CurrentCaller callers) {
		this.commitments = commitments;
		this.signatures = signatures;
		this.contributors = contributors;
		this.recruitment = recruitment;
		this.applications = applications;
		this.rules = rules;
		this.gitHub = gitHub;
		this.gate = gate;
		this.callers = callers;
	}

	/** MEN-3's Commitment panel, or the ended state of the person's pick (UX-DR103). */
	@GetMapping("/api/v1/crew/jobs/{jobId}/commitment")
	CommitmentView commitment(@PathVariable UUID jobId) {
		UUID person = person();
		return view(person, jobId);
	}

	/**
	 * "Ký và tham gia" (FR-81): a caller with no pick here finds nothing; the GitHub link (FR-80) and the cheap refusals
	 * come next, so a signature refused anyway asks GitHub nothing; then GitHub is read here, before the signature's
	 * transaction and its locks, and the answer is passed in (A-03).
	 */
	@PostMapping("/api/v1/crew/jobs/{jobId}/commitment")
	CommitmentView sign(@PathVariable UUID jobId, @Valid @RequestBody CommitmentSignRequest body) {
		UUID person = person();
		this.commitments.requirePicked(person, jobId);
		this.gitHub.requireGitHubLinked(person);
		if (!body.publicCodeAcknowledged()) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PUBLIC_CODE_NOT_ACKNOWLEDGED);
		}
		Contribution contribution = this.contributors.readForCrew(jobId, person);
		this.signatures.sign(person, jobId, body.ndaVersion(), body.commitmentVersion(), body.publicCodeAcknowledged(),
				body.commitEmailNoticeAcknowledged(), contribution);
		return view(person, jobId);
	}

	/** "Từ chối" (R4): the pick ends, and the Mentor is told. */
	@PostMapping("/api/v1/crew/jobs/{jobId}/commitment/decline")
	CommitmentView decline(@PathVariable UUID jobId) {
		UUID person = person();
		this.commitments.decline(person, jobId);
		return view(person, jobId);
	}

	private CommitmentView view(UUID person, UUID jobId) {
		CrewApplication pick = this.commitments.pickOf(person, jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Recruitment found = this.recruitment.recruitment(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		CommitmentPanelView panel = null;
		if (pick.status() == Status.PICKED) {
			Assignment assignment = this.commitments.assignment(person, jobId).orElse(null);
			if (assignment != null) {
				PersonFacts facts = this.commitments.person(person);
				panel = new CommitmentPanelView(assignment.scope(),
						assignment.milestones()
							.stream()
							.map(milestone -> new CommitmentMilestoneView(milestone.title(), milestone.dueOn(), milestone.priceVnd()))
							.toList(),
						assignment.codeJob(), this.commitments.commitEmailAcknowledged(person),
						facts == null ? null : facts.githubLogin(), NdaSignatures.NDA_VERSION, CrewCommitments.COMMITMENT_VERSION,
						splitRules(jobId));
			}
		}
		return new CommitmentView(pick.id(), jobId, found.title(), this.applications.name(pick.mentorId()), pick.status(),
				pick.endReason(), pick.pickedAt() == null ? pick.submittedAt() : pick.pickedAt(),
				pick.signDueAt() == null ? pick.lapsesAt() : pick.signDueAt(), panel);
	}

	/** The split rules as the Job copied them when it was published, else FR-61's defaults (PRD FR-83). */
	private SplitRulesView splitRules(UUID jobId) {
		return new SplitRulesView(this.rules.value(jobId, CAP_RULE, 40), this.rules.value(jobId, DRAFT_RULE, 7),
				this.rules.value(jobId, CONFIRMATION_RULE, 3), this.rules.value(jobId, EDIT_RULE, 3));
	}

	/** A person's unlocked account (AD-19): an Administrator holds no role in any Job, so it finds nothing (SG-31). */
	private UUID person() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID person = caller.requireAccountId();
		this.gate.requireActiveAccount(person);
		return person;
	}

}
