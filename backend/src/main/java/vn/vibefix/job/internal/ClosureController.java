package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.internal.ClosureProposals.Party;
import vn.vibefix.job.internal.ClosureProposals.Proposal;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-1's closure section (FR-37, FR-67, AD-20, AD-23, UX-DR55), under {@code /api/v1/boards/{boardId}}
 * like the Job's other Active-phase calls (a Board's ID is its Job's): the proposal and whose turn it is,
 * and each party's steps from the proposal through Closed. The Customer and the Mentor propose, confirm,
 * and decline on the version they loaded; an active Crew member reads it and may not answer
 * ({@code FORBIDDEN MENTOR_ONLY}); everyone else finds nothing. The data-deletion steps are
 * {@code repo}'s, which the section reads beside this one (FR-66).
 */
@RestController
@Access(Gate.SIGNED_IN)
class ClosureController {

	/**
	 * One closure step.
	 * @param party {@code CUSTOMER} or {@code MENTOR}
	 * @param step {@code CONFIRM_CLOSURE}
	 */
	record ClosureStepView(String party, String name, String step, boolean done) {
	}

	/**
	 * @param kind {@code AUTOMATIC} (the System, FR-67) or {@code PROPOSED} (FR-37)
	 * @param proposer {@code SYSTEM}, {@code CUSTOMER}, or {@code MENTOR}
	 * @param answerable the viewer may confirm or decline it now
	 * @param unanswered a person's proposal went unanswered past its date (FR-39)
	 */
	record ClosureProposalView(UUID id, String kind, String status, String proposer, @Nullable Instant autoConfirmAt,
			Instant answerBy, boolean customerConfirmed, boolean mentorConfirmed, boolean answerable, boolean unanswered) {
	}

	/**
	 * @param version the Job's version, which every command carries (AD-23)
	 * @param audience {@code CUSTOMER}, {@code MENTOR}, or {@code CREW}
	 * @param canPropose the viewer may propose closing an Active Job with no Held Milestone
	 * @param disputable the latest closure proposal was declined, or went unanswered: either side may open a
	 * Dispute (FR-39)
	 */
	record ClosureView(UUID jobId, String state, long version, String audience, boolean canPropose,
			@Nullable ClosureProposalView proposal, List<ClosureStepView> steps, boolean disputable) {
	}

	record ClosureCommandRequest(@NotNull Long version) {
	}

	private record Viewer(Job job, UUID accountId, @Nullable Party party) {
	}

	private final JdbcClient jdbc;

	private final Closures closures;

	private final ClosureProposals proposals;

	private final CrewService crews;

	private final CustomerNames customers;

	private final MentorDirectory mentors;

	private final CurrentCaller callers;

