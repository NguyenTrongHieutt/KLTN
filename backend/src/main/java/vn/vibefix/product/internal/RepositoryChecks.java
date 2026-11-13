package vn.vibefix.product.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRepositories;
import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.github.SourceApp;
import vn.vibefix.github.SourceApp.WorkflowCheck;
import vn.vibefix.github.SourceApp.WorkflowVerdict;
import vn.vibefix.identity.GitHubAccountIds;
import vn.vibefix.product.internal.SourceAppBindings.Bound;
import vn.vibefix.product.internal.SourceAppBindings.Found;
import vn.vibefix.product.internal.SourceAppBindings.Lookup;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * CUS-3's live checks (FR-7, AD-13, AD-45, Story 23.5), in the order the Customer reads them: a GitHub address;
 * the Source App ({@link SourceAppBindings}: installed and the selection rule, nothing written); found with a
 * Source App token narrowed to the repository; administered by the Customer's linked GitHub account, read with the
 * collaborator-permission endpoint for its numeric ID resolved to the current login, and only then the draft bound;
 * not linked yet; no LFS or
 * submodules; at most 250 MB; and, for the workflow step and the registration, the published
 * {@code vibefix.yml} on the default branch. Public and private, user-owned and organization-owned repositories pass.
 * The token is revoked after the checks whatever happened. A GitHub failure, or a Source App not configured, answers
 * {@code GITHUB_UNAVAILABLE}. A refusal from the Source App on carries the repository's name in the details.
 */
@Component
class RepositoryChecks {

	/** 250 MB in GitHub's kilobytes (AD-13). */
	static final long SIZE_LIMIT_KB = 250L * 1024;

	/**
	 * Every check passed: the repository as read, and the pair the draft is bound to.
	 * @param workflowBlob the accepted blob the published {@code vibefix.yml} matched, when the workflow was checked
	 * (Story 23.5c: the registration records it)
	 */
	record Passed(RepositoryMetadata repository, Bound bound, @Nullable String workflowBlob) {
	}

	private final SourceAppBindings bindings;

	private final SourceApp app;

	private final GitHubRepositories repositories;

	private final GitHubAccountIds customers;

	private final JdbcClient jdbc;

	RepositoryChecks(SourceAppBindings bindings, SourceApp app, GitHubRepositories repositories,
			GitHubAccountIds customers, JdbcClient jdbc) {
		this.bindings = bindings;
		this.app = app;
		this.repositories = repositories;
		this.customers = customers;
		this.jdbc = jdbc;
	}

	/**
	 * CUS-3 step 2's checks, and with {@code workflow} the registration's too.
	 * @return the repository and the bound pair, when every check passes
	 * @throws ApiException {@code GUARD_FAILED} with the first check that refuses
	 */
	Passed check(UUID customerId, String url, boolean workflow) {
		RepositoryUrl address = address(url);
		Found found = find(customerId, address);
		try {
			return this.app.withToken(found.installationId(), found.repositoryId(), SourceAppBindings.READ, token -> {
				RepositoryMetadata repository = read(token, found);
				admin(customerId, token, repository);
				// Only an administrator's draft is bound (AD-13): nobody keeps a binding of another's repository.
				Bound bound = this.bindings.record(customerId, address.owner() + "/" + address.name(), found);
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
				String blob = workflow ? workflow(token, repository) : null;
				return new Passed(repository, bound, blob);
			});
		}
		catch (GitHubException unavailable) {
			throw refuse(ProductReason.GITHUB_UNAVAILABLE, null);
		}
	}

	/**
	 * CUS-3 step 3's "Kiểm tra": the Source App, the repository found, the admin right, then the published
	 * {@code vibefix.yml} on the default branch.
	 * @throws ApiException {@code GUARD_FAILED} with {@code WORKFLOW_MISSING}, or {@code WORKFLOW_MISMATCH} carrying
	 * {@code lineEndings: true} when the file holds a carriage return; or an earlier check's refusal
	 */
	RepositoryMetadata checkWorkflow(UUID customerId, String url) {
		RepositoryUrl address = address(url);
		Found found = find(customerId, address);
		try {
			return this.app.withToken(found.installationId(), found.repositoryId(), SourceAppBindings.READ, token -> {
				RepositoryMetadata repository = read(token, found);
				admin(customerId, token, repository);
				this.bindings.record(customerId, address.owner() + "/" + address.name(), found);
				workflow(token, repository);
				return repository;
			});
		}
		catch (GitHubException unavailable) {
			throw refuse(ProductReason.GITHUB_UNAVAILABLE, null);
		}
	}

