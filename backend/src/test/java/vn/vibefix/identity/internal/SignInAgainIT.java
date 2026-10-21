package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * FR-13, author decision 2026-10-21: when the contributor check finds no kept token, it still fails
 * closed, and the Customer gets a to-do and a notice to sign in again; signing in again keeps a new token
 * and closes the to-do. The notice is sent once a day however many Mentors try.
 */
class SignInAgainIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private CustomerRepositories repositories;

	@Autowired
	private SignIns signIns;

	@Test
	void aMissingTokenAsksTheCustomerToSignInAgainUntilTheyDo() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null, null);

		for (int attempt = 0; attempt < 2; attempt++) {
			assertThatThrownBy(() -> this.repositories.hasContributor(lan, "lan", "so-dat-ban", mentor))
				.isInstanceOf(ApiException.class);
		}
		assertThat(openTodos(lan)).isOne();
		assertThat(this.jdbc.sql("SELECT link FROM notification.todo WHERE todo_key = 'identity.sign_in_again'")
			.query(String.class).single()).isEqualTo("/dang-nhap-lai");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'identity.sign_in_again' AND account_id = :id
				""").param("id", lan).query(Long.class).single()).as("once a day").isOne();

		long githubId = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", lan)
			.query(Long.class).single();
		// A fake token, built at run time so no secret pattern sits in the source.
		String token = String.join("", "gh", "o_", "x".repeat(36));
		this.signIns.signIn(githubId, "lan", null, null, List.of(), token);
		assertThat(openTodos(lan)).isZero();
	}

	private long openTodos(UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'identity.sign_in_again' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", account).query(Long.class).single();
	}

}
