package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code identity}'s part in exports and erasure (AD-24). An erased account keeps its row, so the
 * records that name it still resolve, but loses its GitHub identity, its avatar, and its kept token;
 * its acknowledgments stay as the proof of what it agreed to.
 */
@Component
class IdentityPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	private final Accounts accounts;

	private final Clock clock;

	IdentityPersonalData(JdbcClient jdbc, Accounts accounts, Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.clock = clock;
	}

	@Override
	public Set<String> tables() {
		return Set.of("identity.account", "identity.acknowledgment", "identity.customer_token");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		return this.accounts.findById(accountId).map(account -> {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("role", account.role().name());
			if (account.githubLogin() != null) {
				data.put("githubLogin", account.githubLogin());
			}
			if (account.githubUserId() != null) {
				data.put("githubUserId", account.githubUserId());
			}
			data.put("createdAt", account.createdAt().toString());
			data.put("acknowledgments", this.jdbc.sql("""
					SELECT notice, notice_version, acknowledged_at FROM identity.acknowledgment
					WHERE account_id = :id ORDER BY acknowledged_at
					""").param("id", accountId).query((rs, n) -> Map.of("notice", rs.getString("notice"), "version",
					rs.getString("notice_version"), "at", rs.getTimestamp("acknowledged_at").toInstant().toString()))
				.list());
			return new PersonalDataSection("identity", data);
		});
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM identity.customer_token WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("""
				UPDATE identity.account
				SET github_user_id = NULL, github_login = NULL, github_avatar_url = NULL, erased_at = :at
				WHERE id = :id
				""").param("id", accountId).param("at", Timestamp.from(this.clock.instant())).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
