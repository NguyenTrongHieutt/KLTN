package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationContext;

/** An enrolled Administrator with a TOTP secret, for tests outside {@code identity} that need one. */
public final class AdministratorTestSupport {

	/** The Administrator and its TOTP secret. */
	public record Administrator(UUID accountId, byte[] secret) {
	}

	private AdministratorTestSupport() {
	}

	/** The first Administrator, through the bootstrap; the test database must have none yet. */
	public static Administrator create(ApplicationContext context, String email, String name) {
		AdministratorBootstrap.Created created = context.getBean(AdministratorBootstrap.class)
			.createFirst(email, name, "mat-khau-du-dai-1".toCharArray());
		return new Administrator(created.accountId(), Totp.fromBase32(created.secret()));
	}

	/** The code of the TOTP step that contains {@code at}. */
	public static String code(Administrator administrator, Instant at) {
		return Totp.codeAt(administrator.secret(), Totp.step(at));
	}

}
