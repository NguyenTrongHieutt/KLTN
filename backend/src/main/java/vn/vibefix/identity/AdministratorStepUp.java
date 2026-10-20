package vn.vibefix.identity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.ApiException;

/**
 * The step-up another module's sensitive Administrator command needs (SG-10): a fresh TOTP code at a step
 * never used before, a wrong one counted as a failed sign-in. Call it inside the command's transaction
 * and, when refused, throw the refusal only after that transaction commits, so the failure is kept.
 */
public interface AdministratorStepUp {

	/**
	 * @param refusal the refusal to throw after commit, or {@code null} when the code passed
	 * @param endSession the 5th failure locked the sign-in: the session ends with the refusal
	 */
	record Result(@Nullable ApiException refusal, boolean endSession) {

		public boolean passed() {
			return this.refusal == null;
		}

	}

	Result check(UUID administrator, String code);

}
