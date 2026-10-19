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
 * Products (FR-7, AD-20): a Customer's registered Product ({@code {productId}}), whose Customer the
 * matrix calls {@link MatrixCaller#OWNER}. Only the owner and Administrators read its detail.
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
