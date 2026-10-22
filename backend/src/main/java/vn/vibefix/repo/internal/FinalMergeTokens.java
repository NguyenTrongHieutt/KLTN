package vn.vibefix.repo.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.MergeApp;
import vn.vibefix.github.MergeApp.Installation;
import vn.vibefix.github.MergeApp.SelectedRepository;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.repo.internal.FinalMergeAuthorizations.Authorization;

/**
 * The gate before each Final Merge token (AD-10, NFR-5): the authorization's pair must be {@code BOUND},
 * and every repository in the installation's fresh selection must have an open authorization or one that
 * was bound to this installation and has ended. Otherwise no token is minted and the installation's
 * Customers hear it ({@value #WIDENED}). A token comes from the pair's installation, narrowed to the
 * pair's repository alone, so the Merge App can write nowhere else. Story 8.8's merge mints through here.
 */
@Component
class FinalMergeTokens {

	static final String WIDENED = "repo.merge_app_selection_widened";

	private final MergeApp app;

	private final FinalMergeAuthorizations authorizations;

	private final JdbcClient jdbc;

	private final Notifications notifications;

	private final TransactionTemplate transactions;

	FinalMergeTokens(MergeApp app, FinalMergeAuthorizations authorizations, JdbcClient jdbc, Notifications notifications,
			TransactionTemplate transactions) {
		this.app = app;
		this.authorizations = authorizations;
		this.jdbc = jdbc;
		this.notifications = notifications;
		this.transactions = transactions;
	}

	/**
	 * @return the token, or empty when the gate refuses it
	 * @throws vn.vibefix.github.GitHubException when GitHub fails
	 */
	Optional<InstallationToken> mint(UUID authorizationId, Map<String, String> permissions) {
		Authorization authorization = this.authorizations.find(authorizationId).orElse(null);
		if (authorization == null || !"BOUND".equals(authorization.status()) || authorization.installationId() == null) {
			return Optional.empty();
		}
		long installationId = authorization.installationId();
		Installation installation = this.app.installation(installationId).orElse(null);
		if (installation == null || installation.suspended()) {
			return Optional.empty();
		}
		boolean allowed = !installation.all()
				&& installation.repositories().stream().anyMatch(repository -> repository.id() == authorization.repositoryId())
				&& installation.repositories().stream().allMatch(repository -> allowed(repository, installationId));
		if (!allowed) {
			this.transactions.executeWithoutResult(status -> widened(installationId));
			return Optional.empty();
		}
		return Optional.of(this.app.token(installationId, authorization.repositoryId(), permissions));
	}

	private boolean allowed(SelectedRepository repository, long installationId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM repo.final_merge_authorization WHERE repository_github_id = :repository
				    AND (status IN ('REQUESTED', 'BOUND') OR (status IN ('USED', 'ENDED') AND installation_id = :installation)))
				""").param("repository", repository.id()).param("installation", installationId).query(Boolean.class).single();
	}

	/** Every open authorization on the installation: its Customer is asked to select only their repository. */
	private void widened(long installationId) {
		List<Authorization> bound = this.authorizations.inStatus("BOUND")
			.stream()
			.filter(found -> found.installationId() != null && found.installationId() == installationId)
			.toList();
		for (Authorization authorization : bound) {
			if (authorization.customerId() != null) {
				this.notifications.send(new NotificationRequest(WIDENED,
						FinalMergeAuthorizations.cause(authorization.id(), "widened:" + installationId),
						List.of(new Recipient(authorization.customerId(), Audience.CUSTOMER)),
						Map.of("repo", authorization.repositoryName()), FinalMergeAuthorizations.link(authorization.jobId()),
						authorization.jobId(), null));
			}
		}
	}

}
