package vn.vibefix.product.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRepositories;
import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.github.SourceApp;
import vn.vibefix.github.SourceApp.WorkflowVerdict;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.internal.SourceAppBindings.Bound;
import vn.vibefix.product.internal.SourceAppBindings.Checked;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link ProductSources} through the Source App (AD-10, AD-13, AD-45, Story 23.5), with registration's rules: the
 * Product's binding holds the selection rule on a fresh read (and is bound again to a reinstalled App), then one
 * token narrowed to the repository reads it, must find the registered GitHub ID, the Customer's linked account with
 * admin through the collaborator-permission endpoint, and a published {@code vibefix.yml} on its default branch. The
 * token is revoked after the reads. The contributor read (FR-13, FR-81) and the drift read (FR-20, R7) take the
 * binding the same way (Story 23.5b); the contributor read answers "unknown" rather than throwing, and the drift read
 * reads no admin right. No path reads a repository with the Customer's linking token. A GitHub failure is a
 * {@link GitHubException}, which the caller retries.
 */
@Component
class SourceChecks implements ProductSources {

	private static final Logger log = LoggerFactory.getLogger(SourceChecks.class);

	private record Row(UUID customerId, String name, String fullName, long githubId, String defaultBranch) {
	}

	/** What the token's reads found: a block, or none with the default branch read now. */
	private record Read(@Nullable Block block, String defaultBranch, String fullName) {
	}

	private final SourceAppBindings bindings;

	private final SourceApp app;

	private final GitHubRepositories repositories;

	private final CustomerRepositories customers;

	private final JdbcClient jdbc;

