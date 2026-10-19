package vn.vibefix.product.internal;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.identity.CustomerRepositories.CustomerView;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * CUS-3 step 2's live checks (FR-7, AD-13), in the order the Customer reads them: a GitHub
 * address, found, public, administered by the Customer, not linked yet, no LFS or submodules, and
 * at most 250 MB. Organization-owned repositories pass on {@code permissions.admin} (Spike S1,
 * 2026-10-19). A refusal past "found" carries the repository's name in the details.
 */
@Component
class RepositoryChecks {

	/** 250 MB in GitHub's kilobytes (AD-13). */
	static final long SIZE_LIMIT_KB = 250L * 1024;

	private final CustomerRepositories repositories;

	private final JdbcClient jdbc;

	RepositoryChecks(CustomerRepositories repositories, JdbcClient jdbc) {
		this.repositories = repositories;
		this.jdbc = jdbc;
	}

	/** @return the repository, when every check passes */
	RepositoryMetadata check(UUID customerId, String url) {
		RepositoryUrl address = RepositoryUrl.parse(url).orElseThrow(() -> refuse(ProductReason.NOT_GITHUB, null));
		CustomerView view;
		try {
			view = this.repositories.read(customerId, address.owner(), address.name());
		}
		catch (GitHubException ex) {
			throw refuse(ProductReason.GITHUB_UNAVAILABLE, null);
		}
		RepositoryMetadata repository = view.repository().orElseThrow(() -> refuse(ProductReason.REPO_NOT_FOUND, null));
		if (!repository.publicRepository()) {
			throw refuse(ProductReason.REPO_PRIVATE, repository);
		}
		boolean admin = switch (repository.ownerType()) {
			case "User" -> repository.ownerId() == view.customerGithubId();
			case "Organization" -> Boolean.TRUE.equals(repository.admin());
			default -> false;
		};
		if (!admin) {
			throw refuse(ProductReason.NO_ADMIN_PERMISSION, repository);
		}
		boolean linked = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM product.product WHERE repository_github_id = :id AND deleted_at IS NULL)
				""").param("id", repository.id()).query(Boolean.class).single();
		if (linked) {
			throw refuse(ProductReason.REPO_ALREADY_LINKED, repository);
		}
		if (repository.lfs() || repository.submodules()) {
			throw refuse(ProductReason.REPO_UNSUPPORTED_CONTENT, repository);
		}
		if (repository.sizeKb() > SIZE_LIMIT_KB) {
			throw refuse(ProductReason.REPO_TOO_LARGE, repository);
		}
		return repository;
	}

	static ApiException refuse(ProductReason reason, RepositoryMetadata repository) {
		return repository == null ? new ApiException(ErrorCode.GUARD_FAILED, reason)
				: new ApiException(ErrorCode.GUARD_FAILED, reason,
						Map.of("repository", repository.fullName(), "repositoryUrl", repository.htmlUrl()));
	}

}
