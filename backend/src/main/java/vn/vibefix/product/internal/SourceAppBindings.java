package vn.vibefix.product.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.SourceApp;
import vn.vibefix.github.SourceApp.Installation;
import vn.vibefix.github.SourceApp.SelectedRepository;
import vn.vibefix.shared.IdGenerator;

/**
 * Source App bindings (AD-10, AD-13, R2, Story 23.5): a Customer's pair (installation, repository), owned by
 * {@code product}. It re-implements the install-and-bind patterns the retired Merge App had in {@code repo} (L7),
 * which {@code product} (L5) may not call:
 * <ul>
 * <li>"Cài ứng dụng vibefix trên GitHub ↗" mints a single-use {@code state} for the pasted repository, kept only as
 * its SHA-256 and valid for {@link #STATE_LIFETIME};</li>
 * <li>the Setup URL's return consumes it only for the signed-in Customer who minted it and binds nothing, so the
 * one path that binds is the check;</li>
 * <li>"Kiểm tra" finds the installation through {@code GET /repos/{owner}/{name}/installation} with the App's JWT, so
 * an install an organization owner approves later binds on the next "Kiểm tra". The lookup writes nothing
 * ({@link #find}): the caller records the draft's binding ({@link #record}) only once the Customer's admin right on
 * the repository is read, so nobody keeps a binding of a repository they do not administer;</li>
 * <li>the selection rule: {@code repository_selection} is {@code selected}, the repository is in the selection,
 * every other selected repository is the Source Repository of a live Product of the same Customer, and the
 * installation is not suspended and has accepted Contents read, Metadata read and Actions write. Otherwise there is
 * no installation, a suspended one, or one missing permissions ({@code SOURCE_APP_MISSING}), or a wrong selection
 * ({@code SOURCE_APP_WRONG_REPOSITORY}).</li>
 * </ul>
 * Every {@link Found} and {@link Bound} comes from a read made just now, and a caller mints its token from it
 * ({@link #READ}, narrowed to the one repository and revoked after use), so a token is minted only while the rule
 * holds. Each write runs in a transaction of its own ({@code REQUIRES_NEW}), even inside a caller's, and takes the
 * Customer's advisory lock (AD-4) only for that write, so no lock or connection is held across a GitHub call.
 */
@Component
class SourceAppBindings {

	static final Duration STATE_LIFETIME = Duration.ofHours(1);

	/** A Source Repository read's permissions: its metadata, its collaborator permission, and its files. */
	static final java.util.Map<String, String> READ = java.util.Map.of("contents", "read", "metadata", "read");

	/** The contributor read's one permission (FR-13, Story 23.5b): metadata only, no file. */
	static final java.util.Map<String, String> METADATA_READ = java.util.Map.of("metadata", "read");

	private static final SecureRandom RANDOM = new SecureRandom();

	/** What the setup return says on CUS-3: installed, waiting for an organization owner, or nothing seen. */
	enum Returned {

		INSTALLED, REQUESTED, NOT_SEEN

	}

	/**
	 * A binding whose installation holds the selection rule on a read made just now.
	 * @param repositoryFullName the repository's full name as the installation selects it now
	 */
	record Bound(UUID bindingId, long installationId, long repositoryId, String repositoryFullName, String accountLogin,
			String accountType) {
	}

	/** What a check found: the bound pair, or why the binding does not hold. */
	record Checked(@Nullable Bound bound, @Nullable ProductReason refusal) {

		static Checked refused(ProductReason reason) {
			return new Checked(null, reason);
		}

	}

	/**
	 * An installation that holds the selection rule for a repository, read just now, with nothing written yet.
	 * @param repository the repository as the installation selects it
	 */
	record Found(Installation installation, SelectedRepository repository) {

		long installationId() {
			return this.installation.id();
		}

		long repositoryId() {
			return this.repository.id();
		}

	}

	/** What "Kiểm tra" found before anything is written: the installation, or why the rule does not hold. */
	record Lookup(@Nullable Found found, @Nullable ProductReason refusal) {

		static Lookup refused(ProductReason reason) {
			return new Lookup(null, reason);
		}

	}

	/** The Product's live binding, for the deletion guidance (R23). */
	record ProductBinding(long installationId, String accountLogin, String accountType, int otherProducts) {
	}

	private final SourceApp app;

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final IdGenerator ids;

	private final Clock clock;

