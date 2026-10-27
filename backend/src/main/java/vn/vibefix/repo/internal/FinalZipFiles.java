package vn.vibefix.repo.internal;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.files.FileReadPolicy;
import vn.vibefix.shared.Caller;

/**
 * Who may read a Job's final ZIP (FR-21, FR-38, AD-20): the Customer who owns it. The archive is stored as the
 * Job's Customer's file and names no Job, so the relation is its owner, a person's account (AD-43).
 */
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
		return caller.isUser() && this.deletions.getObject().ownsArchive(fileId, caller.requireAccountId());
	}

}
