package vn.vibefix.identity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.Role;

/**
 * ADM-2's accounts (FR-5, FR-59, FR-74, AD-19): the search, one account with its history, and what an
 * Administrator may do to it. Every command is audited with its reason; Administrators are not
 * listed here (ADM-11).
 */
public interface AccountAdministration {

	/** A page holds this many accounts. */
	int PAGE_SIZE = 20;

	/** What a status chip filters on. */
	enum Status {

		LOCKED, VERIFIED, PENDING, APPROVED, REJECTED, REVOKED

	}

	/** Why a Mentor's approval was revoked: the Mentor reads the reason either way (FR-78). */
	enum Ground {

		OTHER, PRIVATE_FEEDBACK

	}

	/**
	 * The search.
	 * @param text matched within the name, the GitHub login, and the email, ignoring accents and case
	 * @param ids only these accounts, such as ADM-1's low private feedback list
	 */
	record Query(@Nullable String text, @Nullable Role role, @Nullable Status status, @Nullable Set<UUID> ids,
			int page) {
	}

	/** @param status {@code VERIFIED} or {@code UNVERIFIED} for a Student, the approval for a Mentor */
	record Row(UUID id, Role role, @Nullable String name, @Nullable String githubLogin, boolean locked,
			@Nullable String status, Instant createdAt) {
	}

	record Page(List<Row> items, long total, int page, int size) {
	}

	/** @param action {@code LOCKED}, {@code UNLOCKED}, {@code VERIFICATION_REVOKED}, or {@code APPROVAL_REVOKED} */
	record Action(String action, String reason, @Nullable Ground ground, @Nullable String actedBy, Instant actedAt) {
	}

	record Verification(String method, Instant verifiedAt, LocalDate validUntil, boolean current) {
	}

	/** One account as ADM-2 shows it. */
	record Account(UUID id, Role role, @Nullable String name, @Nullable String githubLogin, @Nullable String email,
			Instant createdAt, @Nullable Instant signedInAt, @Nullable Instant lockedAt, @Nullable String lockReason,
			@Nullable MentorApprovalStatus approval, @Nullable Verification verification, List<Action> history) {
	}

	/** A step-up command's result: refused, or done. */
	record Result(@Nullable ApiException refusal, boolean endSession) {

		public boolean done() {
			return this.refusal == null;
		}

	}

	Page search(Query query);

	/** The names of these accounts, Administrators included, for ADM-5 and ADM-8; unnamed ones are left out. */
	Map<UUID, String> names(Collection<UUID> accountIds);

	Optional<Account> account(UUID accountId);

	/**
	 * Locks the account: its sessions end, {@link AccountLocked} is published, and the person is emailed
	 * the reason.
	 * @throws ApiException {@code NOT_FOUND}; {@code FORBIDDEN ADMINISTRATOR_ACCOUNT};
	 * {@code CONFLICT ALREADY_LOCKED}
	 */
	void lock(UUID administrator, UUID accountId, String reason);

	/**
	 * Unlocks the account; nothing else is restored.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT NOT_LOCKED}
	 */
	void unlock(UUID administrator, UUID accountId, String reason);

	/**
	 * Revokes a Student's current verification; running Crews keep the Student.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT NOT_VERIFIED}
	 */
	void revokeVerification(UUID administrator, UUID accountId, String reason);

	/**
	 * Revokes a Mentor's approval after the step-up, publishing {@link MentorApprovalRevoked}; the caller
	 * checks a {@code PRIVATE_FEEDBACK} ground's threshold and passes its category as the reason.
	 */
	Result revokeApproval(UUID administrator, UUID accountId, Ground ground, String reason, String stepUpCode);

	/**
	 * A person's account deletion request as ADM-2 shows it (NFR-11, AD-24).
	 * @param completedAt when an Administrator confirmed it and the account was erased
	 * @param blockers what keeps it waiting now; none once it is completed
	 */
	record DeletionRequest(Instant requestedAt, @Nullable Instant completedAt, List<ErasureBlocker> blockers) {

		public DeletionRequest {
			blockers = List.copyOf(blockers);
		}

	}

	/** The account's deletion request, if the person made one. */
	Optional<DeletionRequest> deletionRequest(UUID accountId);

	/**
	 * Confirms the account's deletion request after the step-up: every participant erases or anonymizes the
	 * account, its sessions end, the sign-in grant is revoked through an operation when a token was kept (SG-9),
	 * and {@link AccountErased} is published. A refusal comes back in the result: {@code GUARD_FAILED
	 * NO_DELETION_REQUEST}, or {@code GUARD_FAILED ERASURE_BLOCKED} with the blockers while any remains.
	 */
	Result erase(UUID administrator, UUID accountId, String stepUpCode);

}