	ClosureController(JdbcClient jdbc, Closures closures, ClosureProposals proposals, CrewService crews,
			CustomerNames customers, MentorDirectory mentors, CurrentCaller callers) {
		this.jdbc = jdbc;
		this.closures = closures;
		this.proposals = proposals;
		this.crews = crews;
		this.customers = customers;
		this.mentors = mentors;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/closure")
	ClosureView closure(@PathVariable("boardId") UUID jobId) {
		return view(viewer(jobId, this.callers.get()));
	}

	/** "Đề nghị kết thúc công việc" (FR-37). */
	@PostMapping("/api/v1/boards/{boardId}/closure")
	@ResponseStatus(HttpStatus.CREATED)
	ClosureView propose(@PathVariable("boardId") UUID jobId, @Valid @RequestBody ClosureCommandRequest body) {
		Viewer viewer = answerer(jobId);
		this.closures.propose(jobId, viewer.accountId(), viewer.party(), body.version());
		return view(viewer(jobId, this.callers.get()));
	}

	/** "Xác nhận kết thúc" (FR-37, FR-67): the closure applies once confirmed. */
	@PostMapping("/api/v1/boards/{boardId}/closure/confirm")
	ClosureView confirm(@PathVariable("boardId") UUID jobId, @Valid @RequestBody ClosureCommandRequest body) {
		Viewer viewer = answerer(jobId);
		this.closures.confirm(jobId, viewer.accountId(), viewer.party(), body.version());
		return view(viewer(jobId, this.callers.get()));
	}

	/** "Từ chối": the Job stays Active (FR-39 brings the Dispute in Epic 9). */
	@PostMapping("/api/v1/boards/{boardId}/closure/decline")
	ClosureView decline(@PathVariable("boardId") UUID jobId, @Valid @RequestBody ClosureCommandRequest body) {
		Viewer viewer = answerer(jobId);
		this.closures.decline(jobId, viewer.accountId(), viewer.party(), body.version());
		return view(viewer(jobId, this.callers.get()));
	}

	private Viewer answerer(UUID jobId) {
		Viewer viewer = viewer(jobId, this.callers.get());
		if (viewer.party() == null) {
			throw new ApiException(ErrorCode.FORBIDDEN, JobReason.MENTOR_ONLY);
		}
		return viewer;
	}

	/** The Customer, the Mentor, or an active Crew member (party empty) of a Job with a Board. */
	private Viewer viewer(UUID jobId, Caller caller) {
		Job job = this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		boolean board = job.state() == JobState.ACTIVE || job.state() == JobState.CLOSING || job.state() == JobState.CLOSED;
		if (account == null || job.mentorId() == null || !board) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (caller.hasRole(Role.CUSTOMER) && account.equals(job.customerId())) {
			return new Viewer(job, account, Party.CUSTOMER);
		}
		if (caller.hasRole(Role.MENTOR) && account.equals(job.mentorId())) {
			return new Viewer(job, account, Party.MENTOR);
		}
		if (caller.hasRole(Role.STUDENT) && this.crews.crewRelationship(jobId, account).kind() == Relationship.ACTIVE) {
			return new Viewer(job, account, null);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	private ClosureView view(Viewer viewer) {
		Job job = viewer.job();
		Proposal proposal = this.proposals.latest(job.id()).orElse(null);
		boolean open = proposal != null && "OPEN".equals(proposal.status());
		boolean ended = job.state() == JobState.CLOSING || job.state() == JobState.CLOSED;
		boolean canPropose = viewer.party() != null && job.state() == JobState.ACTIVE && !open
				&& !Closures.held(this.jdbc, job.id());
		ClosureProposalView proposalView = null;
		if (proposal != null && (open || ended)) {
			boolean answerable = open && viewer.party() != null && !proposal.confirmedBy(viewer.party())
					&& (proposal.automatic() || !viewer.accountId().equals(proposal.proposerId()));
			String proposer = proposal.automatic() ? "SYSTEM"
					: job.customerId().equals(proposal.proposerId()) ? "CUSTOMER" : "MENTOR";
			proposalView = new ClosureProposalView(proposal.id(), proposal.kind(), proposal.status(), proposer,
					proposal.autoConfirmAt(), proposal.answerBy(), proposal.customerConfirmedAt() != null,
					proposal.mentorConfirmedAt() != null, answerable, this.proposals.wentUnanswered(proposal.id()));
		}
		List<ClosureStepView> steps = new ArrayList<>();
		if (proposalView != null || ended) {
			boolean customerDone = ended || (proposal != null && proposal.customerConfirmedAt() != null);
			boolean mentorDone = ended || (proposal != null && proposal.mentorConfirmedAt() != null);
			steps.add(new ClosureStepView("CUSTOMER", this.customers.displayName(job.customerId()), "CONFIRM_CLOSURE",
					customerDone));
			steps.add(new ClosureStepView("MENTOR",
					this.mentors.cards(List.of(job.mentorId())).stream().findFirst().map(MentorCard::displayName).orElse(""),
					"CONFIRM_CLOSURE", mentorDone));
		}
		boolean disputable = viewer.party() != null && job.state() == JobState.ACTIVE && proposal != null
				&& !proposal.automatic()
				&& ("DECLINED".equals(proposal.status()) || (open && this.proposals.wentUnanswered(proposal.id())));
		return new ClosureView(job.id(), job.state().name(), job.version(),
				viewer.party() == null ? "CREW" : viewer.party().name(), canPropose, proposalView, steps, disputable);
	}

}
