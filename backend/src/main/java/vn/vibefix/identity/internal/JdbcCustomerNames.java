package vn.vibefix.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.CustomerNames;

/**
 * {@link CustomerNames} by the one name rule of {@link Profiles#NAME} (Story 19.3); any person's
 * account may be a Job's Customer, an Administrator's never.
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
		return this.jdbc.sql("SELECT coalesce(" + Profiles.NAME + ", '')"
				+ " FROM identity.account a LEFT JOIN identity.profile p ON p.account_id = a.id"
				+ " WHERE a.id = :id AND a.kind = 'USER' AND a.erased_at IS NULL")
			.param("id", customerId)
			.query(String.class)
			.optional()
			.orElse("");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CustomerSince> since(UUID customerId) {
		return this.jdbc.sql("""
				SELECT p.kind, a.created_at
				FROM identity.account a LEFT JOIN identity.profile p ON p.account_id = a.id
				WHERE a.id = :id AND a.kind = 'USER' AND a.erased_at IS NULL
				""")
			.param("id", customerId)
			.query((rs, n) -> new CustomerSince(rs.getString("kind"), rs.getTimestamp("created_at").toInstant()))
			.optional();
	}

}
