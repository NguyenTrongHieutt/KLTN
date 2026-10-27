package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.timers.Deadlines;

/**
 * {@code identity}'s part in exports and erasure (AD-24). An erased account keeps its row, so the
 * records that name it still resolve, but loses its Google and GitHub identities, its avatar, its kept
 * token, its profile with its typed links and showcased repositories, its school email codes and send
 * counters, and its live verification, which ends {@code ERASED} with its facts deleted (so its subject
 * no longer counts as used). The verification rows keep their subject HMACs, as the person-link signals
 * do, until {@link PersonSignalPurge} deletes them 24 months later; no export shows an HMAC. Its
 * acknowledgments stay as the proof of what it agreed to.
 */
@Component
class IdentityPersonalData implements PersonalDataParticipant {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	private final Deadlines deadlines;

	private final Accounts accounts;

	private final Clock clock;

	private final Profiles profiles;

	private final AdministratorCredentials administrators;

	IdentityPersonalData(JdbcClient jdbc, Accounts accounts, Clock clock, Profiles profiles,
			AdministratorCredentials administrators, Deadlines deadlines) {
		this.deadlines = deadlines;
		this.administrators = administrators;
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.clock = clock;
		this.profiles = profiles;
	}

	@Override
	public Set<String> tables() {
		return Set.of("identity.account", "identity.acknowledgment", "identity.customer_token",
				"identity.profile", "identity.profile_link", "identity.showcase_repository",
				"identity.contact_grant", "identity.school_email_code", "identity.school_email_send_hour",
				"identity.school_email_send_day", "identity.verification", "identity.administrator_credential",
				"identity.administrator_enrolment", "identity.person_signal", "identity.deletion_request",
				"identity.account_action");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		return this.accounts.findById(accountId).map(account -> {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("kind", account.kind().name());
			// The stored role of an account from before Google sign-in, kept as text until Story 20.6 drops it.
			if (account.role() != null) {
				data.put("role", account.role());
			}
			// The Google identity: the subject that keys the account, and what the last sign-in read.
			if (account.googleSub() != null) {
				data.put("googleSub", account.googleSub());
			}
			if (account.googleEmail() != null) {
				data.put("googleEmail", account.googleEmail());
			}
			if (account.googleName() != null) {
				data.put("googleName", account.googleName());
			}
			if (account.githubLogin() != null) {
				data.put("githubLogin", account.githubLogin());
			}
			if (account.githubUserId() != null) {
				data.put("githubUserId", account.githubUserId());
			}
			// When GitHub was linked (Story 19.2): ACC-2's "Xác minh qua GitHub · {tháng/năm}".
			this.accounts.gitHubLinkedAt(accountId).ifPresent(at -> data.put("githubLinkedAt", at.toString()));
			this.accounts.gitHubEmail(accountId).ifPresent(email -> data.put("githubEmail", email));
			data.put("createdAt", account.createdAt().toString());
			data.put("acknowledgments", this.jdbc.sql("""
					SELECT notice, notice_version, acknowledged_at FROM identity.acknowledgment
					WHERE account_id = :id ORDER BY acknowledged_at
					""").param("id", accountId).query((rs, n) -> Map.of("notice", rs.getString("notice"), "version",
					rs.getString("notice_version"), "at", rs.getTimestamp("acknowledged_at").toInstant().toString()))
				.list());
			// The one profile as the person typed it (Story 19.3), with its typed links and repositories.
			this.profiles.find(accountId).ifPresent(profile -> data.put("profile", profileSection(profile)));
			data.put("theme", this.profiles.theme(accountId).name());
			// Each verification with its public facts while live; never its subject HMAC (Story 19.4).
			List<Map<String, Object>> verifications = this.jdbc.sql("""
					SELECT kind, provider, facts, consent_version, verified_at, valid_until, ended_at, ended_reason
					FROM identity.verification WHERE account_id = :id ORDER BY verified_at
					""").param("id", accountId).query((rs, n) -> {
				Map<String, Object> verification = new LinkedHashMap<>();
				verification.put("kind", rs.getString("kind"));
				verification.put("method", VerificationRows.Provider.valueOf(rs.getString("provider")).method());
				if (rs.getString("facts") != null) {
					verification.put("facts", JSON.readValue(rs.getString("facts"), Map.class));
				}
				verification.put("consentVersion", rs.getString("consent_version"));
				verification.put("verifiedAt", rs.getTimestamp("verified_at").toInstant().toString());
				verification.put("validUntil", rs.getObject("valid_until", LocalDate.class).toString());
				if (rs.getTimestamp("ended_at") != null) {
					verification.put("endedAt", rs.getTimestamp("ended_at").toInstant().toString());
					verification.put("endedReason", rs.getString("ended_reason"));
				}
				return verification;
			}).list();
			if (!verifications.isEmpty()) {
				data.put("verifications", verifications);
			}
			// What Administrators did to the account and why (FR-59); not which Administrator.
			List<Map<String, String>> actions = this.jdbc.sql("""
					SELECT action, reason, acted_at FROM identity.account_action WHERE account_id = :id ORDER BY acted_at
					""").param("id", accountId).query((rs, n) -> Map.of("action", rs.getString("action"), "reason",
					rs.getString("reason"), "at", rs.getTimestamp("acted_at").toInstant().toString()))
				.list();
			if (!actions.isEmpty()) {
				data.put("accountActions", actions);
			}
			// Person-link signals by where they came from, never their HMACs (AD-19).
			List<Map<String, String>> signals = this.jdbc.sql("""
					SELECT DISTINCT source, recorded_at FROM identity.person_signal
					WHERE account_id = :id ORDER BY recorded_at, source
					""").param("id", accountId).query((rs, n) -> Map.of("source", rs.getString("source"), "recordedAt",
					rs.getTimestamp("recorded_at").toInstant().toString())).list();
			if (!signals.isEmpty()) {
				data.put("personSignals", signals);
			}
			// The pending code's school, declared details, and time; never the code or the address, which no table holds.
			List<Map<String, String>> codes = this.jdbc.sql("""
					SELECT school, declared_name, declared_major, sent_at FROM identity.school_email_code
					WHERE account_id = :id ORDER BY sent_at
					""").param("id", accountId).query((rs, n) -> Map.of("school", rs.getString("school"), "name",
					rs.getString("declared_name"), "major", rs.getString("declared_major"), "sentAt",
					rs.getTimestamp("sent_at").toInstant().toString())).list();
			if (!codes.isEmpty()) {
				data.put("schoolEmailCodes", codes);
			}
			// An Administrator's email and name; never the password hash or the TOTP secret.
			this.administrators.identity(accountId).ifPresent(found -> data.put("administrator",
					Map.of("email", found.email(), "displayName", found.displayName())));
			this.jdbc.sql("""
					SELECT email, display_name FROM identity.administrator_enrolment
					WHERE account_id = :id AND completed_at IS NULL
					""").param("id", accountId).query((rs, n) -> Map.of("email", rs.getString("email"), "displayName",
					rs.getString("display_name"))).optional().ifPresent(found -> data.put("administratorEnrolment", found));
			return new PersonalDataSection("identity", data);
		});
	}

