package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.Capabilities;
import vn.vibefix.identity.StudentVerification;

/**
 * {@link Capabilities} on the account row and its student verification, read on every call (AD-19), by the
 * rules the gates and the verification already own: GitHub is verified by {@link Accounts#GITHUB_LINKED}, the
 * student verification by {@link StudentVerification#isVerified} (a live row valid today in Vietnam by the
 * injected Clock, AD-42), and the account is locked while {@code locked_at} is set. LinkedIn is not verified
 * by anyone until Story 19.6.
 */
@Component
class AccountCapabilities implements Capabilities {

	private final Accounts accounts;

	private final StudentVerification students;

	AccountCapabilities(Accounts accounts, StudentVerification students) {
		this.accounts = accounts;
		this.students = students;
	}

	@Override
	public Held capabilities(UUID accountId) {
		return this.accounts.standing(accountId)
			.map(standing -> new Held(standing.gitHubLinked(), false, this.students.isVerified(accountId),
					standing.locked()))
			.orElse(Held.NONE);
	}

	@Override
	public Map<UUID, Instant> lockedAmong(Collection<UUID> accountIds) {
		return this.accounts.lockedAmong(accountIds);
	}

}
