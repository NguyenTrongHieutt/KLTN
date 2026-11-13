package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.github.OAuthGrants;
import vn.vibefix.identity.internal.GitHubLinkReader.LinkedUser;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 23.5d (R31, AD-10, AD-19, SG-9): the GitHub link keeps identity only. As the callback does, each test requests
 * the grant's revocation first and then links; whatever the link leads to (a re-link of the same numeric ID, even by a
 * live Product's owner, a refused one of another ID, or a locked account), the token GitHub issued is stored nowhere
 * and exactly one revocation of its whole OAuth grant exists, carrying the token sealed with the account ID; the
 * operation revokes the grant on GitHub, never a token alone. Nothing asks anyone to re-link, and deleting the last
 * live Product revokes nothing, since nothing is kept.
 */
class GitHubGrantRevocationIT extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private GitHubLinksImpl links;

	@Autowired
	private TokenCipher cipher;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void aLiveProductsOwnerReLinksTheSameIdAndOnlyTheGrantRevocationHoldsTheToken() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		liveProduct(lan);
		long githubId = githubId(lan);
		// A fake token, built at run time so no secret pattern sits in the source.
		String token = String.join("", "gh", "o_", "x".repeat(36));

		GitHubLinksImpl.Outcome outcome = callback(lan, new LinkedUser(githubId, "lan", null, null, List.of()), token);

		assertThat(outcome).isEqualTo(GitHubLinksImpl.Outcome.RELINKED);
		assertThat(this.jdbc.sql("SELECT coalesce(to_regclass('identity.customer_token')::text, 'gone')").query(String.class)
			.single()).as("no table keeps a GitHub token").isEqualTo("gone");
		List<Map<String, Object>> revocations = revocations(lan);
		assertThat(revocations).singleElement().satisfies(revocation -> {
			assertThat(json((String) revocation.get("payload"))).as("the whole grant: no revoke mode")
				.isEqualTo(Map.of("accountId", lan.toString()));
			assertThat((String) revocation.get("personal_data")).contains("ciphertext").doesNotContain(token);
		});
		assertThat(openToken(lan, revocations.getFirst())).isEqualTo(token);
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE account_id = :id AND closed_at IS NULL AND todo_key LIKE 'identity.%'
				""").param("id", lan).query(Long.class).single()).as("nobody is asked to re-link").isZero();
	}

	@Test
	void reLinkingAnotherGitHubAccountIsRefusedAndStillRevokesTheGrant() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		long githubId = githubId(lan);
		String token = String.join("", "gh", "o_", "y".repeat(36));

		GitHubLinksImpl.Outcome outcome = callback(lan, new LinkedUser(githubId + 1, "someone", null, null,
				List.of("someone@example.test")), token);

		assertThat(outcome).isEqualTo(GitHubLinksImpl.Outcome.LINK_IN_USE);
		assertThat(githubId(lan)).isEqualTo(githubId);
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE account_id = :id").param("id", lan)
			.query(Long.class).single()).isZero();
		assertThat(revocations(lan)).as("nothing of the refused link stays authorized").hasSize(1);
	}

	@Test
	void aLockedAccountLinksNothingAndItsOneRevocationNamesOnlyTheAccount() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		long githubId = githubId(lan);
		IdentityTestSupport.lock(this.jdbc, lan, "Vi phạm điều khoản");
		String token = String.join("", "gh", "o_", "l".repeat(36));

		GitHubLinksImpl.Outcome outcome = callback(lan, new LinkedUser(githubId, "lan-moi", null, null,
				List.of("lan@example.test")), token);

		assertThat(outcome).isEqualTo(GitHubLinksImpl.Outcome.ACCOUNT_INACTIVE);
		assertThat(this.jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id").param("id", lan)
			.query(String.class).single()).as("nothing changed").isNotEqualTo("lan-moi");
		List<Map<String, Object>> revocations = revocations(lan);
		assertThat(revocations).as("exactly one, the callback's: the link requests none").hasSize(1);
		assertThat(json((String) revocations.getFirst().get("payload"))).isEqualTo(Map.of("accountId", lan.toString()));
		assertThat(openToken(lan, revocations.getFirst())).isEqualTo(token);
	}

	@Test
	void theRevocationRevokesTheWholeGrantOnGitHubWithTheOpenedToken() {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		String token = String.join("", "gh", "o_", "z".repeat(36));
		callback(lan, new LinkedUser(githubId(lan), "lan", null, null, List.of()), token);
		Map<String, Object> revocation = revocations(lan).getFirst();
		RecordingGrants grants = new RecordingGrants();
		OAuthGrantRevocation handler = new OAuthGrantRevocation(this.cipher, new GitHubSignIn(linkingApp()), grants);

		Outcome outcome = handler.run(new OperationRun(UUID.randomUUID(), "key", OAuthGrantRevocation.TYPE, 1,
				json((String) revocation.get("payload")), json((String) revocation.get("personal_data"))));

		assertThat(outcome).isEqualTo(Outcome.succeeded());
		assertThat(grants.calls).containsExactly("grant stub-client " + token);
	}

	@Test
	void deletingTheLastLiveProductRevokesNothingAndKeepsTheLink() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID product = liveProduct(lan);

		this.mvc.perform(delete("/api/v1/products/" + product)
			.with(IdentityTestSupport.signedIn(this.sessions, lan, Role.CUSTOMER, this.clock.instant()))
			.with(csrf())).andExpect(status().isNoContent());

		assertThat(revocations(lan)).isEmpty();
		assertThat(githubId(lan)).as("the link stays").isPositive();
	}

	/** The grant revocations requested for the account, with their payload and sealed personal data as JSON text. */
	private List<Map<String, Object>> revocations(UUID account) {
		return this.jdbc.sql("""
				SELECT payload::text AS payload, personal_data::text AS personal_data FROM operations.external_operation
				WHERE type = 'identity.revoke_oauth_grant' AND account_id = :id ORDER BY created_at
				""").param("id", account).query().listOfRows();
	}

	private String openToken(UUID account, Map<String, Object> revocation) {
		Map<String, Object> sealed = json((String) revocation.get("personal_data"));
		return this.cipher.open(new TokenCipher.Sealed(((Number) sealed.get("keyVersion")).intValue(),
				java.util.Base64.getDecoder().decode((String) sealed.get("nonce")),
				java.util.Base64.getDecoder().decode((String) sealed.get("ciphertext"))), account.toString());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> json(String text) {
		return JSON.readValue(text, Map.class);
	}

	private static MockEnvironment linkingApp() {
		return new MockEnvironment().withProperty(GitHubSignIn.CLIENT_ID, "stub-client")
			.withProperty(GitHubSignIn.CLIENT_SECRET, "stub-secret")
			.withProperty("vibefix.github.oauth.authorization-uri", "http://127.0.0.1:1/login/oauth/authorize")
			.withProperty("vibefix.github.oauth.token-uri", "http://127.0.0.1:1/login/oauth/access_token")
			.withProperty("vibefix.github.oauth.user-info-uri", "http://127.0.0.1:1/user");
	}

	/** What the callback does once GitHub issued a token: the grant's revocation first, then the link. */
	private GitHubLinksImpl.Outcome callback(UUID account, LinkedUser user, String token) {
		this.links.revokeGrant(account, token);
		return this.links.link(account, user);
	}

	private long githubId(UUID account) {
		return this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", account)
			.query(Long.class)
			.single();
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

	/** Records what is asked of GitHub instead of asking it. */
	private static final class RecordingGrants implements OAuthGrants {

		private final List<String> calls = new ArrayList<>();

		@Override
		public void revokeGrant(String clientId, String clientSecret, String accessToken) {
			this.calls.add("grant " + clientId + " " + accessToken);
		}

		@Override
		public void revokeToken(String clientId, String clientSecret, String accessToken) {
			this.calls.add("token " + clientId + " " + accessToken);
		}

	}

}
