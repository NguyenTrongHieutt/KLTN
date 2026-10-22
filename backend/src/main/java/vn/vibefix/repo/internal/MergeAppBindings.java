package vn.vibefix.repo.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.MergeApp;
import vn.vibefix.github.MergeApp.InstallRequest;
import vn.vibefix.github.MergeApp.Installation;
import vn.vibefix.github.MergeApp.SelectedRepository;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;

/**
 * Binding Final Merge authorizations to Merge App installations (AD-10). GitHub allows one installation
 * per account, so an authorization binds to the pair (installation, repository). Each installation is
 * re-read with the App's JWT first, outside any transaction; then, under the account's advisory lock:
 * <ul>
 * <li>a selection of every repository, or a selected repository the account does not own or with no
 * authorization {@code REQUESTED} or {@code BOUND} to this installation, is refused
 * ({@code MERGE_APP_WRONG_REPOSITORY}) and binds nothing;</li>
 * <li>a setup return's {@code state} binds its authorization, whose repository must be selected;</li>
 * <li>an event without one binds each {@code REQUESTED} authorization in the selection whose Customer is
 * the event's requester, else its sender, and tells them ({@value #AUTHORIZED});</li>
 * <li>an installation {@code REMOVING} or {@code REMOVED} binds nothing.</li>
 * </ul>
 * Deleting, suspending, or deselecting returns the affected {@code BOUND} authorizations to
 * {@code REQUESTED}. Install requests are matched by requester and account; one no longer listed with
 * nothing bound counts as refused ({@value #REQUEST_REFUSED}). The daily reconciliation binds an unbound
 * pair on the Customer's own account, then removes the installations no open authorization needs.
 */
@Component
class MergeAppBindings {

	static final String AUTHORIZED = "repo.final_merge_authorized";

	static final String REQUEST_REFUSED = "repo.merge_app_request_refused";

	static final String WRONG_REPOSITORY = "MERGE_APP_WRONG_REPOSITORY";

	private static final Logger log = LoggerFactory.getLogger(MergeAppBindings.class);

	/** How a setup return ended. */
	enum Result {

		BOUND, REFUSED, UNKNOWN

	}

	private final MergeApp app;

	private final JdbcClient jdbc;

	private final FinalMergeAuthorizations authorizations;

	private final CustomerRepositories customers;

	private final Notifications notifications;

	private final TransactionTemplate transactions;

	private final Clock clock;

	/** Looked up on use: a bind in Closing starts the Final Merge's check (Story 8.8). */
	private final ObjectProvider<FinalMerges> merges;

