package vn.vibefix.repo.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;

/** Who may read a Job's final ZIP (FR-21, FR-38, AD-20): the Customer who owns it. */
@Component
class FinalZipFiles implements FileReadPolicy {

	private final ObjectProvider<RepositoryDeletions> deletions;

	FinalZipFiles(ObjectProvider<RepositoryDeletions> deletions) {
		this.deletions = deletions;
	}

	@Override
	public String purpose() {
		return RepositoryDeletions.ZIP_PURPOSE;
	}

	@Override
	public boolean mayRead(UUID fileId, Caller caller) {
		return caller.hasRole(Role.CUSTOMER) && this.deletions.getObject().ownsArchive(fileId, caller.requireAccountId());
	}

}
