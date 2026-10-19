package vn.vibefix.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;

/** {@link MentorGate} on the account row, read again on every call (AD-19). */
@Component
class MentorGates implements MentorGate {

	private final Accounts accounts;

	MentorGates(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public void requireApprovedMentor(UUID accountId) {
		Account account = active(accountId);
		if (account.role() != Role.MENTOR || account.mentorApprovalStatus() != MentorApprovalStatus.APPROVED) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.MENTOR_NOT_APPROVED);
		}
	}

	@Override
	public void requireActiveAccount(UUID accountId) {
		active(accountId);
	}

	private Account active(UUID accountId) {
		Account account = this.accounts.findById(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED));
		if (account.isLocked() || account.erasedAt() != null) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED);
		}
		return account;
	}

}
