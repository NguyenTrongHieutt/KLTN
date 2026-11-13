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
import vn.vibefix.github.SourceApp.WorkflowCheck;
import vn.vibefix.identity.GitHubAccountIds;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.internal.SourceAppBindings.Bound;
import vn.vibefix.product.internal.SourceAppBindings.Checked;
import vn.vibefix.product.internal.SourceStatuses.Status;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link ProductSources} through the Source App (AD-10, AD-13, AD-45, Story 23.5), with registration's rules: the
 * Product's binding holds the selection rule on a fresh read (and is bound again to a reinstalled App), then one
 * token narrowed to the repository reads it, must find the registered GitHub ID, the Customer's linked account with
 * admin through the collaborator-permission endpoint, and a published {@code vibefix.yml} on its default branch. The
 * token is revoked after the reads. The contributor read (FR-13, FR-81) and the drift read (FR-20, R7) take the
 * binding the same way (Story 23.5b); the contributor read answers "unknown" rather than throwing, and the drift read
 * reads no admin right. J7's head read and dispatch target (Story 23.6a) take it the same way too, read the repository
 * and its workflow with one token, and read no admin right; the head read then reads the default branch's ref. No path
 * reads a repository with the Customer's linking token. A GitHub failure is a {@link GitHubException}, which the caller
 * retries.
 * <p>
 * Each read records what it found of the Source App and the workflow in {@link SourceStatuses} (Story 23.5c), so CUS-2
 * shows it without a GitHub call, with the default branch it read when that moved: the check and the re-confirmation
 * (whose admin right is no status, so their workflow is read even when the admin right is not held), the drift read,
 * J7's head read and dispatch target, and a contributor read whose binding refuses record it best-effort, so a failed
 * side effect never changes what they answer; CUS-4's "Kiểm tra lại" ({@link #refresh}) fails with it. A GitHub failure
 * records nothing.
 */
@Component
class SourceChecks implements ProductSources {

	private static final Logger log = LoggerFactory.getLogger(SourceChecks.class);

	private record Row(UUID customerId, String name, String fullName, long githubId, String defaultBranch) {
	}

	/**
	 * What the token's reads found: a block, or none with the default branch read now, and the status to record.
	 * @param branchRead the default branch as the repository was read now; none when no repository was read
	 */
	private record Read(@Nullable Block block, String defaultBranch, String fullName, Status status, @Nullable String blob,
			@Nullable String branchRead) {
	}

	/**
	 * The repository and its workflow, read with one token.
	 * @param repository none when the token reads no repository of the registered ID
	 * @param blob the accepted blob the file matched
	 * @param lineEndings a mismatching file that holds a carriage return
	 */
	private record Found(@Nullable RepositoryMetadata repository, Status status, @Nullable String blob,
			boolean lineEndings) {
	}

	/**
	 * "Kiểm tra lại"'s outcome (CUS-4, Story 23.5c), as recorded.
	 * @param lineEndings a {@link Status#WORKFLOW_MISMATCH} whose file holds a carriage return
	 */
	record Refreshed(Status status, boolean lineEndings) {
	}

	/** What the drift read found, and the status it records. */
	private record DriftRead(Drift drift, Status status, @Nullable String blob, @Nullable String branchRead) {
	}

	/** What a J7 read found (Story 23.6a): its answer, and the status it records. */
	private record J7Read<T>(T answer, Status status, @Nullable String blob, @Nullable String branchRead) {
	}

	private final SourceAppBindings bindings;

	private final SourceApp app;

	private final GitHubRepositories repositories;

	private final GitHubAccountIds customers;

	private final JdbcClient jdbc;

	private final SourceStatuses statuses;

	SourceChecks(SourceAppBindings bindings, SourceApp app, GitHubRepositories repositories, GitHubAccountIds customers,
			JdbcClient jdbc, SourceStatuses statuses) {
		this.bindings = bindings;
		this.app = app;
		this.repositories = repositories;
		this.customers = customers;
		this.jdbc = jdbc;
		this.statuses = statuses;
	}

	@Override
	public Optional<Registered> registered(UUID productId) {
		return this.jdbc.sql("""
				SELECT customer_account_id, repository_github_id, repository_full_name, repository_default_branch
				FROM product.product
				WHERE id = :id AND deleted_at IS NULL
				""").param("id", productId).query((rs, n) -> new Registered(productId, rs.getObject("customer_account_id", UUID.class),
				rs.getLong("repository_github_id"), rs.getString("repository_full_name"),
				rs.getString("repository_default_branch"))).optional();
	}

