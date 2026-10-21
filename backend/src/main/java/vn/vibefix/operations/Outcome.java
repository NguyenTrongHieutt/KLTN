package vn.vibefix.operations;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * What one run of an {@link OperationHandler} found (AD-6). Codes are upper-case constants such as
 * {@code SMTP_UNAVAILABLE}: they reach the Administrator queue and the audit, never a sentence or
 * personal data.
 */
public sealed interface Outcome {

	/** The effect happened; {@code result} is what joiners of the key receive. */
	record Succeeded(Map<String, Object> result) implements Outcome {

		public Succeeded {
			result = Map.copyOf(result);
		}

	}

	/** The platform precondition no longer holds; the handler did not act. */
	record Obsolete(String code) implements Outcome {

		public Obsolete {
			requireCode(code);
		}

	}

	/** A precondition outside the platform is violated; retrying cannot help. */
	record Aborted(String code) implements Outcome {

		public Aborted {
			requireCode(code);
		}

	}

	/** The attempt failed and nothing happened outside; try again later. */
	record Transient(String code) implements Outcome {

		public Transient {
			requireCode(code);
		}

	}

	/** The attempt failed and the effect may have happened; read back before trying again. */
	record Ambiguous(String code) implements Outcome {

		public Ambiguous {
			requireCode(code);
		}

	}

	/**
	 * The other side answered and refused for good (a signed VNPay refusal, for example): retrying cannot
	 * help, and only an Administrator can tell what happened outside, so the operation fails at once into
	 * the FR-64 queue.
	 */
	record Refused(String code) implements Outcome {

		public Refused {
			requireCode(code);
		}

	}

	/** Not tried now, and not counted as an attempt: try again at {@code until} (a budget, for example). */
	record Deferred(Instant until) implements Outcome {

		public Deferred {
			Objects.requireNonNull(until, "until");
		}

	}

	/** Success without a result to share. */
	static Succeeded succeeded() {
		return new Succeeded(Map.of());
	}

	private static void requireCode(String code) {
		if (code == null || !code.matches("[A-Z][A-Z0-9_]*")) {
			throw new IllegalArgumentException("An outcome code is an upper-case constant, not '" + code + "'");
		}
	}

}
