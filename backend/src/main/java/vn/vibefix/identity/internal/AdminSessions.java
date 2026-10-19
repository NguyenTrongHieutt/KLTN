package vn.vibefix.identity.internal;

import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;

/**
 * The limits of an Administrator session (SG-10): it ends after 30 minutes without activity or 8
 * hours after sign-in, whichever comes first. Only commands and the explicit activity call count as
 * activity, so background polling never extends it. Both times are session attributes; a session
 * without them is not an Administrator session.
 */
final class AdminSessions {

	static final Duration IDLE = Duration.ofMinutes(30);

	static final Duration ABSOLUTE = Duration.ofHours(8);

	static final String SIGNED_IN_AT = AdminSessions.class.getName() + ".signedInAt";

	static final String ACTIVE_AT = AdminSessions.class.getName() + ".activeAt";

	/** When the session ends without further activity, and at the latest. */
	record Expiry(Instant idle, Instant absolute) {
	}

	private AdminSessions() {
	}

	static void start(HttpSession session, Instant now) {
		session.setAttribute(SIGNED_IN_AT, now.toEpochMilli());
		session.setAttribute(ACTIVE_AT, now.toEpochMilli());
	}

	/** The session's limits, or nothing when it is not an Administrator session or has ended. */
	static @Nullable Expiry current(@Nullable HttpSession session, Instant now) {
		if (session == null || !(session.getAttribute(SIGNED_IN_AT) instanceof Long signedIn)
				|| !(session.getAttribute(ACTIVE_AT) instanceof Long active)) {
			return null;
		}
		Expiry expiry = new Expiry(Instant.ofEpochMilli(active).plus(IDLE), Instant.ofEpochMilli(signedIn).plus(ABSOLUTE));
		return now.isBefore(expiry.idle()) && now.isBefore(expiry.absolute()) ? expiry : null;
	}

	/** Records activity now; the absolute limit stays. */
	static void touch(HttpSession session, Instant now) {
		session.setAttribute(ACTIVE_AT, now.toEpochMilli());
	}

}
