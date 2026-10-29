package vn.vibefix.matching.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.job.JobRoles;
import vn.vibefix.matching.internal.CrewApplicationProofs.Owner;
import vn.vibefix.shared.Caller;

/**
 * Who may read a Crew Application's proof file (FR-81, AD-20, AD-21, Story 21.4): the applicant who uploaded it, and
 * once an application carries it, the Job's Mentor, by their role in the Job (AD-43), until the applicant withdraws the
 * application. Nobody else, the Customer and Administrators included, reads one (no Administrator grant covers a Crew
 * Application); {@code files} serves it as an attachment with {@code nosniff} and {@code Content-Security-Policy:
 * sandbox}.
 */
@Component
class CrewApplicationProofFiles implements FileReadPolicy {

	/** Looked up on use: the role check reads {@code job}, which reads {@code files}. */
	private final ObjectProvider<CrewApplicationProofs> proofs;

	private final ObjectProvider<JobRoles> roles;

	CrewApplicationProofFiles(ObjectProvider<CrewApplicationProofs> proofs, ObjectProvider<JobRoles> roles) {
		this.proofs = proofs;
		this.roles = roles;
	}

	@Override
	public String purpose() {
		return CrewApplicationProofs.PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		if (!caller.isUser() || caller.accountId() == null) {
			return false;
		}
		Owner owner = this.proofs.getObject().owner(fileId).orElse(null);
		if (owner == null) {
			return false;
		}
		if (owner.applicantId().equals(caller.accountId())) {
			return true;
		}
		return owner.applicationId() != null && !owner.withdrawn()
				&& this.roles.getObject().roleOf(owner.jobId(), caller).isMentor();
	}

}
