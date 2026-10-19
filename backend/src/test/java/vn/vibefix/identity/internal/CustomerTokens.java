package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationContext;

/** Keeps a Customer's sign-in token as sign-in does, for tests outside {@code identity}. */
public final class CustomerTokens {

	private CustomerTokens() {
	}

	public static void keep(ApplicationContext context, UUID customerId, String token) {
		Accounts accounts = context.getBean(Accounts.class);
		TokenCipher cipher = context.getBean(TokenCipher.class);
		accounts.storeCustomerToken(customerId, cipher.seal(token, customerId.toString()), Instant.now());
	}

	public static long githubUserId(org.springframework.jdbc.core.simple.JdbcClient jdbc, UUID accountId) {
		return jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", accountId)
			.query(Long.class).single();
	}

}
