package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.GitHubLinkBlocker;
import vn.vibefix.identity.GitHubLinkParticipant;
import vn.vibefix.identity.GitHubLinks;
import vn.vibefix.identity.Notices;
import vn.vibefix.identity.internal.GitHubLinkReader.LinkedUser;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The rules of an account's GitHub link (FR-80, R14, AD-10, AD-19, AD-22, AD-24). One GitHub account
 * links one vibefix account, by its numeric ID, and nothing is ever merged; a linked account re-links
 * only the same ID. The token GitHub issues is kept, sealed with the account ID as associated data, only
 * for a first Product's registration or while a participant needs it (the owner of a live Product), and
 * discarded otherwise. Unlinking waits until no participant reports a blocker; it removes the public
 * facts at once, while the person-link signals stay under AD-24's retention. Every change is audited.
 */
@Component
class GitHubLinksImpl implements GitHubLinks {

	/** What a link is started for: ACC-2's row, or CUS-3 step 2, which needs the token kept. */
	enum Purpose {

		PROFILE, PRODUCT

	}

	/** What a returning link led to. */
	enum Outcome {

		/** The account had no GitHub link and has one now. */
		LINKED,

		/** The linked GitHub account came back: its login, avatar, and emails are read again. */
		RELINKED,

		/** Another account holds this GitHub ID (ACC-2's {@code da-lien-ket-tai-khoan-khac}); nothing changed. */
		ALREADY_LINKED,

		/** The account is linked to another GitHub ID (ACC-2's {@code sai-tai-khoan}); nothing changed. */
		LINK_IN_USE,

		/** The account was locked, erased, or is not a person's; nothing changed. */
		ACCOUNT_INACTIVE

	}

	/**
	 * ACC-2's GitHub row.
	 * @param relinkNeeded the {@code identity.relink_github} to-do is open: "Cần liên kết lại"
	 * @param repositoryTokenKept a token is kept for the account's repositories (CUS-3 step 2 re-links
	 * without one)
	 */
	record Status(boolean linked, @Nullable String login, @Nullable String avatarUrl, @Nullable Instant linkedAt,
			boolean relinkNeeded, boolean repositoryTokenKept, List<GitHubLinkBlocker> blockers) {
	}

	private final Accounts accounts;

	private final TokenCipher cipher;

	private final TokenRenewals renewals;

	private final Todos todos;

	private final JdbcPersonSignals signals;

	private final ExternalOperations operations;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final ObjectProvider<GitHubLinkParticipant> participants;

	GitHubLinksImpl(Accounts accounts, TokenCipher cipher, TokenRenewals renewals, Todos todos, JdbcPersonSignals signals,
			ExternalOperations operations, AuditLog auditLog, IdGenerator ids, Clock clock,
			ObjectProvider<GitHubLinkParticipant> participants) {
		this.accounts = accounts;
		this.cipher = cipher;
		this.renewals = renewals;
		this.todos = todos;
		this.signals = signals;
		this.operations = operations;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.participants = participants;
	}