	SourceAppBindings(SourceApp app, JdbcClient jdbc, PlatformTransactionManager transactionManager, IdGenerator ids,
			Clock clock) {
		this.app = app;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		// A write of its own, never the caller's: the registration's and the re-confirmation's transactions read GitHub.
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * A fresh install link for the Customer's draft of {@code owner/name}; the previous link's {@code state} no
	 * longer counts.
	 * @throws GitHubException when the App is not configured or its slug cannot be read
	 */
	String installLink(UUID customerId, String owner, String name) {
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		String url = this.app.installUrl(state);
		String fullName = owner + "/" + name;
		this.transactions.executeWithoutResult(status -> {
			lock(customerId);
			Timestamp now = now();
			Timestamp until = Timestamp.from(this.clock.instant().plus(STATE_LIFETIME));
			int updated = this.jdbc.sql("""
					UPDATE product.source_app_binding SET state_hash = :hash, state_expires_at = :until
					WHERE customer_account_id = :customer AND lower(repository_full_name) = lower(:name) AND product_id IS NULL
					    AND status <> 'ENDED'
					""").param("hash", hash(state)).param("until", until).param("customer", customerId).param("name", fullName)
				.update();
			if (updated == 0) {
				this.jdbc.sql("""
						INSERT INTO product.source_app_binding (id, customer_account_id, repository_full_name, status, state_hash,
						    state_expires_at, created_at)
						VALUES (:id, :customer, :name, 'PENDING', :hash, :until, :now)
						""").param("id", this.ids.newId()).param("customer", customerId).param("name", fullName)
					.param("hash", hash(state)).param("until", until).param("now", now).update();
			}
		});
		return url;
	}

	/**
	 * Uses up a setup return's {@code state}: it is the signed-in Customer's own and unexpired. It binds nothing; any
	 * other state changes nothing.
	 * @param installationId GitHub's {@code installation_id}, when it sent one
	 * @param action GitHub's {@code setup_action}: {@code install}, {@code update}, or {@code request}
	 * @return what CUS-3 says, or empty for a state that is not the Customer's, expired, or used
	 */
	Optional<Returned> consume(String state, UUID customerId, @Nullable Long installationId, @Nullable String action) {
		Optional<UUID> consumed = this.transactions.execute(status -> this.jdbc.sql("""
				UPDATE product.source_app_binding SET state_hash = NULL, state_expires_at = NULL
				WHERE state_hash = :hash AND state_expires_at > :now AND customer_account_id = :customer AND status <> 'ENDED'
				RETURNING id
				""").param("hash", hash(state)).param("now", now()).param("customer", customerId).query(UUID.class).optional());
		if (consumed == null || consumed.isEmpty()) {
			return Optional.empty();
		}
		if ("request".equals(action)) {
			return Optional.of(Returned.REQUESTED);
		}
		return Optional.of(installationId == null ? Returned.NOT_SEEN : Returned.INSTALLED);
	}

	/**
	 * "Kiểm tra" for the Customer's draft of {@code owner/name}: the installation that reaches it, read now, and the
	 * selection rule. Nothing is written: the caller {@link #record}s the binding once the admin right is read.
	 * @throws GitHubException when GitHub cannot answer, or the App is not configured
	 */
	Lookup find(UUID customerId, String owner, String name) {
		String fullName = owner + "/" + name;
		Installation installation = this.app.installationOn(owner, name).orElse(null);
		if (installation == null) {
			return Lookup.refused(ProductReason.SOURCE_APP_MISSING);
		}
		Rule rule = rule(customerId, installation, selected -> selected.fullName().equalsIgnoreCase(fullName));
		if (rule.refusal() != null) {
			return Lookup.refused(rule.refusal());
		}
		return new Lookup(new Found(installation, Objects.requireNonNull(rule.repository())), null);
	}

	/**
	 * Binds the Customer's draft of the pasted {@code fullName} to what {@link #find} found, once the Customer's admin
	 * right on the repository is read: the draft of the pasted name, else of GitHub's name; another draft of the same
	 * repository ends.
	 */
	Bound record(UUID customerId, String fullName, Found found) {
		Installation installation = found.installation();
		SelectedRepository repository = found.repository();
		UUID id = this.transactions.execute(status -> {
			lock(customerId);
			// The draft of the pasted name, else of GitHub's name; another draft of the same repository ends.
			Optional<UUID> draft = this.jdbc.sql("""
					SELECT id FROM product.source_app_binding
					WHERE customer_account_id = :customer AND product_id IS NULL AND status <> 'ENDED'
					    AND lower(repository_full_name) IN (lower(:pasted), lower(:name))
					ORDER BY lower(repository_full_name) = lower(:pasted) DESC, created_at DESC, id LIMIT 1
					""").param("customer", customerId).param("pasted", fullName).param("name", repository.fullName())
				.query(UUID.class).optional();
			UUID binding = draft.orElseGet(this.ids::newId);
			this.jdbc.sql("""
					UPDATE product.source_app_binding SET status = 'ENDED', ended_at = :now, state_hash = NULL,
					    state_expires_at = NULL
					WHERE customer_account_id = :customer AND product_id IS NULL AND status <> 'ENDED' AND id <> :id
					    AND (repository_github_id = :repository OR lower(repository_full_name) = lower(:name))
					""").param("now", now()).param("customer", customerId).param("id", binding)
				.param("repository", repository.id()).param("name", repository.fullName()).update();
			if (draft.isEmpty()) {
				this.jdbc.sql("""
						INSERT INTO product.source_app_binding (id, customer_account_id, repository_full_name, status, created_at)
						VALUES (:id, :customer, :name, 'PENDING', :now)
						""").param("id", binding).param("customer", customerId).param("name", repository.fullName())
					.param("now", now()).update();
			}
			bindRow(binding, installation, repository);
			return binding;
		});
		return new Bound(Objects.requireNonNull(id), installation.id(), repository.id(), repository.fullName(),
				installation.accountLogin(), installation.accountType());
	}

	/**
	 * The Product's binding, read now: by its installation; when that one is gone or no longer holds the rule, the
	 * installation found again by the repository's name (a reinstall, a Source transferred to an account where the
	 * App is installed, or a Product registered before the Source App), with the rule on the registered repository ID.
	 * When it holds, the Product is bound to it (the check binds always); else the known installation's refusal
	 * stands.
	 * @throws GitHubException when GitHub cannot answer, or the App is not configured
	 */
	Checked forProduct(UUID customerId, UUID productId, long repositoryId, String fullName) {
		Long known = this.jdbc.sql("""
				SELECT installation_id FROM product.source_app_binding WHERE product_id = :product AND status = 'BOUND'
				""").param("product", productId).query(Long.class).optional().orElse(null);
		Predicate<SelectedRepository> registered = selected -> selected.id() == repositoryId;
		Installation installation = known == null ? null : this.app.installation(known).orElse(null);
		Rule rule = installation == null ? null : rule(customerId, installation, registered);
		if (rule == null || rule.refusal() != null) {
			String[] parts = fullName.split("/", 2);
			Installation byName = parts.length == 2 ? this.app.installationOn(parts[0], parts[1]).orElse(null) : null;
			if (byName != null && (installation == null || byName.id() != installation.id())) {
				Rule other = rule(customerId, byName, registered);
				if (rule == null || other.refusal() == null) {
					installation = byName;
					rule = other;
				}
			}
		}
		if (installation == null || rule == null) {
			return Checked.refused(ProductReason.SOURCE_APP_MISSING);
		}
		if (rule.refusal() != null) {
			return Checked.refused(rule.refusal());
		}
		SelectedRepository repository = Objects.requireNonNull(rule.repository());
		Installation found = installation;
		UUID id = this.transactions.execute(status -> {
			lock(customerId);
			UUID binding = this.jdbc.sql("""
					SELECT id FROM product.source_app_binding WHERE product_id = :product AND status <> 'ENDED'
					""").param("product", productId).query(UUID.class).optional().orElse(null);
			if (binding == null) {
				binding = this.ids.newId();
				this.jdbc.sql("""
						INSERT INTO product.source_app_binding (id, customer_account_id, repository_full_name, repository_github_id,
						    installation_id, account_github_id, account_login, account_type, product_id, status, created_at,
						    bound_at)
						VALUES (:id, :customer, :name, :repository, :installation, :account, :login, :type, :product, 'BOUND',
						    :now, :now)
						""").param("id", binding).param("customer", customerId).param("name", repository.fullName())
					.param("repository", repository.id()).param("installation", found.id())
					.param("account", found.accountId()).param("login", found.accountLogin())
					.param("type", accountType(found)).param("product", productId).param("now", now()).update();
				return binding;
			}
			bindRow(binding, found, repository);
			return binding;
		});
		return new Checked(new Bound(id, installation.id(), repository.id(), repository.fullName(),
				installation.accountLogin(), installation.accountType()), null);
	}

	/**
	 * The registration's binding becomes the Product's, in the registration's transaction; the Customer's other
	 * drafts of the same repository end.
	 */
	void attach(UUID bindingId, UUID productId, UUID customerId, long repositoryId) {
		lock(customerId);
		this.jdbc.sql("""
				UPDATE product.source_app_binding SET status = 'ENDED', ended_at = :now, state_hash = NULL, state_expires_at = NULL
				WHERE customer_account_id = :customer AND product_id IS NULL AND status <> 'ENDED' AND id <> :id
				    AND repository_github_id = :repository
				""").param("now", now()).param("customer", customerId).param("id", bindingId).param("repository", repositoryId)
			.update();
		this.jdbc.sql("""
				UPDATE product.source_app_binding SET product_id = :product, state_hash = NULL, state_expires_at = NULL
				WHERE id = :id AND status = 'BOUND'
				""").param("product", productId).param("id", bindingId).update();
	}

	/** The Product's binding with how many other live Products use its installation; none before it is bound. */
	Optional<ProductBinding> ofProduct(UUID productId) {
		return this.jdbc.sql("""
				SELECT b.installation_id, b.account_login, b.account_type,
				       (SELECT count(*) FROM product.source_app_binding o JOIN product.product p ON p.id = o.product_id
				        WHERE o.installation_id = b.installation_id AND o.status = 'BOUND' AND o.product_id <> b.product_id
				          AND p.deleted_at IS NULL) AS others
				FROM product.source_app_binding b WHERE b.product_id = :product AND b.status = 'BOUND'
				""").param("product", productId)
			.query((rs, n) -> new ProductBinding(rs.getLong("installation_id"), rs.getString("account_login"),
					rs.getString("account_type"), rs.getInt("others")))
			.optional();
	}

	/** GitHub's installation settings page, where the Customer changes the selection or uninstalls (R23). */
	String settingsUrl(ProductBinding binding) {
		return this.app.settingsUrl(binding.accountType(), binding.accountLogin(), binding.installationId());
	}

	/** The selection rule (AD-10, R2) on an installation read just now. */
	private Rule rule(UUID customerId, Installation installation, Predicate<SelectedRepository> repository) {
		if (installation.suspended() || !installation.accepted()) {
			return new Rule(null, ProductReason.SOURCE_APP_MISSING);
		}
		if (installation.all()) {
			return new Rule(null, ProductReason.SOURCE_APP_WRONG_REPOSITORY);
		}
		SelectedRepository found = installation.repositories().stream().filter(repository).findFirst().orElse(null);
		if (found == null) {
			return new Rule(null, ProductReason.SOURCE_APP_WRONG_REPOSITORY);
		}
		List<Long> others = installation.repositories()
			.stream()
			.map(SelectedRepository::id)
			.filter(id -> id != found.id())
			.distinct()
			.toList();
		if (!others.isEmpty()) {
			long live = this.jdbc.sql("""
					SELECT count(DISTINCT repository_github_id) FROM product.product
					WHERE customer_account_id = :customer AND deleted_at IS NULL AND repository_github_id IN (:others)
					""").param("customer", customerId).param("others", others).query(Long.class).single();
			if (live != others.size()) {
				return new Rule(null, ProductReason.SOURCE_APP_WRONG_REPOSITORY);
			}
		}
		return new Rule(found, null);
	}

	/** The selection rule's outcome: the repository as selected, or why it does not hold. */
	private record Rule(@Nullable SelectedRepository repository, @Nullable ProductReason refusal) {
	}

	private void bindRow(UUID binding, Installation installation, SelectedRepository repository) {
		this.jdbc.sql("""
				UPDATE product.source_app_binding SET status = 'BOUND', repository_github_id = :repository,
				    repository_full_name = :name, installation_id = :installation, account_github_id = :account,
				    account_login = :login, account_type = :type, bound_at = :now
				WHERE id = :id
				""").param("repository", repository.id()).param("name", repository.fullName())
			.param("installation", installation.id()).param("account", installation.accountId())
			.param("login", installation.accountLogin()).param("type", accountType(installation)).param("now", now())
			.param("id", binding).update();
	}

	private static String accountType(Installation installation) {
		return "Organization".equals(installation.accountType()) ? "Organization" : "User";
	}

	/** AD-4's Source App lock: one Customer's bindings change one at a time. */
	private void lock(UUID customerId) {
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "product.source_app_customer:" + customerId)
			.query((rs, n) -> 1)
			.list();
	}

	private Timestamp now() {
		return Timestamp.from(this.clock.instant());
	}

	static String hash(String state) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
