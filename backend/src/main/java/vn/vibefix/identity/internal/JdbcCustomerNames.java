package vn.vibefix.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.CustomerNames;

/** {@link CustomerNames} from {@code identity.customer_profile}, falling back to the GitHub username. */
@Component
class JdbcCustomerNames implements CustomerNames {

	private final JdbcClient jdbc;

	JdbcCustomerNames(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public String displayName(UUID customerId) {
		return this.jdbc.sql("""
				SELECT coalesce(p.display_name, '@' || a.github_login, '')
				FROM identity.account a LEFT JOIN identity.customer_profile p ON p.account_id = a.id
				WHERE a.id = :id AND a.role = 'CUSTOMER' AND a.erased_at IS NULL
				""").param("id", customerId).query(String.class).optional().orElse("");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CustomerSince> since(UUID customerId) {
		return this.jdbc.sql("""
				SELECT p.kind, a.created_at
				FROM identity.account a LEFT JOIN identity.customer_profile p ON p.account_id = a.id
				WHERE a.id = :id AND a.role = 'CUSTOMER' AND a.erased_at IS NULL
				""")
			.param("id", customerId)
			.query((rs, n) -> new CustomerSince(rs.getString("kind"), rs.getTimestamp("created_at").toInstant()))
			.optional();
	}

}