	@Override
	@Transactional
	public @Nullable Block reconfirm(UUID productId, String newFullName) {
		Row product = row(productId);
		if (newFullName.split("/", 2).length != 2) {
			return Block.SOURCE_APP_WRONG_REPOSITORY;
		}
		// The status is recorded in a transaction of its own before this one writes the Product's row.
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

	/**
	 * CUS-4's "Kiểm tra lại" (Story 23.5c): the binding on a fresh read, then the repository and its workflow with one
	 * token, without the admin right; the status is recorded, and a failure to record it fails the check.
	 * @throws ApiException {@code NOT_FOUND} for no such Product
	 * @throws GitHubException when GitHub cannot answer now, or the Source App is not configured: nothing is recorded
	 */
	Refreshed refresh(UUID productId) {
		Row product = row(productId);
		Checked checked = this.bindings.forProduct(product.customerId(), productId, product.githubId(), product.fullName());
		if (checked.refusal() != null) {
			Status status = Status.valueOf(checked.refusal().name());
			this.statuses.record(productId, status, null, null);
			return new Refreshed(status, false);
		}
		Bound bound = checked.bound();
		Found found = this.app.withToken(bound.installationId(), bound.repositoryId(), SourceAppBindings.READ,
				token -> found(product, bound, token));
		RepositoryMetadata repository = found.repository();
		this.statuses.record(productId, found.status(), found.blob(), repository == null ? null : repository.defaultBranch());
		return new Refreshed(found.status(), found.lineEndings());
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
				recordQuietly(productId, Status.valueOf(checked.refusal().name()), null, null);
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
			recordQuietly(productId, Status.valueOf(checked.refusal().name()), null, null);
			return new Drift(Block.valueOf(checked.refusal().name()), null, product.fullName(), false, List.of());
		}
		Bound bound = checked.bound();
		DriftRead read = this.app.withToken(bound.installationId(), bound.repositoryId(), SourceAppBindings.READ,
				token -> drift(product, bound, token, copiedSha));
		recordQuietly(productId, read.status(), read.blob(), read.branchRead());
		return read.drift();
	}

	@Override
	public Head head(UUID productId) {
		Row product = row(productId);
		Checked checked = this.bindings.forProduct(product.customerId(), productId, product.githubId(), product.fullName());
		if (checked.refusal() != null) {
			Block block = Block.valueOf(checked.refusal().name());
			recordQuietly(productId, Status.valueOf(block.name()), null, null);
			return new Head(block, null, product.defaultBranch(), product.fullName());
		}
		Bound bound = checked.bound();
		J7Read<Head> read = this.app.withToken(bound.installationId(), bound.repositoryId(), SourceAppBindings.READ, token -> {
			Found found = found(product, bound, token);
			RepositoryMetadata repository = found.repository();
			if (repository == null) {
				return new J7Read<>(new Head(Block.SOURCE_APP_WRONG_REPOSITORY, null, product.defaultBranch(), product.fullName()),
						found.status(), null, null);
			}
			String sha = this.repositories.head(token.value(), repository.fullName(), repository.defaultBranch());
			return new J7Read<>(new Head(null, sha, repository.defaultBranch(), repository.fullName()), found.status(),
					found.blob(), repository.defaultBranch());
		});
		recordQuietly(productId, read.status(), read.blob(), read.branchRead());
		return read.answer();
	}

	@Override
	public DispatchTarget dispatchTarget(UUID productId) {
		Row product = row(productId);
		Checked checked = this.bindings.forProduct(product.customerId(), productId, product.githubId(), product.fullName());
		if (checked.refusal() != null) {
			Block block = Block.valueOf(checked.refusal().name());
			recordQuietly(productId, Status.valueOf(block.name()), null, null);
			return new DispatchTarget(block, 0, product.githubId(), product.fullName(), product.defaultBranch());
		}
		Bound bound = checked.bound();
		J7Read<DispatchTarget> read = this.app.withToken(bound.installationId(), bound.repositoryId(), SourceAppBindings.READ,
				token -> {
					Found found = found(product, bound, token);
					RepositoryMetadata repository = found.repository();
					if (repository == null) {
						return new J7Read<>(new DispatchTarget(Block.SOURCE_APP_WRONG_REPOSITORY, 0, product.githubId(),
								product.fullName(), product.defaultBranch()), found.status(), null, null);
					}
					return new J7Read<>(new DispatchTarget(null, bound.installationId(), repository.id(), repository.fullName(),
							repository.defaultBranch()), found.status(), found.blob(), repository.defaultBranch());
				});
		recordQuietly(productId, read.status(), read.blob(), read.branchRead());
		return read.answer();
	}

