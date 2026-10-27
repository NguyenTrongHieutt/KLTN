package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rows of {@code identity.verification} (Story 19.4, AD-42): one live row per account and kind, keyed
 * by the keyed HMAC of its subject. Ending a row deletes its public facts in the same transaction and, unless
 * a new row replaces it, clears the profile's projection of them; the subject HMAC stays on the ended row
 * (AD-24). Every writer here joins its caller's transaction, which holds the account's lock.
 */
@Component
class VerificationRows {

	static final String STUDENT = "STUDENT";

	/**
	 * How a student verification was made. {@code STUDENT_CARD} is read on the ended rows from before Story
	 * 19.7 retired the card; nothing writes it any more.
	 */
	enum Provider {

		EMAIL_CODE, STUDENT_CARD;

		/** The API's name of the method: {@code SCHOOL_EMAIL}, or {@code STUDENT_CARD} for an old ended row. */
		String method() {
			return this == EMAIL_CODE ? "SCHOOL_EMAIL" : "STUDENT_CARD";
		}

	}

	/**
	 * Why a row ended. {@code REVOKED} is read on the rows an Administrator revoked before Story 19.7 removed
	 * the revocation; nothing writes it any more.
	 */
	enum Ending {

		EXPIRED, UNLINKED, REPLACED, ERASED, REVOKED

	}

	/**
	 * The public facts of a live row.
	 * @param school the allowlisted domain the address matched
	 * @param name the declared full name, locked while the row is live
	 * @param major the declared major, locked while the row is live
	 * @param verifiedMonth {@code yyyy-MM}, in Vietnam
	 */
	record Facts(String school, @Nullable String name, @Nullable String major, String verifiedMonth) {
	}

	/** One row; {@code facts} is absent once it ended. */
	record Row(UUID id, UUID accountId, Provider provider, @Nullable Facts facts, @Nullable String consentVersion,
			Instant verifiedAt, LocalDate validUntil, @Nullable Instant endedAt, @Nullable Ending endedReason) {

		boolean live() {
			return this.endedAt == null;
		}

	}

	/** What a new row holds. */
	record NewRow(UUID id, UUID accountId, Provider provider, String subjectHmac, int keyVersion, Facts facts,
			@Nullable String consentVersion, Instant verifiedAt, LocalDate validUntil) {
	}

	private static final String COLUMNS = """
			id, account_id, provider, facts, consent_version, verified_at, valid_until, ended_at, ended_reason
			""";

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	VerificationRows(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** {@code yyyy-MM} of the day a verification was made, in Vietnam. */
	static String month(Instant verifiedAt) {
		return LocalDate.ofInstant(verifiedAt, StudentVerifications.VIETNAM).format(MONTH);
	}

	/** The account's row that has not ended, current or lapsed, if it has one. */
	Optional<Row> live(UUID accountId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM identity.verification"
				+ " WHERE account_id = :account AND kind = 'STUDENT' AND ended_at IS NULL")
			.param("account", accountId)
			.query(VerificationRows::row)
			.optional();
	}

