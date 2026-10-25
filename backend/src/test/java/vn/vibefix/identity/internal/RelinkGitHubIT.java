package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.identity.GitHubLinks;
import vn.vibefix.identity.internal.GitHubLinkReader.LinkedUser;
import vn.vibefix.identity.internal.GitHubLinksImpl.Purpose;
import vn.vibefix.product.ProductSources;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * FR-13, FR-18, AD-10, author decision 2026-10-21, Story 19.2: when an AD-13 re-check finds no kept token,
 * it still fails closed, and the live Product's owner gets the {@code identity.relink_github} to-do and a
 * notice, which lead to ACC-2; the Source Repository check before a Code Job starts blocks with
 * {@code RELINK_GITHUB}. The notice is sent once a day however many Mentors try. Re-linking the same GitHub
 * account keeps a new token and closes the to-do; deleting the last live Product forgets the token.
 */
class RelinkGitHubIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private CustomerRepositories repositories;

	@Autowired
	private ProductSources sources;

	@Autowired
	private GitHubLinksImpl links;

	@Autowired
	private GitHubLinks publicLinks;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void aMissingTokenAsksTheOwnerToReLinkUntilTheyDo() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null, null);
		UUID product = liveProduct(lan);

		for (int attempt = 0; attempt < 2; attempt++) {
			assertThatThrownBy(() -> this.repositories.hasContributor(lan, "lan", "so-dat-ban", mentor))
				.isInstanceOf(ApiException.class);
		}
		assertThat(openTodos(lan)).isOne();
		assertThat(this.jdbc.sql("SELECT link FROM notification.todo WHERE todo_key = 'identity.relink_github'")
			.query(String.class).single()).isEqualTo("/ho-so/xac-minh");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'identity.relink_github' AND account_id = :id
				""").param("id", lan).query(Long.class).single()).as("once a day").isOne();
		assertThat(this.sources.check(product).block()).isEqualTo(ProductSources.Block.RELINK_GITHUB);

		long githubId = CustomerTokens.githubUserId(this.jdbc, lan);
		// A fake token, built at run time so no secret pattern sits in the source.
		String token = String.join("", "gh", "o_", "x".repeat(36));
		GitHubLinksImpl.Outcome outcome = this.links.link(lan, new LinkedUser(githubId, "lan", null, null, List.of()),
				Purpose.PROFILE, token);

		assertThat(outcome).isEqualTo(GitHubLinksImpl.Outcome.RELINKED);
		assertThat(openTodos(lan)).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.customer_token WHERE account_id = :id").param("id", lan)
			.query(Long.class).single()).as("kept for the live Product's owner").isOne();
	}

	@Test
	void reLinkingAnotherGitHubAccountIsRefusedAndKeepsNothing() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		long githubId = CustomerTokens.githubUserId(this.jdbc, lan);

		GitHubLinksImpl.Outcome outcome = this.links.link(lan, new LinkedUser(githubId + 1, "someone", null, null,
				List.of("someone@example.test")), Purpose.PRODUCT, String.join("", "gh", "o_", "y".repeat(36)));

		assertThat(outcome).isEqualTo(GitHubLinksImpl.Outcome.LINK_IN_USE);
		assertThat(CustomerTokens.githubUserId(this.jdbc, lan)).isEqualTo(githubId);
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.customer_token WHERE account_id = :id").param("id", lan)
			.query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE account_id = :id").param("id", lan)
			.query(Long.class).single()).isZero();
	}

	@Test
	void theTokenIsForgottenOnlyOnceNoLiveProductNeedsIt() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID product = liveProduct(lan);
		CustomerTokens.keep(this.context, lan, String.join("", "gh", "o_", "z".repeat(36)));

		new TransactionTemplate(this.transactionManager)
			.executeWithoutResult(status -> this.publicLinks.forgetRepositoryToken(lan));
		assertThat(tokens(lan)).as("a live Product still reads with it").isOne();

		this.jdbc.sql("UPDATE product.product SET deleted_at = now() WHERE id = :id").param("id", product).update();
		new TransactionTemplate(this.transactionManager)
			.executeWithoutResult(status -> this.publicLinks.forgetRepositoryToken(lan));
		assertThat(tokens(lan)).isZero();
		assertThat(CustomerTokens.githubUserId(this.jdbc, lan)).as("the link stays").isPositive();
	}

	private long tokens(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM identity.customer_token WHERE account_id = :id").param("id", account)
			.query(Long.class).single();
	}

	private UUID liveProduct(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :repository, 'lan/so-dat-ban',
				    'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""").param("id", id).param("owner", owner)
			.param("repository", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		return id;
	}

	private long openTodos(UUID account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'identity.relink_github' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", account).query(Long.class).single();
	}

}
