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

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.timers.Deadlines;

/**
 * {@code identity}'s part in exports and erasure (AD-24). An erased account keeps its row, so the
 * records that name it still resolve, but loses its GitHub identity, its avatar, its kept token,
 * its profiles, its school email codes, and its verification (so its school address no longer counts
 * as used); its acknowledgments stay as the proof of what it agreed to.
 */
@Component
class IdentityPersonalData implements PersonalDataParticipant {

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
				"identity.customer_profile", "identity.student_profile", "identity.showcase_repository",
				"identity.contact_grant", "identity.school_email_code", "identity.student_verification",
				"identity.student_card", "identity.administrator_credential", "identity.administrator_enrolment",
				"identity.person_signal", "identity.mentor_application", "identity.mentor_decision",
				"identity.account_action");
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
			this.accounts.notificationAddress(accountId).ifPresent(email -> data.put("githubEmail", email));
			data.put("createdAt", account.createdAt().toString());
			data.put("acknowledgments", this.jdbc.sql("""
					SELECT notice, notice_version, acknowledged_at FROM identity.acknowledgment
					WHERE account_id = :id ORDER BY acknowledged_at
					""").param("id", accountId).query((rs, n) -> Map.of("notice", rs.getString("notice"), "version",
					rs.getString("notice_version"), "at", rs.getTimestamp("acknowledged_at").toInstant().toString()))
				.list());
			this.profiles.customer(accountId).ifPresent(profile -> data.put("customerProfile", profile));
			this.profiles.student(accountId).ifPresent(profile -> {
				data.put("studentProfile", profile);
				// The Skills by name, as the Student chose them; the IDs alone mean nothing to a person.
				data.put("studentSkills", this.profiles.skills(profile.skillIds()).stream().map(skill -> skill.name()).toList());
			});
			data.put("theme", this.profiles.theme(accountId).name());
			this.jdbc.sql("""
					SELECT method, school_email, verified_at, valid_until FROM identity.student_verification
					WHERE account_id = :id
					""").param("id", accountId).query((rs, n) -> {
				Map<String, Object> verification = new LinkedHashMap<>();
				verification.put("method", rs.getString("method"));
				verification.put("schoolEmail", rs.getString("school_email"));
				verification.put("verifiedAt", rs.getTimestamp("verified_at").toInstant().toString());
				verification.put("validUntil", rs.getObject("valid_until", LocalDate.class).toString());
				return verification;
			}).optional().ifPresent(verification -> data.put("studentVerification", verification));
			// The cards' decisions and what the Administrator recorded; the files are files' to export.
			List<Map<String, Object>> cards = this.jdbc.sql("""
					SELECT status, file_name, uploaded_at, decided_at, student_number, school, rejection_reason
					FROM identity.student_card WHERE account_id = :id ORDER BY uploaded_at
					""").param("id", accountId).query((rs, n) -> {
				Map<String, Object> card = new LinkedHashMap<>();
				card.put("status", rs.getString("status"));
				card.put("fileName", rs.getString("file_name"));
				card.put("uploadedAt", rs.getTimestamp("uploaded_at").toInstant().toString());
				if (rs.getTimestamp("decided_at") != null) {
					card.put("decidedAt", rs.getTimestamp("decided_at").toInstant().toString());
				}
				if (rs.getString("student_number") != null) {
					card.put("studentNumber", rs.getString("student_number"));
					card.put("school", rs.getString("school"));
				}
				if (rs.getString("rejection_reason") != null) {
					card.put("rejectionReason", rs.getString("rejection_reason"));
				}
				return card;
			}).list();
			if (!cards.isEmpty()) {
				data.put("studentCards", cards);
			}
			// The Mentor application as its applicant filled it in, with the document's name.
			this.jdbc.sql("""
					SELECT display_name, experience, years_of_experience, skill_names, linkedin_url, other_link,
					       document_name, submitted_at
					FROM identity.mentor_application WHERE account_id = :id
					""").param("id", accountId).query((rs, n) -> {
				Map<String, Object> application = new LinkedHashMap<>();
				application.put("displayName", rs.getString("display_name"));
				application.put("experience", rs.getString("experience"));
				application.put("yearsOfExperience", rs.getObject("years_of_experience"));
				application.put("skills", rs.getString("skill_names"));
				application.put("linkedinUrl", rs.getString("linkedin_url"));
				application.put("otherLink", rs.getString("other_link"));
				application.put("document", rs.getString("document_name"));
				Timestamp submitted = rs.getTimestamp("submitted_at");
				application.put("submittedAt", submitted == null ? null : submitted.toInstant().toString());
				return application;
			}).optional().ifPresent(application -> data.put("mentorApplication", application));
			// The decisions on it, with the reasons the applicant read; not which Administrator decided.
			List<Map<String, Object>> decisions = this.jdbc.sql("""
					SELECT decision, reason, reapply_after, decided_at FROM identity.mentor_decision
					WHERE account_id = :id ORDER BY decided_at
					""").param("id", accountId).query((rs, n) -> {
				Map<String, Object> decision = new LinkedHashMap<>();
				decision.put("decision", rs.getString("decision"));
				decision.put("decidedAt", rs.getTimestamp("decided_at").toInstant().toString());
				if (rs.getString("reason") != null) {
					decision.put("reason", rs.getString("reason"));
					decision.put("reapplyAfter", rs.getTimestamp("reapply_after").toInstant().toString());
				}
				return decision;
			}).list();
			if (!decisions.isEmpty()) {
				data.put("mentorDecisions", decisions);
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
			// The addresses and times of the codes, never the codes: only their hashes are kept.
			List<Map<String, String>> codes = this.jdbc.sql("""
					SELECT email, sent_at FROM identity.school_email_code WHERE account_id = :id ORDER BY sent_at
					""").param("id", accountId).query((rs, n) -> Map.of("email", rs.getString("email"), "sentAt",
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
		for (String table : new String[] { "identity.customer_profile", "identity.student_profile",
				"identity.showcase_repository", "identity.school_email_code", "identity.student_verification",
				"identity.student_card", "identity.administrator_credential", "identity.administrator_enrolment",
				"identity.mentor_application", "identity.mentor_decision", "identity.account_action" }) {
			this.jdbc.sql("DELETE FROM " + table + " WHERE account_id = :id").param("id", accountId).update();
		}
		this.jdbc.sql("""
				DELETE FROM identity.contact_grant WHERE customer_account_id = :id OR mentor_account_id = :id
				""").param("id", accountId).update();
		Instant erasedAt = this.clock.instant();
		this.jdbc.sql("""
				UPDATE identity.account
				SET github_user_id = NULL, github_login = NULL, github_avatar_url = NULL, github_email = NULL, erased_at = :at
				WHERE id = :id
				""").param("id", accountId).param("at", Timestamp.from(erasedAt)).update();
		// The person-link signals outlive the account (AD-19, AD-24): deleted 24 months from now.
		this.deadlines.schedule(PersonSignalPurge.after(accountId, erasedAt));
	}

	@Override
	public void deleteProduct(UUID productId) {
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