	/** The account's latest row: the one that has not ended, else the latest one that did. */
	Optional<Row> latest(UUID accountId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM identity.verification"
				+ " WHERE account_id = :account AND kind = 'STUDENT'"
				+ " ORDER BY ended_at IS NULL DESC, verified_at DESC, id DESC LIMIT 1")
			.param("account", accountId)
			.query(VerificationRows::row)
			.optional();
	}

	/** The row {@code id} of {@code accountId} while it has not ended. */
	Optional<Row> liveById(UUID id, UUID accountId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM identity.verification"
				+ " WHERE id = :id AND account_id = :account AND ended_at IS NULL")
			.param("id", id)
			.param("account", accountId)
			.query(VerificationRows::row)
			.optional();
	}

	/** When the account last verified, by any way, if it ever did. */
	Optional<Instant> lastVerifiedAt(UUID accountId) {
		return this.jdbc.sql("""
				SELECT max(verified_at) FROM identity.verification WHERE account_id = :account AND kind = 'STUDENT'
				""").param("account", accountId).query(Timestamp.class).optional().map(Timestamp::toInstant);
	}

	/**
	 * Holds the subject's lock until commit, so "one live account per subject" holds across accounts
	 * before the partial unique index has to.
	 */
	void lockSubject(String subjectHmac) {
		this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "identity.verification:" + subjectHmac)
			.query((rs, n) -> 1)
			.single();
	}

	/** Whether any of the subject's HMACs, by key version, is on another account's row that has not ended. */
	boolean liveElsewhere(Map<Integer, String> hmacs, UUID accountId) {
		for (Map.Entry<Integer, String> hmac : hmacs.entrySet()) {
			boolean taken = this.jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM identity.verification
					               WHERE kind = 'STUDENT' AND key_version = :version AND subject_hmac = :hmac
					                 AND ended_at IS NULL AND account_id <> :account)
					""")
				.param("version", hmac.getKey())
				.param("hmac", hmac.getValue())
				.param("account", accountId)
				.query(Boolean.class)
				.single();
			if (taken) {
				return true;
			}
		}
		return false;
	}

	/** Writes a live row, and projects its facts into the profile. */
	@Transactional(propagation = Propagation.MANDATORY)
	void insert(NewRow row, Instant now) {
		this.jdbc.sql("""
				INSERT INTO identity.verification (id, account_id, kind, provider, subject_hmac, key_version, facts,
				    consent_version, verified_at, valid_until)
				VALUES (:id, :account, 'STUDENT', :provider, :hmac, :version, CAST(:facts AS jsonb), :consent, :verifiedAt,
				        :validUntil)
				""")
			.param("id", row.id())
			.param("account", row.accountId())
			.param("provider", row.provider().name())
			.param("hmac", row.subjectHmac())
			.param("version", row.keyVersion())
			.param("facts", json(row.facts()))
			.param("consent", row.consentVersion())
			.param("verifiedAt", Timestamp.from(row.verifiedAt()))
			.param("validUntil", row.validUntil())
			.update();
		// The projection the AD-36 search column and the directories read; a profile is created if none exists.
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, student_name, student_school, student_major, updated_at)
				VALUES (:account, :name, :school, :major, :now)
				ON CONFLICT (account_id) DO UPDATE SET student_name = EXCLUDED.student_name,
				    student_school = EXCLUDED.student_school, student_major = EXCLUDED.student_major
				""")
			.param("account", row.accountId())
			.param("name", row.facts().name())
			.param("school", row.facts().school())
			.param("major", row.facts().major())
			.param("now", Timestamp.from(now))
			.update();
	}

	/**
	 * Ends the row {@code reason}, deleting its facts. Unless a new row replaces it, the profile's
	 * projection is cleared. "Bỏ xác minh" and an erasure delete every pending code of the account; an
	 * expiry deletes a renewal's, since it holds a copy of the locked name and major.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void end(Row row, Ending reason, Instant now) {
		this.jdbc.sql("""
				UPDATE identity.verification SET ended_at = :now, ended_reason = :reason, facts = NULL
				WHERE id = :id AND ended_at IS NULL
				""").param("id", row.id()).param("now", Timestamp.from(now)).param("reason", reason.name()).update();
		if (reason == Ending.REPLACED) {
			return;
		}
		this.jdbc.sql("""
				UPDATE identity.profile SET student_name = NULL, student_school = NULL, student_major = NULL
				WHERE account_id = :account
				""").param("account", row.accountId()).update();
		if (reason == Ending.EXPIRED) {
			this.jdbc.sql("DELETE FROM identity.school_email_code WHERE account_id = :account AND renewal")
				.param("account", row.accountId())
				.update();
		}
		else {
			deleteCodes(row.accountId());
		}
	}

	/** Deletes every pending school email code of the account. */
	void deleteCodes(UUID accountId) {
		this.jdbc.sql("DELETE FROM identity.school_email_code WHERE account_id = :account")
			.param("account", accountId)
			.update();
	}

	/**
	 * Deletes the rows that ended at or before {@code cutoff}: their subject HMACs are kept 24 months
	 * after the row ends (AD-24), as the person-link signals of an erased account are.
	 * @return how many rows were deleted
	 */
	int purgeEnded(Instant cutoff) {
		return this.jdbc.sql("DELETE FROM identity.verification WHERE ended_at IS NOT NULL AND ended_at <= :cutoff")
			.param("cutoff", Timestamp.from(cutoff))
			.update();
	}

	private static String json(Facts facts) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("school", facts.school());
		if (facts.name() != null) {
			values.put("name", facts.name());
		}
		if (facts.major() != null) {
			values.put("major", facts.major());
		}
		values.put("verifiedMonth", facts.verifiedMonth());
		return JSON.writeValueAsString(values);
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		String facts = rs.getString("facts");
		Timestamp ended = rs.getTimestamp("ended_at");
		String reason = rs.getString("ended_reason");
		return new Row(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
				Provider.valueOf(rs.getString("provider")), facts == null ? null : facts(facts),
				rs.getString("consent_version"), rs.getTimestamp("verified_at").toInstant(),
				rs.getObject("valid_until", LocalDate.class), ended == null ? null : ended.toInstant(),
				reason == null ? null : Ending.valueOf(reason));
	}

	private static Facts facts(String json) {
		JsonNode node = JSON.readTree(json);
		return new Facts(node.path("school").asString(), text(node, "name"), text(node, "major"),
				node.path("verifiedMonth").asString());
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

}
