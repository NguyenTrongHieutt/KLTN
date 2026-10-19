package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.internal.AdministratorCredentials.Credential;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Step-up confirmation (SG-10): before a sensitive command an Administrator enters a fresh TOTP code,
 * at a step never used before. A wrong code counts as a failed sign-in for that email, so a stolen
 * session cannot guess codes: the answer says how many attempts are left, and the 5th failure locks
 * sign-in for 15 minutes, alerts the other Administrators, and ends the session.
 * <p>
 * Call it inside the command's transaction, and return a refusal rather than throw it, so the
 * failure is counted: the caller throws after commit.
 */
@Component
class StepUps {

	/** How a step-up ended. */
	sealed interface Outcome {

		record Passed() implements Outcome {
		}

		/** @param endSession the 5th failure locked the email: the session ends with the refusal */
		record Refused(ApiException refusal, boolean endSession) implements Outcome {
		}

	}

	private final AdministratorCredentials credentials;

	private final AdminSignIns signIns;

	private final Clock clock;

	StepUps(AdministratorCredentials credentials, AdminSignIns signIns, Clock clock) {
		this.credentials = credentials;
		this.signIns = signIns;
		this.clock = clock;
	}

	Outcome check(UUID administrator, String code) {
		Optional<Credential> credential = this.credentials.findByAccount(administrator);
		if (credential.isEmpty()) {
			return new Outcome.Refused(new ApiException(ErrorCode.NOT_FOUND), true);
		}
		String emailHash = StudentVerifications.sha256(credential.get().email());
		Instant now = this.clock.instant();
		Optional<Instant> lockedUntil = this.signIns.lockedUntil(emailHash, now);
		if (lockedUntil.isPresent()) {
			return new Outcome.Refused(AdminSignIns.locked(Duration.between(now, lockedUntil.get())), true);
		}
		if (this.signIns.codeAccepted(credential.get(), code, now)) {
			return new Outcome.Passed();
		}
		AdminSignIns.Failure failure = this.signIns.recordFailure(emailHash, credential, "identity.step_up_failed", now);
		if (failure.locked()) {
			return new Outcome.Refused(AdminSignIns.locked(AdminSignIns.LOCK), true);
		}
		return new Outcome.Refused(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.STEP_UP_FAILED,
				Map.of("attemptsLeft", failure.attemptsLeft())), false);
	}

}
