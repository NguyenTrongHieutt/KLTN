package vn.vibefix.product.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.identity.CustomerRepositories.CustomerView;
import vn.vibefix.product.ProductSources;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link ProductSources} with the same rules as registration (AD-13): a user-owned repository passes on
 * its owner ID, an organization-owned one on {@code permissions.admin}. The repository is read by the
 * name it was registered with and must still carry the registered GitHub ID. A missing or refused token
 * blocks the start; {@code identity} has asked the Customer to sign in again.
 */
@Component
class SourceChecks implements ProductSources {

	private record Registered(UUID customerId, String name, String fullName, long githubId, String defaultBranch) {
	}

	private final CustomerRepositories repositories;

	private final JdbcClient jdbc;

	SourceChecks(CustomerRepositories repositories, JdbcClient jdbc) {
		this.repositories = repositories;
		this.jdbc = jdbc;
	}

	@Override
	public SourceCheck check(UUID productId) {
		Registered product = this.jdbc.sql("""
				SELECT customer_account_id, name, repository_full_name, repository_github_id, repository_default_branch
				FROM product.product WHERE id = :id AND deleted_at IS NULL
				""")
			.param("id", productId)
			.query((rs, n) -> new Registered(rs.getObject("customer_account_id", UUID.class), rs.getString("name"),
					rs.getString("repository_full_name"), rs.getLong("repository_github_id"),
					rs.getString("repository_default_branch")))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		String[] owner = product.fullName().split("/", 2);
		CustomerView view;
		try {
			view = this.repositories.read(product.customerId(), owner[0], owner[1]);
		}
		catch (ApiException noToken) {
			return result(Block.SIGN_IN_AGAIN, productId, product, product.defaultBranch());
		}
		catch (GitHubException refused) {
			if (refused.status() == 401) {
				return result(Block.SIGN_IN_AGAIN, productId, product, product.defaultBranch());
			}
			throw refused;
		}
		RepositoryMetadata repository = view.repository().orElse(null);
		if (repository == null || !repository.publicRepository() || repository.id() != product.githubId()) {
			return result(Block.SOURCE_NOT_PUBLIC, productId, product, product.defaultBranch());
		}
		boolean admin = switch (repository.ownerType()) {
			case "User" -> repository.ownerId() == view.customerGithubId();
			case "Organization" -> Boolean.TRUE.equals(repository.admin());
			default -> false;
		};
		return result(admin ? null : Block.NO_ADMIN_PERMISSION, productId, product, repository.defaultBranch());
	}

	private static SourceCheck result(@Nullable Block block, UUID productId, Registered product, String defaultBranch) {
		return new SourceCheck(block, productId, product.customerId(), product.name(), product.fullName(), product.githubId(),
				defaultBranch);
	}

}
