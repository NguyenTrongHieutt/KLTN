package vn.vibefix.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.CustomerNames;

/**
 * {@link CustomerNames} from the account's one profile (Story 19.3), falling back to the Google name
 * and then the GitHub username; any account may be a Job's Customer.
 */
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
				SELECT coalesce(p.display_name, a.google_name, '@' || a.github_login, '')
				FROM identity.account a LEFT JOIN identity.profile p ON p.account_id = a.id
				WHERE a.id = :id AND a.erased_at IS NULL
				""").param("id", customerId).query(String.class).optional().orElse("");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CustomerSince> since(UUID customerId) {
		return this.jdbc.sql("""
				SELECT p.kind, a.created_at
				FROM identity.account a LEFT JOIN identity.profile p ON p.account_id = a.id
				WHERE a.id = :id AND a.erased_at IS NULL
				""")
			.param("id", customerId)
			.query((rs, n) -> new CustomerSince(rs.getString("kind"), rs.getTimestamp("created_at").toInstant()))
			.optional();
	}

}
