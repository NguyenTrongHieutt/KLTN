package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;

/**
 * Products (FR-7, FR-8, AD-20): a Customer's registered Product ({@code {productId}}), whose Customer
 * the matrix calls {@link MatrixCaller#OWNER}, with one open credential Finding of its intake scan
 * ({@code {findingId}}) that the owner resolves. Only the owner and Administrators read them.
 */
public class ProductFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private UUID owner;

	public ProductFixture(JdbcClient jdbc, SessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.sessions = sessions;
	}

	@Override
	public void seed(MatrixWorld world) {
		this.owner = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID product = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sản phẩm ma trận', 'Mô tả', ARRAY['LOVABLE'], :repository, 'matrix/product', 'User',
				    'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", product)
			.param("owner", this.owner)
			.param("repository", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		world.put("productId", product);
		UUID run = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, rules_version, found_count, new_findings, requested_at,
				    finished_at)
				VALUES (:id, :product, :owner, 'Sản phẩm ma trận', 'matrix/product', 1, 'INTAKE', 'SUCCEEDED', 2, 1, 1,
				    :now, :now)
				""").param("id", run).param("product", product).param("owner", this.owner).param("now", now).update();
		UUID finding = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
				    commit_sha, committed_at, masked_preview, fingerprint, status, created_at)
				VALUES (:id, :product, :run, 'CREDENTIAL', 'stripe-secret-key', 'Khóa bí mật Stripe', 'server/pay.js',
				    repeat('a', 40), :now, 'sk_l••••••••Q7mZ', repeat('b', 64), 'OPEN', :now)
				""").param("id", finding).param("product", product).param("run", run).param("now", now).update();
		this.jdbc.sql("INSERT INTO scanning.finding_resolver (finding_id, account_id) VALUES (:f, :a)")
			.param("f", finding)
			.param("a", this.owner)
			.update();
		world.put("findingId", finding);
	}

	@Override
	public Set<String> stateKeys() {
		return Set.of();
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		if (caller != MatrixCaller.OWNER) {
			return Optional.empty();
		}
		return Optional.of(IdentityTestSupport.signedIn(this.sessions, this.owner, Role.CUSTOMER, MutableClock.DEFAULT_INSTANT));
	}

}
