package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationContext;

/** An enrolled Administrator with a TOTP secret, for tests outside {@code identity} that need one. */
public final class AdministratorTestSupport {

	/** The Administrator and its TOTP secret. */
	public record Administrator(UUID accountId, byte[] secret) {
	}

	/** One password hash for every enrolled test Administrator: the step-up reads only the TOTP code. */
	private static final String PASSWORD_HASH = AdminSignIns.PASSWORDS.encode("mat-khau-du-dai-1");

	private AdministratorTestSupport() {
	}

	/** The first Administrator, through the bootstrap; the test database must have none yet. */
	public static Administrator create(ApplicationContext context, String email, String name) {
		AdministratorBootstrap.Created created = context.getBean(AdministratorBootstrap.class)
			.createFirst(email, name, "mat-khau-du-dai-1".toCharArray());
		return new Administrator(created.accountId(), Totp.fromBase32(created.secret()));
	}

	/**
	 * Enrols an Administrator account that exists already with a password and a TOTP secret, as an enrolment through
	 * ADM-11's link stores them, so its step-up passes with {@link #code}.
	 */
	public static Administrator enrol(ApplicationContext context, UUID accountId, String email, String name, Instant at) {
		byte[] secret = Totp.newSecret();
		context.getBean(AdministratorCredentials.class)
			.insert(accountId, email, name, PASSWORD_HASH, context.getBean(TokenCipher.class)
				.seal(Totp.base32(secret), AdministratorCredentials.totpAssociatedData(accountId)), at);
		return new Administrator(accountId, secret);
	}

	/** The code of the TOTP step that contains {@code at}. */
	public static String code(Administrator administrator, Instant at) {
		return Totp.codeAt(administrator.secret(), Totp.step(at));
	}

}
