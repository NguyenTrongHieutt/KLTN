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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.GitHubLinkBlocker;
import vn.vibefix.identity.GitHubLinkParticipant;
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
import vn.vibefix.shared.Kind;

/**
 * The rules of an account's GitHub link (FR-80, R14, R31, AD-10, AD-19, AD-24). One GitHub account links one
 * vibefix account, by its numeric ID, and nothing is ever merged; a linked account re-links only the same ID. The
 * link keeps identity only: the token GitHub issues is used once in the callback to read who the person is, like
 * Google's, and never stored; the callback has the whole OAuth grant revoked on GitHub ({@link #revokeGrant},
 * {@link OAuthGrantRevocation}), so a link made since Story 23.5d leaves vibefix authorized on nobody's GitHub
 * account (SG-9). Unlinking waits until no participant reports a blocker; it
 * removes the public facts at once, while the person-link signals stay under AD-24's retention. Every change is
 * audited.
 */
@Component
class GitHubLinksImpl {

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

	/** ACC-2's GitHub row. */
	record Status(boolean linked, @Nullable String login, @Nullable String avatarUrl, @Nullable Instant linkedAt,
			List<GitHubLinkBlocker> blockers) {
	}

	private final Accounts accounts;

	private final TokenCipher cipher;

	private final Todos todos;

	private final JdbcPersonSignals signals;

	private final ExternalOperations operations;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final ObjectProvider<GitHubLinkParticipant> participants;

	GitHubLinksImpl(Accounts accounts, TokenCipher cipher, Todos todos, JdbcPersonSignals signals,
			ExternalOperations operations, AuditLog auditLog, IdGenerator ids, Clock clock,
			ObjectProvider<GitHubLinkParticipant> participants) {
		this.accounts = accounts;
		this.cipher = cipher;
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
	 * verified email (AD-19); the {@code identity.link_github} to-do closes. No token is taken or stored: the
	 * callback has already requested its grant's revocation ({@link #revokeGrant}) in a transaction of its own,
	 * so nothing that happens here can undo it.
	 * @throws org.springframework.dao.DuplicateKeyException when another account linked the same ID in
	 * between; nothing is written
	 */
	@Transactional
	Outcome link(UUID accountId, LinkedUser user) {
		Account account = this.accounts.lockForLink(accountId)
			.filter(found -> found.kind() == Kind.USER && found.isActive())
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
		this.todos.close(SignIns.LINK_GITHUB, accountId, accountId);
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), relink ? "identity.github_relinked" : "identity.github_linked",
				AuditObject.of("identity.account", accountId)));
		return relink ? Outcome.RELINKED : Outcome.LINKED;
	}

	/**
	 * Revokes the whole OAuth grant the token came with on GitHub (R31, SG-9), as an operation that carries the
	 * token sealed with the account ID as associated data and purges it when it completes
	 * ({@link OAuthGrantRevocation}); retried by that operation. The callback requests it exactly once, as soon as
	 * GitHub issued a token and before {@link #link}, in a transaction of its own, so a user read that failed or a
	 * link that refuses or throws never loses it. Nothing else keeps the token.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void revokeGrant(UUID accountId, String token) {
		TokenCipher.Sealed sealed = this.cipher.seal(token, accountId.toString());
		this.operations.request(new OperationRequest(OAuthGrantRevocation.TYPE, this.ids.newId(),
				Map.of("accountId", accountId.toString()),
				Map.of("keyVersion", sealed.keyVersion(), "nonce", Base64.getEncoder().encodeToString(sealed.nonce()),
						"ciphertext", Base64.getEncoder().encodeToString(sealed.ciphertext())),
				accountId, 0));
	}

	/**
	 * "Bỏ liên kết" (FR-80, R14): the GitHub columns are cleared and {@code identity.link_github} opens again.
	 * Nothing is revoked on GitHub: the grant was revoked when the account linked. The person-link signals stay
	 * (AD-24).
	 * @throws ApiException {@code NOT_FOUND} for an account that is not an active person's or has no link;
	 * {@code GUARD_FAILED GITHUB_LINK_IN_USE} with the {@code blockers}; {@code CONFLICT} when GitHub is the
	 * only way the account is known (an account from before Google sign-in)
	 */
	@Transactional
	void unlink(UUID accountId) {
		Account account = this.accounts.lockForLink(accountId)
			.filter(found -> found.kind() == Kind.USER && found.isActive() && found.githubUserId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<GitHubLinkBlocker> blockers = blockers(accountId);
		if (!blockers.isEmpty()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.GITHUB_LINK_IN_USE,
					Map.of("blockers", blockers.stream().map(GitHubLinksImpl::blocker).toList()));
		}
		if (account.googleSub() == null) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		this.accounts.unlinkGitHub(accountId);
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
			.filter(found -> found.kind() == Kind.USER && found.isActive())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		boolean linked = account.githubUserId() != null;
		return new Status(linked, account.githubLogin(), account.githubAvatarUrl(),
				linked ? this.accounts.gitHubLinkedAt(accountId).orElse(null) : null,
				linked ? blockers(accountId) : List.of());
	}

	/** Every participant's blockers, in bean order. */
	List<GitHubLinkBlocker> blockers(UUID accountId) {
		return this.participants.orderedStream().flatMap(participant -> participant.githubLinkBlockers(accountId).stream())
			.toList();
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
