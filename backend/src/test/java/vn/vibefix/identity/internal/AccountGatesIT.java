package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-19, Story 19.7: the two global gates that replace the Mentor approval, read from the database on every
 * call. New work needs GitHub linked; everything else an unlocked account. No Mentor needs an approval.
 */
class AccountGatesIT extends IntegrationTest {

	@Autowired
	private ActiveAccount activeAccount;

	@Autowired
	private GitHubGate gitHub;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void aMentorWithGitHubLinkedPassesBothGatesWithNoApproval() {
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);

		assertThatCode(() -> this.gitHub.requireGitHubLinked(mentor)).doesNotThrowAnyException();
		assertThatCode(() -> this.activeAccount.requireActiveAccount(mentor)).doesNotThrowAnyException();
	}

	@Test
	void anUnlinkedAccountIsRefusedNewWorkOnlyAndTheLinkIsReadEachTime() {
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		this.gitHub.requireGitHubLinked(mentor);

		IdentityTestSupport.unlinkGitHub(this.jdbc, mentor);

		refused(() -> this.gitHub.requireGitHubLinked(mentor), "GITHUB_NOT_LINKED");
		assertThatCode(() -> this.activeAccount.requireActiveAccount(mentor)).doesNotThrowAnyException();
	}

	@Test
	void bothGatesRefuseALockedErasedOrUnknownAccountAndTheLockIsReadEachTime() {
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		this.activeAccount.requireActiveAccount(mentor);

		IdentityTestSupport.lock(this.jdbc, mentor, "gate test");

		refused(() -> this.gitHub.requireGitHubLinked(mentor), "ACCOUNT_LOCKED");
		refused(() -> this.activeAccount.requireActiveAccount(mentor), "ACCOUNT_LOCKED");
		refused(() -> this.activeAccount.requireActiveAccount(UUID.randomUUID()), "ACCOUNT_LOCKED");

		UUID erased = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.jdbc.sql("""
				UPDATE identity.account SET erased_at = now(), github_user_id = NULL, github_login = NULL WHERE id = :id
				""").param("id", erased).update();
		refused(() -> this.activeAccount.requireActiveAccount(erased), "ACCOUNT_LOCKED");
	}

	private static void refused(ThrowingCallable call, String reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN);
			assertThat(ex.reason()).hasValue(reason);
		});
	}

}
