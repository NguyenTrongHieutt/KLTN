package vn.vibefix.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.shared.Kind;
import vn.vibefix.support.MutableClock;

/**
 * Accounts for tests that need a signed-in caller without the sign-in flow: a row in
 * {@code identity.account} and the authentication a session would hold for it. An account with a {@link Role}
 * (the test-only shim enum, Story 20.3) is the shim of the accounts from before Google sign-in (Story 19.1),
 * kept while tests move over to roles per Job (Epic 20): the role seeds only the row's kind and, for a person, a
 * linked GitHub account. No account stores a role (Story 20.6), and nothing on the server decides on one.
 */
public final class IdentityTestSupport {

	private static final java.util.concurrent.atomic.AtomicLong githubIds = new java.util.concurrent.atomic.AtomicLong(
			900_000);

	private IdentityTestSupport() {
	}

	/**
	 * Inserts an account and returns its ID. A person's account carries a linked GitHub account
	 * ({@code user-{id}}), as the accounts from before Google sign-in did; a Mentor needs no approval
	 * (Story 19.7).
	 * @param lockReason a reason to lock it, or {@code null}
	 */
	public static UUID insertAccount(JdbcClient jdbc, Role role, @Nullable String lockReason) {
		UUID id = UUID.randomUUID();
		Long githubId = role == Role.ADMINISTRATOR ? null : githubIds.incrementAndGet();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
				INSERT INTO identity.account (id, kind, github_user_id, github_login, github_linked_at,
				    locked_at, lock_reason, created_at)
				VALUES (:id, :kind, :githubId, :login, :linkedAt, :lockedAt, :lockReason, :now)
				""")
			.param("id", id)
			.param("kind", role == Role.ADMINISTRATOR ? "ADMINISTRATOR" : "USER")
			.param("githubId", githubId)
			.param("login", githubId == null ? null : "user-" + githubId)
			.param("linkedAt", githubId == null ? null : now)
			.param("lockedAt", lockReason == null ? null : now)
			.param("lockReason", lockReason)
			.param("now", now)
			.update();
		return id;
	}

	/**
	 * Inserts a Google account, as a first Google sign-in creates it (Story 19.1): a {@code USER} with
	 * no role and no GitHub ID, which accepted N-10 {@code privacyNoticeVersion}. Returns its ID.
	 */
	public static UUID insertGoogleAccount(JdbcClient jdbc, String googleSub, String privacyNoticeVersion) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
				INSERT INTO identity.account (id, kind, google_sub, google_email, google_name, created_at, signed_in_at)
				VALUES (:id, 'USER', :sub, :sub || '@gmail.test', 'Người dùng ' || :sub, :now, :now)
				""").param("id", id).param("sub", googleSub).param("now", now).update();
		jdbc.sql("""
				INSERT INTO identity.acknowledgment (id, account_id, notice, notice_version, acknowledged_at)
				VALUES (:ack, :id, 'PRIVACY', :version, :now)
				""").param("ack", UUID.randomUUID()).param("id", id).param("version", privacyNoticeVersion)
			.param("now", now)
			.update();
		return id;
	}

	/**
	 * Links the next fake GitHub account ({@code user-{id}}) to the account, as linking does (Story 19.2); a Google
	 * account with it is a {@code USER} whose {@code githubVerified} capability holds, with no role anywhere.
	 */
	public static void linkGitHub(JdbcClient jdbc, UUID accountId) {
		long githubId = githubIds.incrementAndGet();
		jdbc.sql("""
				UPDATE identity.account SET github_user_id = :githubId, github_login = :login, github_linked_at = :now
				WHERE id = :id
				""")
			.param("githubId", githubId)
			.param("login", "user-" + githubId)
			.param("now", Timestamp.from(MutableClock.DEFAULT_INSTANT))
			.param("id", accountId)
			.update();
	}

	/**
	 * A request signed in as the account. An Administrator's is a real stored session (Spring Session
	 * JDBC) with the times {@code CallerFilter} requires (SG-10), as if signed in at
	 * {@code signedInAt} and active since; it is sent with its cookie, as a browser sends it.
	 */
	@SuppressWarnings("unchecked")
	public static RequestPostProcessor signedIn(SessionRepository<? extends Session> repository, UUID accountId,
			@Nullable Role role, Instant signedInAt) {
		if (role != Role.ADMINISTRATOR) {
			return SecurityMockMvcRequestPostProcessors.authentication(authentication(accountId, role));
		}
		SessionRepository<Session> sessions = (SessionRepository<Session>) repository;
		Session session = sessions.createSession();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication(accountId, role));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		session.setAttribute(AdminSessions.SIGNED_IN_AT, signedInAt.toEpochMilli());
		session.setAttribute(AdminSessions.ACTIVE_AT, signedInAt.toEpochMilli());
		sessions.save(session);
		Cookie cookie = new Cookie(SecurityConfiguration.SESSION_COOKIE,
				Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
		return request -> {
			Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
			Cookie[] all = Arrays.copyOf(existing, existing.length + 1);
			all[existing.length] = cookie;
			request.setCookies(all);
			return request;
		};
	}

	/**
	 * The authentication a signed-in session holds for the account: its ID and kind only (Story 20.1), the
	 * kind an Administrator's for {@code ADMINISTRATOR} and a person's for any other role or none (a Google
	 * account's). The caller filter reads the kind and the lock from the account row.
	 */
	public static Authentication authentication(UUID accountId, @Nullable Role role) {
		return new SignInSession.AccountAuthentication(accountId,
				role == Role.ADMINISTRATOR ? Kind.ADMINISTRATOR : Kind.USER);
	}

	/**
	 * Verifies the account as a student until {@code validUntil}, as a confirmed school email code does
	 * (Story 19.4): a live {@code identity.verification} row whose subject is a fake HMAC of the account,
	 * so no two accounts share it. The profile is left as it is.
	 */
	public static UUID verifyStudent(JdbcClient jdbc, UUID accountId, LocalDate validUntil) {
		UUID id = verifyStudent(jdbc, accountId, validUntil, fakeHmac(accountId.toString()));
		if (id == null) {
			throw new IllegalStateException("The account " + accountId + " is verified already");
		}
		return id;
	}

	/**
	 * Verifies the account with the subject {@code subjectHmac}, unless a live row holds that subject or
	 * the account already; returns the row's ID, or {@code null} when nothing was written.
	 */
	public static @Nullable UUID verifyStudent(JdbcClient jdbc, UUID accountId, LocalDate validUntil, String subjectHmac) {
		UUID id = UUID.randomUUID();
		int inserted = jdbc.sql("""
				INSERT INTO identity.verification (id, account_id, kind, provider, subject_hmac, key_version, facts,
				    consent_version, verified_at, valid_until)
				VALUES (:id, :account, 'STUDENT', 'EMAIL_CODE', :hmac, 1, CAST(:facts AS jsonb), '1.0', :now, :until)
				ON CONFLICT DO NOTHING
				""")
			.param("id", id)
			.param("account", accountId)
			.param("hmac", subjectHmac)
			.param("facts", """
					{"school":"hust.edu.vn","name":"Sinh viên thử","major":"Công nghệ thông tin","verifiedMonth":"2026-01"}""")
			.param("now", Timestamp.from(MutableClock.DEFAULT_INSTANT))
			.param("until", validUntil)
			.update();
		return inserted == 1 ? id : null;
	}

	/**
	 * Verifies the account until {@code validUntil} with these public facts, and projects them into its profile
	 * as {@code VerificationRows.insert} does (the profile row is created when there is none); the subject is
	 * a fake HMAC of the account. Returns the row's ID.
	 */
	public static UUID verifyStudent(JdbcClient jdbc, UUID accountId, LocalDate validUntil, String school,
			@Nullable String name, @Nullable String major) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
				INSERT INTO identity.verification (id, account_id, kind, provider, subject_hmac, key_version, facts,
				    consent_version, verified_at, valid_until)
				VALUES (:id, :account, 'STUDENT', 'EMAIL_CODE', :hmac, 1,
				        jsonb_strip_nulls(jsonb_build_object('school', CAST(:school AS text), 'name', CAST(:name AS text),
				            'major', CAST(:major AS text), 'verifiedMonth', '2026-01')), '1.0', :now, :until)
				""")
			.param("id", id)
			.param("account", accountId)
			.param("hmac", fakeHmac(accountId.toString()))
			.param("school", school)
			.param("name", name)
			.param("major", major)
			.param("now", now)
			.param("until", validUntil)
			.update();
		jdbc.sql("""
				INSERT INTO identity.profile (account_id, student_name, student_school, student_major, updated_at)
				VALUES (:account, :name, :school, :major, :now)
				ON CONFLICT (account_id) DO UPDATE SET student_name = EXCLUDED.student_name,
				    student_school = EXCLUDED.student_school, student_major = EXCLUDED.student_major
				""")
			.param("account", accountId)
			.param("name", name)
			.param("school", school)
			.param("major", major)
			.param("now", now)
			.update();
		return id;
	}

	/** A stand-in for a keyed HMAC: 64 lower-case hexadecimal digits that depend on {@code seed} only. */
	public static String fakeHmac(String seed) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java platform has SHA-256", ex);
		}
	}

	/**
	 * Removes the account's GitHub link, as an unlink does (Story 19.2); an account from before Google
	 * sign-in gets a Google subject first, since a person's account keeps one of the two.
	 */
	public static void unlinkGitHub(JdbcClient jdbc, UUID accountId) {
		jdbc.sql("""
				UPDATE identity.account
				SET google_sub = coalesce(google_sub, 'google-' || id), github_user_id = NULL, github_login = NULL,
				    github_avatar_url = NULL, github_linked_at = NULL
				WHERE id = :id
				""").param("id", accountId).update();
	}

	/** Locks an account, as an Administrator will (FR-59). */
	public static void lock(JdbcClient jdbc, UUID accountId, String reason) {
		jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = :reason WHERE id = :id")
			.param("at", Timestamp.from(MutableClock.DEFAULT_INSTANT))
			.param("reason", reason)
			.param("id", accountId)
			.update();
	}

}
