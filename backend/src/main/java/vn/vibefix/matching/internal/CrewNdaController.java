package vn.vibefix.matching.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.job.Crews;
import vn.vibefix.job.JobRoles;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * STU-4's "Ký và xác nhận" and "Từ chối" (FR-75): a Crew member's commands, which need an unlocked account
 * (AD-19); signing needs a linked GitHub account too (FR-80), since the Crew member is granted on the Working
 * Repository by it. Anyone but an assigned Student finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewNdaController {

	/** The Crew Terms version and the NDA text the panel showed, with N-5 and, for a Code Job, N-23. */
	record CrewConfirmRequest(@NotNull Integer crewTermsVersionNo, @NotBlank String ndaVersion, boolean publicCodeAcknowledged,
			boolean commitEmailNoticeAcknowledged) {
	}

	record CrewDeclineRequest(@NotNull Integer crewTermsVersionNo) {
	}

	private final CrewSignatures signatures;

	private final Crews crews;

	private final CurrentCaller callers;

	private final GitHubGate gitHub;

	private final JobRoles roles;

	CrewNdaController(CrewSignatures signatures, Crews crews, CurrentCaller callers, GitHubGate gitHub,
			JobRoles roles) {
		this.gitHub = gitHub;
		this.roles = roles;
		this.signatures = signatures;
		this.crews = crews;
		this.callers = callers;
	}

	@PostMapping("/api/v1/crew/jobs/{jobId}/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void confirmCrewTerms(@PathVariable UUID jobId, @Valid @RequestBody CrewConfirmRequest body) {
		UUID student = member(jobId);
		this.gitHub.requireGitHubLinked(student);
		this.signatures.confirm(student, jobId, body.crewTermsVersionNo(), body.ndaVersion(), body.publicCodeAcknowledged(),
				body.commitEmailNoticeAcknowledged());
	}

	@PostMapping("/api/v1/crew/jobs/{jobId}/decline")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void declineCrewTerms(@PathVariable UUID jobId, @Valid @RequestBody CrewDeclineRequest body) {
		this.crews.decline(jobId, member(jobId), body.crewTermsVersionNo());
	}

	/**
	 * A Crew member of the Job, by their role in it (AD-43); anyone else finds nothing. {@code job} then finds
	 * the member's open assignment, or answers {@code NOT_FOUND}.
	 */
	private UUID member(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isCrew()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
