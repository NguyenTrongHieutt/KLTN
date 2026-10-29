package vn.vibefix.matching.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.job.JobRoles;
import vn.vibefix.matching.internal.BidProofs.Owner;
import vn.vibefix.shared.Caller;

/**
 * Who may read a bid's proof file (FR-13, AD-20, AD-21): the bidder who uploaded it, and once a bid carries it, the
 * Job's Customer, by their role in the Job (AD-43), until the bidder withdraws the bid, which the Customer then no
 * longer sees. Nobody else, Administrators included, reads one; {@code files}
 * serves it as an attachment with {@code nosniff} and {@code Content-Security-Policy: sandbox}.
 */
@Component
class BidProofFiles implements FileReadPolicy {

	/** Looked up on use: the role check reads {@code job}, which reads {@code files}. */
	private final ObjectProvider<BidProofs> proofs;

	private final ObjectProvider<JobRoles> roles;

	BidProofFiles(ObjectProvider<BidProofs> proofs, ObjectProvider<JobRoles> roles) {
		this.proofs = proofs;
		this.roles = roles;
	}

	@Override
	public String purpose() {
		return BidProofs.PURPOSE;
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
		if (owner.bidderId().equals(caller.accountId())) {
			return true;
		}
		return owner.applicationId() != null && !owner.withdrawn()
				&& this.roles.getObject().roleOf(owner.jobId(), caller).isCustomer();
	}

}