	SourceChecks(SourceAppBindings bindings, SourceApp app, GitHubRepositories repositories, CustomerRepositories customers,
			JdbcClient jdbc) {
		this.bindings = bindings;
		this.app = app;
		this.repositories = repositories;
		this.customers = customers;
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Registered> registered(UUID productId) {
		return this.jdbc.sql("""
				SELECT customer_account_id, repository_github_id, repository_full_name FROM product.product
				WHERE id = :id AND deleted_at IS NULL
				""").param("id", productId).query((rs, n) -> new Registered(productId, rs.getObject("customer_account_id", UUID.class),
				rs.getLong("repository_github_id"), rs.getString("repository_full_name"))).optional();
	}

	@Override
	@Transactional
	public @Nullable Block reconfirm(UUID productId, String newFullName) {
		Row product = row(productId);
		if (newFullName.split("/", 2).length != 2) {
			return Block.SOURCE_APP_WRONG_REPOSITORY;
		}
		Read read = read(productId, product, newFullName);
		if (read.block() != null) {
			return read.block();
		}
		this.jdbc.sql("UPDATE product.product SET repository_full_name = :name WHERE id = :id")
			.param("name", read.fullName())
			.param("id", productId)
			.update();
		return null;
	}

	@Override
	public SourceCheck check(UUID productId) {
		Row product = row(productId);
		Read read = read(productId, product, product.fullName());
		return new SourceCheck(read.block(), productId, product.customerId(), product.name(), product.fullName(),
				product.githubId(), read.block() == null ? read.defaultBranch() : product.defaultBranch());
	}

	@Override
	public Optional<Set<Long>> contributors(UUID productId) {
		try {
			Row product = row(productId);
			Checked checked = this.bindings.forProduct(product.customerId(), productId, product.githubId(),
					product.fullName());
			if (checked.refusal() != null) {
				log.warn("The contributors of Product {} are unknown: the Source App binding does not hold ({})", productId,
						checked.refusal());
				return Optional.empty();
			}
			Bound bound = checked.bound();
			String[] name = bound.repositoryFullName().split("/", 2);
			return Optional.of(Set.copyOf(this.app.withToken(bound.installationId(), bound.repositoryId(),
					SourceAppBindings.METADATA_READ, token -> this.repositories.contributorIds(token.value(), name[0], name[1]))));
		}
		catch (ApiException missing) {
			log.warn("The contributors of Product {} are unknown: no such Product ({})", productId, missing.code());
			return Optional.empty();
		}
		catch (GitHubException unknown) {
			// GitHub refusing or unreachable, or the App not configured: unknown, so the guard fails closed. Only the status
			// is logged, never a token or a body.
			log.warn("The contributors of Product {} are unknown: GitHub answered HTTP {} (0: not reached, or the Source "
					+ "App is not configured)", productId, unknown.status());
			return Optional.empty();
		}
	}

	@Override
	public Drift drift(UUID productId, String copiedSha) {
		Row product = row(productId);
		Checked checked = this.bindings.forProduct(product.customerId(), productId, product.githubId(), product.fullName());
		if (checked.refusal() != null) {
			return new Drift(Block.valueOf(checked.refusal().name()), null, product.fullName(), false, List.of());
		}
		Bound bound = checked.bound();
		return this.app.withToken(bound.installationId(), bound.repositoryId(), SourceAppBindings.READ,
				token -> drift(product, bound, token, copiedSha));
	}

	/**
	 * The repository, its workflow and its new commits, with one token: a workflow that differs is read next to the
	 * rest, so no other change waits for it. The admin right is no drift.
	 */
	private Drift drift(Row product, Bound bound, InstallationToken token, String copiedSha) {
		String[] name = bound.repositoryFullName().split("/", 2);
		RepositoryMetadata repository = this.repositories.read(token.value(), name[0], name[1]).orElse(null);
		if (repository == null || repository.id() != product.githubId()) {
			return new Drift(Block.SOURCE_APP_WRONG_REPOSITORY, null, product.fullName(), false, List.of());
		}
		Block workflow = switch (this.app.workflow(token, repository.fullName(), repository.defaultBranch()).verdict()) {
			case MATCHES -> null;
			case MISSING -> Block.WORKFLOW_MISSING;
			case MISMATCH -> Block.WORKFLOW_MISMATCH;
		};
		List<String> commits = this.repositories.commitsSince(token.value(), repository.fullName(), copiedSha,
				repository.defaultBranch());
		List<String> kept = commits.size() > MAX_DRIFT_COMMITS ? commits.subList(commits.size() - MAX_DRIFT_COMMITS,
				commits.size()) : commits;
		return new Drift(null, workflow, repository.fullName(), repository.archived(), kept);
	}

	/** The binding on a fresh read under {@code fullName}, then the repository, the admin right, and the workflow. */
	private Read read(UUID productId, Row product, String fullName) {
		Checked checked = this.bindings.forProduct(product.customerId(), productId, product.githubId(), fullName);
		if (checked.refusal() != null) {
			return new Read(Block.valueOf(checked.refusal().name()), product.defaultBranch(), fullName);
		}
		Bound bound = checked.bound();
		return this.app.withToken(bound.installationId(), bound.repositoryId(), SourceAppBindings.READ,
				token -> read(product, bound, token));
	}

	private Read read(Row product, Bound bound, InstallationToken token) {
		String[] name = bound.repositoryFullName().split("/", 2);
		RepositoryMetadata repository = this.repositories.read(token.value(), name[0], name[1]).orElse(null);
		if (repository == null || repository.id() != product.githubId()) {
			return new Read(Block.SOURCE_APP_WRONG_REPOSITORY, product.defaultBranch(), bound.repositoryFullName());
		}
		if (!RepositoryChecks.administers(this.customers, this.repositories, product.customerId(), token,
				repository.fullName())) {
			return new Read(Block.NO_ADMIN_PERMISSION, repository.defaultBranch(), repository.fullName());
		}
		WorkflowVerdict workflow = this.app.workflow(token, repository.fullName(), repository.defaultBranch()).verdict();
		Block block = switch (workflow) {
			case MATCHES -> null;
			case MISSING -> Block.WORKFLOW_MISSING;
			case MISMATCH -> Block.WORKFLOW_MISMATCH;
		};
		return new Read(block, repository.defaultBranch(), repository.fullName());
	}

	private Row row(UUID productId) {
		return this.jdbc.sql("""
				SELECT customer_account_id, name, repository_full_name, repository_github_id, repository_default_branch
				FROM product.product WHERE id = :id AND deleted_at IS NULL
				""")
			.param("id", productId)
			.query((rs, n) -> new Row(rs.getObject("customer_account_id", UUID.class), rs.getString("name"),
					rs.getString("repository_full_name"), rs.getLong("repository_github_id"),
					rs.getString("repository_default_branch")))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