	/** @throws ApiException {@code GUARD_FAILED NOT_GITHUB} for anything but a GitHub repository address */
	static RepositoryUrl address(String url) {
		return RepositoryUrl.parse(url).orElseThrow(() -> refuse(ProductReason.NOT_GITHUB, null));
	}

	/** The Source App checks: the installation that reaches the repository and the selection rule, nothing written. */
	private Found find(UUID customerId, RepositoryUrl address) {
		String fullName = address.owner() + "/" + address.name();
		Lookup lookup;
		try {
			lookup = this.bindings.find(customerId, address.owner(), address.name());
		}
		catch (GitHubException unavailable) {
			throw refuse(ProductReason.GITHUB_UNAVAILABLE, null);
		}
		if (lookup.refusal() != null) {
			throw refuse(lookup.refusal(), fullName, "https://github.com/" + fullName, Map.of());
		}
		return Objects.requireNonNull(lookup.found());
	}

	private RepositoryMetadata read(InstallationToken token, Found found) {
		String[] name = found.repository().fullName().split("/", 2);
		RepositoryMetadata repository = this.repositories.read(token.value(), name[0], name[1]).orElse(null);
		if (repository == null || repository.id() != found.repositoryId()) {
			throw refuse(ProductReason.REPO_NOT_FOUND, null);
		}
		return repository;
	}

	/** AD-13: the Customer's linked numeric GitHub ID, resolved to its current login, has admin on the repository. */
	private void admin(UUID customerId, InstallationToken token, RepositoryMetadata repository) {
		if (!administers(this.customers, this.repositories, customerId, token, repository.fullName())) {
			throw refuse(ProductReason.NO_ADMIN_PERMISSION, repository);
		}
	}

	/** @return the accepted blob the file matched */
	private @Nullable String workflow(InstallationToken token, RepositoryMetadata repository) {
		WorkflowCheck checked = this.app.workflow(token, repository.fullName(), repository.defaultBranch());
		if (checked.verdict() == WorkflowVerdict.MISSING) {
			throw refuse(ProductReason.WORKFLOW_MISSING, repository);
		}
		if (checked.verdict() == WorkflowVerdict.MISMATCH) {
			throw refuse(ProductReason.WORKFLOW_MISMATCH, repository.fullName(), repository.htmlUrl(),
					checked.lineEndings() ? Map.<String, Object>of("lineEndings", true) : Map.<String, Object>of());
		}
		return checked.blob();
	}

	/**
	 * Whether the account linked to {@code customerId} administers {@code fullName} (AD-13): its numeric GitHub ID read
	 * without a GitHub call, its current login read with the token, and the collaborator-permission endpoint.
	 * @throws GitHubException when GitHub cannot answer
	 */
	static boolean administers(GitHubAccountIds customers, GitHubRepositories repositories, UUID customerId,
			InstallationToken token, String fullName) {
		Long githubId = customers.githubUserId(customerId).orElse(null);
		if (githubId == null) {
			return false;
		}
		String login = repositories.login(token.value(), githubId).orElse(null);
		if (login == null) {
			return false;
		}
		String[] name = fullName.split("/", 2);
		return repositories.permission(token.value(), name[0], name[1], login).filter("admin"::equals).isPresent();
	}

	static ApiException refuse(ProductReason reason, @Nullable RepositoryMetadata repository) {
		return repository == null ? new ApiException(ErrorCode.GUARD_FAILED, reason)
				: refuse(reason, repository.fullName(), repository.htmlUrl(), Map.of());
	}

	private static ApiException refuse(ProductReason reason, String fullName, String htmlUrl, Map<String, Object> more) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("repository", fullName);
		details.put("repositoryUrl", htmlUrl);
		details.putAll(more);
		return new ApiException(ErrorCode.GUARD_FAILED, reason, details);
	}

}