	MergeAppBindings(MergeApp app, JdbcClient jdbc, FinalMergeAuthorizations authorizations, CustomerRepositories customers,
			Notifications notifications, TransactionTemplate transactions, Clock clock, ObjectProvider<FinalMerges> merges) {
		this.merges = merges;
		this.app = app;
		this.jdbc = jdbc;
		this.authorizations = authorizations;
		this.customers = customers;
		this.notifications = notifications;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** A setup return with a valid {@code state}: GitHub first, then the checks and the bind. */
	Result bindReturn(Authorization named, long installationId) {
		Installation installation = this.app.installation(installationId).orElse(null);
		if (installation == null) {
			return Result.UNKNOWN;
		}
		return Objects.requireNonNull(this.transactions.execute(status -> apply(installation, named, null, true)));
	}

	/** An {@code installation} or {@code installation_repositories} event: no state, so the actor must be the Customer. */
	void bindEvent(long installationId, @Nullable Long actorId) {
		Installation installation = this.app.installation(installationId).orElse(null);
		if (installation == null) {
			return;
		}
		this.transactions.executeWithoutResult(status -> apply(installation, null, actorId, true));
	}

	/**
	 * Deleted, suspended, or deselected: each affected {@code BOUND} authorization returns to
	 * {@code REQUESTED}, in the caller's transaction.
	 * @param repositories the deselected repositories, or {@code null} for all of the installation's
	 */
	void unbind(long installationId, @Nullable List<Long> repositories, boolean deleted) {
		String unbind = """
				UPDATE repo.final_merge_authorization SET status = 'REQUESTED', bound_at = NULL
				WHERE installation_id = :installation AND status = 'BOUND'
				""";
		if (repositories == null) {
			this.jdbc.sql(unbind).param("installation", installationId).update();
		}
		else if (!repositories.isEmpty()) {
			this.jdbc.sql(unbind + " AND repository_github_id IN (:repositories)")
				.param("installation", installationId)
				.param("repositories", repositories)
				.update();
		}
		if (deleted) {
			this.jdbc.sql("""
					UPDATE repo.merge_installation SET status = 'REMOVED', removed_at = :now
					WHERE installation_id = :installation AND status <> 'REMOVED'
					""").param("now", Timestamp.from(this.clock.instant())).param("installation", installationId).update();
		}
	}

	/**
	 * The install requests (SG-21) for these {@code REQUESTED} authorizations: a listed one is recorded
	 * with its organization; one seen before and no longer listed, with nothing bound, counts as refused.
	 * A GitHub failure changes nothing.
	 */
	void readRequests(List<Authorization> requested) {
		if (requested.isEmpty()) {
			return;
		}
		List<InstallRequest> requests;
		try {
			requests = this.app.requests();
		}
		catch (GitHubException unavailable) {
			log.warn("The Merge App's install requests were not read: {}", unavailable.getMessage());
			return;
		}
		this.transactions.executeWithoutResult(status -> {
			for (Authorization authorization : requested) {
				Long customer = authorization.customerId() == null ? null
						: this.customers.githubUserId(authorization.customerId()).orElse(null);
				InstallRequest match = requests.stream()
					.filter(request -> customer != null && request.requesterId() == customer
							&& request.accountLogin().equalsIgnoreCase(authorization.owner()))
					.findFirst()
					.orElse(null);
				if (match != null) {
					this.jdbc.sql("""
							UPDATE repo.final_merge_authorization SET request_org = :org,
							    request_seen_at = coalesce(request_seen_at, :now), request_refused_at = NULL
							WHERE id = :id AND status = 'REQUESTED'
							""").param("org", match.accountLogin()).param("now", now()).param("id", authorization.id()).update();
				}
				else if (authorization.requestSeenAt() != null) {
					refused(authorization);
				}
			}
		});
	}

	/**
	 * The daily reconciliation (AD-10): the install requests first, then each known installation re-read,
	 * an unbound pair on the Customer's own account bound, and an installation no open authorization
	 * needs removed.
	 */
	void reconcile() {
		readRequests(this.authorizations.inStatus("REQUESTED"));
		List<Long> installations = this.jdbc.sql("SELECT installation_id FROM repo.merge_installation WHERE status = 'ACTIVE'")
			.query(Long.class)
			.list();
		for (long id : installations) {
			try {
				Installation installation = this.app.installation(id).orElse(null);
				if (installation == null) {
					this.transactions.executeWithoutResult(status -> unbind(id, null, true));
					continue;
				}
				this.transactions
					.executeWithoutResult(status -> apply(installation, null, installation.accountId(), false));
				removeIfUnneeded(installation);
			}
			catch (GitHubException unavailable) {
				log.warn("Merge App installation {} was not reconciled: {}", id, unavailable.getMessage());
			}
		}
	}

	/** Removes the installation when nothing open needs it: marked {@code REMOVING} under the lock first. */
	void removeIfUnneeded(Installation installation) {
		Boolean removing = this.transactions.execute(status -> {
			lock(installation.accountId());
			if (needed(installation)) {
				return false;
			}
			return this.jdbc.sql("""
					UPDATE repo.merge_installation SET status = 'REMOVING' WHERE installation_id = :id AND status = 'ACTIVE'
					""").param("id", installation.id()).update() == 1;
		});
		if (!Boolean.TRUE.equals(removing)) {
			return;
		}
		this.app.remove(installation.id());
		this.transactions.executeWithoutResult(status -> this.jdbc.sql("""
				UPDATE repo.merge_installation SET status = 'REMOVED', removed_at = :now WHERE installation_id = :id
				""").param("now", now()).param("id", installation.id()).update());
	}

	/** AD-10's checks and the bind, in the caller's transaction. */
	private Result apply(Installation installation, @Nullable Authorization named, @Nullable Long actorId, boolean record) {
		lock(installation.accountId());
		String status = this.jdbc.sql("SELECT status FROM repo.merge_installation WHERE installation_id = :id")
			.param("id", installation.id())
			.query(String.class)
			.optional()
			.orElse(null);
		if ("REMOVING".equals(status) || "REMOVED".equals(status)) {
			return Result.UNKNOWN;
		}
		this.jdbc.sql("""
				INSERT INTO repo.merge_installation (installation_id, account_github_id, account_login, status, seen_at)
				VALUES (:id, :account, :login, 'ACTIVE', :now)
				ON CONFLICT (installation_id) DO UPDATE SET account_login = :login, seen_at = :now
				""").param("id", installation.id()).param("account", installation.accountId())
			.param("login", installation.accountLogin()).param("now", now()).update();
		if (installation.suspended()) {
			return Result.UNKNOWN;
		}
		Set<Long> selected = installation.repositories().stream().map(SelectedRepository::id).collect(Collectors.toSet());
		List<Authorization> targets = new ArrayList<>();
		if (named != null) {
			this.authorizations.find(named.id()).filter(Authorization::open).ifPresent(targets::add);
		}
		else if (actorId != null) {
			for (Authorization requested : this.authorizations.inStatus("REQUESTED")) {
				if (selected.contains(requested.repositoryId()) && requested.customerId() != null
						&& this.customers.githubUserId(requested.customerId()).filter(actorId::equals).isPresent()) {
					targets.add(requested);
				}
			}
		}
		if (targets.isEmpty()) {
			return Result.UNKNOWN;
		}
		boolean wrong = installation.all() || installation.repositories()
			.stream()
			.anyMatch(repository -> repository.ownerId() != installation.accountId() || !acceptable(repository, installation))
				|| targets.stream().anyMatch(target -> !selected.contains(target.repositoryId()));
		if (wrong) {
			if (record) {
				targets.forEach(target -> this.jdbc.sql("""
						UPDATE repo.final_merge_authorization SET refusal = :refusal, refused_at = :now WHERE id = :id
						""").param("refusal", WRONG_REPOSITORY).param("now", now()).param("id", target.id()).update());
			}
			return Result.REFUSED;
		}
		for (Authorization target : targets) {
			int bound = this.jdbc.sql("""
					UPDATE repo.final_merge_authorization SET status = 'BOUND', installation_id = :installation, bound_at = :now,
					    refusal = NULL, refused_at = NULL, request_seen_at = NULL, request_refused_at = NULL
					WHERE id = :id AND status = 'REQUESTED'
					""").param("installation", installation.id()).param("now", now()).param("id", target.id()).update();
			if (bound == 1) {
				this.authorizations.audit("repo.final_merge_bound", target.id(), target.jobId());
				this.merges.getObject().prepare(target.jobId());
				if (named == null && target.customerId() != null) {
					Authorization now = this.authorizations.find(target.id()).orElseThrow();
					this.notifications.send(new NotificationRequest(AUTHORIZED,
							FinalMergeAuthorizations.cause(target.id(), "bound:" + installation.id()),
							List.of(new Recipient(target.customerId(), Audience.CUSTOMER)),
							Map.of("org", installation.accountLogin(), "repo", target.repositoryName(), "date",
									FinalMergeAuthorizations.dayMonth(now.deadlineAt())),
							FinalMergeAuthorizations.link(target.jobId()), target.jobId(), null));
				}
			}
		}
		return Result.BOUND;
	}

	/** A selected repository with an authorization {@code REQUESTED}, or {@code BOUND} to this installation. */
	private boolean acceptable(SelectedRepository repository, Installation installation) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM repo.final_merge_authorization WHERE repository_github_id = :repository
				    AND (status = 'REQUESTED' OR (status = 'BOUND' AND installation_id = :installation)))
				""").param("repository", repository.id()).param("installation", installation.id()).query(Boolean.class).single();
	}

	/** Whether a {@code BOUND} authorization is on it, or a {@code REQUESTED} one covers a selected repository. */
	private boolean needed(Installation installation) {
		List<Long> selected = installation.repositories().stream().map(SelectedRepository::id).toList();
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM repo.final_merge_authorization
				    WHERE (status = 'BOUND' AND installation_id = :installation)
				       OR (status = 'REQUESTED' AND repository_github_id IN (:selected)))
				""").param("installation", installation.id()).param("selected", selected.isEmpty() ? List.of(-1L) : selected)
			.query(Boolean.class)
			.single();
	}

	private void refused(Authorization authorization) {
		this.jdbc.sql("""
				UPDATE repo.final_merge_authorization SET request_seen_at = NULL, request_refused_at = :now
				WHERE id = :id AND status = 'REQUESTED'
				""").param("now", now()).param("id", authorization.id()).update();
		UUID customer = authorization.customerId();
		if (customer != null) {
			this.notifications.send(new NotificationRequest(REQUEST_REFUSED,
					FinalMergeAuthorizations.cause(authorization.id(), "refused:" + authorization.requestSeenAt()),
					List.of(new Recipient(customer, Audience.CUSTOMER)),
					Map.of("repo", authorization.repositoryName(), "date",
							FinalMergeAuthorizations.dayMonth(authorization.deadlineAt())),
					FinalMergeAuthorizations.link(authorization.jobId()), authorization.jobId(), null));
		}
	}

	/** AD-4's Merge App account lock: binding and removal on one account wait for each other. */
	private void lock(long accountId) {
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "repo.merge_account:" + accountId)
			.query((rs, n) -> 1)
			.list();
	}

	private Timestamp now() {
		return Timestamp.from(this.clock.instant());
	}

	/** For the token gate: the installation's authorizations, open or bound to it before. */
	Optional<Installation> read(long installationId) {
		return this.app.installation(installationId);
	}

}
