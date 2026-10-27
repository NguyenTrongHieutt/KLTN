package vn.vibefix.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Kind;

/**
 * {@link GitHubGate} and {@link ActiveAccount} on the account row, read again on every call (AD-19), by the
 * rules {@link Accounts#ACTIVE} and {@link Accounts#GITHUB_LINKED} the Mentor directory shares. Both gate user
 * commands, so both refuse an Administrator, who holds no role in any Job (FR-2).
 */
@Component
class AccountGates implements GitHubGate, ActiveAccount {

	private final Accounts accounts;

	AccountGates(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public void requireGitHubLinked(UUID accountId) {
		if (!activeUser(accountId).gitHubLinked()) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.GITHUB_NOT_LINKED);
		}
	}

	@Override
	public void requireActiveAccount(UUID accountId) {
		activeUser(accountId);
	}

	/** A person's account that is unlocked and not erased. */
	private Accounts.Standing activeUser(UUID accountId) {
		Accounts.Standing standing = this.accounts.standing(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED));
		if (standing.kind() != Kind.USER) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		if (!standing.active()) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED);
		}
		return standing;
	}

}