	/** Joins the deletion transaction; the purge Deadline it schedules needs one. */
	@Override
	@Transactional
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM identity.customer_token WHERE account_id = :id").param("id", accountId).update();
		// The deletion request stays as the record that it was carried out; an erased Administrator's name goes.
		this.jdbc.sql("UPDATE identity.deletion_request SET completed_by_admin_id = NULL WHERE completed_by_admin_id = :id")
			.param("id", accountId)
			.update();
		// The live verification ends ERASED with its facts; the row keeps its subject HMAC (AD-24).
		this.jdbc.sql("""
				UPDATE identity.verification SET ended_at = :now, ended_reason = 'ERASED', facts = NULL
				WHERE account_id = :id AND ended_at IS NULL
				""").param("id", accountId).param("now", Timestamp.from(this.clock.instant())).update();
		for (String table : new String[] { "identity.showcase_repository", "identity.school_email_code",
				"identity.school_email_send_hour", "identity.administrator_credential", "identity.administrator_enrolment",
				"identity.account_action", "identity.profile_link", "identity.profile" }) {
			this.jdbc.sql("DELETE FROM " + table + " WHERE account_id = :id").param("id", accountId).update();
		}
		this.jdbc.sql("""
				DELETE FROM identity.contact_grant WHERE customer_account_id = :id OR mentor_account_id = :id
				""").param("id", accountId).update();
		Instant erasedAt = this.clock.instant();
		this.jdbc.sql("""
				UPDATE identity.account
				SET github_user_id = NULL, github_login = NULL, github_avatar_url = NULL, github_email = NULL,
				    github_linked_at = NULL, google_sub = NULL, google_email = NULL, google_name = NULL, erased_at = :at
				WHERE id = :id
				""").param("id", accountId).param("at", Timestamp.from(erasedAt)).update();
		// The person-link signals outlive the account (AD-19, AD-24): deleted 24 months from now.
		this.deadlines.schedule(PersonSignalPurge.after(accountId, erasedAt));
	}

	/** Every field as saved; the Skills by name too, since the IDs alone mean nothing to a person. */
	private Map<String, Object> profileSection(Profiles.Profile profile) {
		Map<String, Object> section = new LinkedHashMap<>();
		section.put("displayName", profile.displayName());
		section.put("kind", profile.kind().name());
		section.put("contactEmail", profile.contactEmail());
		section.put("phone", profile.phone());
		section.put("experience", profile.experience());
		section.put("yearsOfExperience", profile.yearsOfExperience());
		section.put("skills", this.profiles.skills(profile.skillIds()).stream().map(skill -> skill.name()).toList());
		section.put("links", profile.links());
		section.put("repositories", profile.repositories());
		section.put("hideStudentDetails", profile.hideStudentDetails());
		Profiles.StudentDetails details = profile.studentDetails();
		if (details != null) {
			Map<String, Object> student = new LinkedHashMap<>();
			student.put("name", details.name());
			student.put("school", details.school());
			student.put("major", details.major());
			section.put("studentDetails", student);
		}
		return section;
	}

	@Override
	public void deleteProduct(UUID productId) {
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
