package vn.vibefix.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/** {@link GitHubGate} and {@link ActiveAccount} on the account row, read again on every call (AD-19). */
@Component
class AccountGates implements GitHubGate, ActiveAccount {

	private final Accounts accounts;

	AccountGates(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public void requireGitHubLinked(UUID accountId) {
		if (active(accountId).githubUserId() == null) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.GITHUB_NOT_LINKED);
		}
	}

	@Override
	public void requireActiveAccount(UUID accountId) {
		active(accountId);
	}

	private Account active(UUID accountId) {
		Account account = this.accounts.findById(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED));
		if (!account.isActive()) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED);
		}
		return account;
	}

}