	/**
	 * N-24 before leaving for GitHub (AD-42, NFR-9): the {@code LINK_GITHUB} acknowledgment with its
	 * version and time; a version accepted before keeps its first time.
	 */
	@Transactional
	void consent(UUID accountId) {
		UUID acknowledgment = this.ids.newId();
		if (this.accounts.insertAcknowledgment(acknowledgment, accountId, Notices.LINK_GITHUB, Notices.LINK_GITHUB_VERSION,
				this.clock.instant())) {
			this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.notice_acknowledged",
					AuditObject.of("identity.account", accountId), AuditObject.of("identity.acknowledgment", acknowledgment)));
		}
	}

	/**
	 * Links what GitHub returned to the account, or refreshes the same GitHub account (FR-80): the ID, the
	 * login, the avatar, the primary verified email, and the time; one {@code GITHUB_EMAIL} signal per
	 * verified email (AD-19); the {@code identity.link_github} and {@code identity.relink_github} to-dos
	 * close.
	 * @param token what GitHub issued, kept only by {@link #keepsToken} and discarded otherwise
	 * @throws org.springframework.dao.DuplicateKeyException when another account linked the same ID in
	 * between; nothing is written
	 */
	@Transactional
	Outcome link(UUID accountId, LinkedUser user, Purpose purpose, @Nullable String token) {
		Account account = this.accounts.lockForLink(accountId)
			.filter(found -> found.kind() == Account.Kind.USER && found.isActive())
			.orElse(null);
		if (account == null) {
			return Outcome.ACCOUNT_INACTIVE;
		}
		Optional<Account> holder = this.accounts.findByGitHubUserId(user.id());
		if (holder.isPresent() && !holder.get().id().equals(accountId)) {
			return Outcome.ALREADY_LINKED;
		}
		if (account.githubUserId() != null && account.githubUserId() != user.id()) {
			return Outcome.LINK_IN_USE;
		}
		boolean relink = account.githubUserId() != null;
		Instant now = this.clock.instant();
		this.accounts.linkGitHub(accountId, user.id(), user.login(), user.avatarUrl(), user.primaryEmail(), now);
		user.verifiedEmails().forEach(email -> this.signals.record(accountId, JdbcPersonSignals.Source.GITHUB_EMAIL, email));
		if (token != null) {
			Optional<TokenCipher.Sealed> previous = this.accounts.customerToken(accountId);
			TokenCipher.Sealed sealed = this.cipher.seal(token, accountId.toString());
			if (keepsToken(accountId, purpose)) {
				this.accounts.storeCustomerToken(accountId, sealed, now);
			}
			else {
				// Not an owner of a live Product, nor a first registration: no token is kept (AD-10).
				this.accounts.deleteCustomerToken(accountId);
				revokeLater(accountId, sealed);
			}
			// The token this link replaced, or the one it dropped, is revoked alone; the new one stays valid.
			previous.ifPresent(old -> revokeLater(accountId, old));
		}
		this.todos.close(SignIns.LINK_GITHUB, accountId, accountId);
		this.renewals.renewed(accountId);
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), relink ? "identity.github_relinked" : "identity.github_linked",
				AuditObject.of("identity.account", accountId)));
		return relink ? Outcome.RELINKED : Outcome.LINKED;
	}

	/**
	 * "Bỏ liên kết" (FR-80, R14): the GitHub columns are cleared, the kept token is deleted and its grant
	 * revoked on GitHub ({@link OAuthGrantRevocation}), and {@code identity.link_github} opens again. The
	 * person-link signals stay (AD-24).
	 * @throws ApiException {@code NOT_FOUND} for an account that is not an active person's or has no link;
	 * {@code GUARD_FAILED GITHUB_LINK_IN_USE} with the {@code blockers}; {@code CONFLICT} when GitHub is the
	 * only way the account is known (an account from before Google sign-in)
	 */
	@Transactional
	void unlink(UUID accountId) {
		Account account = this.accounts.lockForLink(accountId)
			.filter(found -> found.kind() == Account.Kind.USER && found.isActive() && found.githubUserId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<GitHubLinkBlocker> blockers = blockers(accountId);
		if (!blockers.isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.GITHUB_LINK_IN_USE,
					Map.of("blockers", blockers.stream().map(GitHubLinksImpl::blocker).toList()));
		}
		if (account.googleSub() == null) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		Optional<TokenCipher.Sealed> token = this.accounts.customerToken(accountId);
		this.accounts.deleteCustomerToken(accountId);
		this.accounts.unlinkGitHub(accountId);
		token.ifPresent(sealed -> revokeLater(accountId, sealed));
		this.renewals.renewed(accountId);
		this.todos.open(new TodoRequest(SignIns.LINK_GITHUB, accountId, Audience.SUBJECT, accountId, Map.of(),
				SignIns.LINK_GITHUB_PATH, null, null));
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.github_unlinked",
				AuditObject.of("identity.account", accountId)));
	}

	/**
	 * ACC-2's GitHub row, read from the database.
	 * @throws ApiException {@code NOT_FOUND} for an Administrator or an inactive account
	 */
	@Transactional(readOnly = true)
	Status status(UUID accountId) {
		Account account = this.accounts.findById(accountId)
			.filter(found -> found.kind() == Account.Kind.USER && found.isActive())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		boolean linked = account.githubUserId() != null;
		return new Status(linked, account.githubLogin(), account.githubAvatarUrl(),
				linked ? this.accounts.gitHubLinkedAt(accountId).orElse(null) : null, linked && this.renewals.asked(accountId),
				this.accounts.customerToken(accountId).isPresent(), linked ? blockers(accountId) : List.of());
	}

	/** Every participant's blockers, in bean order. */
	List<GitHubLinkBlocker> blockers(UUID accountId) {
		return this.participants.orderedStream().flatMap(participant -> participant.githubLinkBlockers(accountId).stream())
			.toList();
	}

	@Override
	@Transactional
	public void forgetRepositoryToken(UUID accountId) {
		if (!keepsRepositoryToken(accountId)) {
			Optional<TokenCipher.Sealed> token = this.accounts.customerToken(accountId);
			this.accounts.deleteCustomerToken(accountId);
			token.ifPresent(sealed -> revokeLater(accountId, sealed));
			// Nothing of the account's is read with a token any more, so nothing asks it to re-link.
			this.renewals.renewed(accountId);
		}
	}

	/** Whether a participant reads the account's repositories with its kept token: a live Product's owner. */
	boolean keepsRepositoryToken(UUID accountId) {
		return this.participants.orderedStream().anyMatch(participant -> participant.keepsRepositoryToken(accountId));
	}

	/** The token is revoked alone on GitHub, as an operation that carries it sealed ({@link OAuthGrantRevocation}). */
	private void revokeLater(UUID accountId, TokenCipher.Sealed sealed) {
		this.operations.request(new OperationRequest(OAuthGrantRevocation.TYPE, this.ids.newId(),
				Map.of("accountId", accountId.toString(), "revoke", OAuthGrantRevocation.TOKEN),
				Map.of("keyVersion", sealed.keyVersion(), "nonce", Base64.getEncoder().encodeToString(sealed.nonce()),
						"ciphertext", Base64.getEncoder().encodeToString(sealed.ciphertext())),
				accountId, 0));
	}

	/** A first Product's registration keeps it, and so does a participant that reads with it (AD-10). */
	private boolean keepsToken(UUID accountId, Purpose purpose) {
		return purpose == Purpose.PRODUCT || keepsRepositoryToken(accountId);
	}

	/** A blocker as the refusal's {@code blockers} lists it. */
	static Map<String, Object> blocker(GitHubLinkBlocker blocker) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("module", blocker.module());
		row.put("reason", blocker.reason());
		row.put("subjectId", blocker.subjectId().toString());
		row.put("name", blocker.name());
		return row;
	}

}
