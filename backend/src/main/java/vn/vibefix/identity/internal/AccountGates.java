package vn.vibefix.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link GitHubGate} and {@link ActiveAccount} on the account row, read again on every call (AD-19), by the
 * rules {@link Accounts#ACTIVE} and {@link Accounts#GITHUB_LINKED} the Mentor directory shares.
 */
@Component
class AccountGates implements GitHubGate, ActiveAccount {

	private final Accounts accounts;

	AccountGates(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public void requireGitHubLinked(UUID accountId) {
		if (!active(accountId).gitHubLinked()) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.GITHUB_NOT_LINKED);
		}
	}

	@Override
	public void requireActiveAccount(UUID accountId) {
		active(accountId);
	}

	private Accounts.Standing active(UUID accountId) {
		Accounts.Standing standing = this.accounts.standing(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED));
		if (!standing.active()) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED);
		}
		return standing;
	}

}
