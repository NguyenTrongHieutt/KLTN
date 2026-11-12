package vn.vibefix.product.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.github.GitHubApps.InstallationToken;
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
 * token is revoked after the reads. No path reads a repository with the Customer's linking token. A GitHub failure
 * is a {@link vn.vibefix.github.GitHubException}, which the caller retries.
 */
@Component
class SourceChecks implements ProductSources {

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