	/**
	 * The repository, its workflow and its new commits, with one token: a workflow that differs is read next to the
	 * rest, so no other change waits for it. The admin right is no drift.
	 */
	private DriftRead drift(Row product, Bound bound, InstallationToken token, String copiedSha) {
		Found found = found(product, bound, token);
		RepositoryMetadata repository = found.repository();
		if (repository == null) {
			return new DriftRead(new Drift(Block.SOURCE_APP_WRONG_REPOSITORY, null, product.fullName(), false, List.of()),
					found.status(), null, null);
		}
		Block workflow = found.status() == Status.READY ? null : Block.valueOf(found.status().name());
		List<String> commits = this.repositories.commitsSince(token.value(), repository.fullName(), copiedSha,
				repository.defaultBranch());
		List<String> kept = commits.size() > MAX_DRIFT_COMMITS ? commits.subList(commits.size() - MAX_DRIFT_COMMITS,
				commits.size()) : commits;
		return new DriftRead(new Drift(null, workflow, repository.fullName(), repository.archived(), kept), found.status(),
				found.blob(), repository.defaultBranch());
	}

	/**
	 * The binding on a fresh read under {@code fullName}, then the repository, the admin right, and the workflow; what
	 * it found of the Source App and the workflow is recorded, best-effort.
	 */
	private Read read(UUID productId, Row product, String fullName) {
		Checked checked = this.bindings.forProduct(product.customerId(), productId, product.githubId(), fullName);
		if (checked.refusal() != null) {
			Block block = Block.valueOf(checked.refusal().name());
			recordQuietly(productId, Status.valueOf(block.name()), null, null);
			return new Read(block, product.defaultBranch(), fullName, Status.valueOf(block.name()), null, null);
		}
		Bound bound = checked.bound();
		Read read = this.app.withToken(bound.installationId(), bound.repositoryId(), SourceAppBindings.READ,
				token -> read(product, bound, token));
		recordQuietly(productId, read.status(), read.blob(), read.branchRead());
		return read;
	}

	private Read read(Row product, Bound bound, InstallationToken token) {
		Found found = found(product, bound, token);
		RepositoryMetadata repository = found.repository();
		if (repository == null) {
			return new Read(Block.SOURCE_APP_WRONG_REPOSITORY, product.defaultBranch(), bound.repositoryFullName(),
					found.status(), null, null);
		}
		if (!RepositoryChecks.administers(this.customers, this.repositories, product.customerId(), token,
				repository.fullName())) {
			return new Read(Block.NO_ADMIN_PERMISSION, repository.defaultBranch(), repository.fullName(), found.status(),
					found.blob(), repository.defaultBranch());
		}
		Block block = found.status() == Status.READY ? null : Block.valueOf(found.status().name());
		return new Read(block, repository.defaultBranch(), repository.fullName(), found.status(), found.blob(),
				repository.defaultBranch());
	}

	/**
	 * The repository by the bound name, which must be the registered ID, else {@link Status#SOURCE_APP_WRONG_REPOSITORY};
	 * then its {@code vibefix.yml} on its default branch.
	 */
	private Found found(Row product, Bound bound, InstallationToken token) {
		String[] name = bound.repositoryFullName().split("/", 2);
		RepositoryMetadata repository = this.repositories.read(token.value(), name[0], name[1]).orElse(null);
		if (repository == null || repository.id() != product.githubId()) {
			return new Found(null, Status.SOURCE_APP_WRONG_REPOSITORY, null, false);
		}
		WorkflowCheck workflow = this.app.workflow(token, repository.fullName(), repository.defaultBranch());
		return new Found(repository, Status.of(workflow.verdict()), workflow.blob(), workflow.lineEndings());
	}

	/**
	 * Records what a read found, best-effort: the check, the re-confirmation, the drift read and the contributor read
	 * answer what GitHub said even when the side effect fails (a notification refused, a database error), which is
	 * logged with the Product.
	 */
	private void recordQuietly(UUID productId, Status status, @Nullable String blob, @Nullable String branch) {
		try {
			this.statuses.record(productId, status, blob, branch);
		}
		catch (RuntimeException failed) {
			log.warn("The Source status {} of Product {} was not recorded; the check answers anyway", status, productId,
					failed);
		}
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
