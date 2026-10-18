package vn.vibefix.shared;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Something that keeps an account from being erased now (AD-24); the waiting deletion request
 * lists it.
 * @param module the module reporting it
 * @param reason the module's reason code, for example {@code JOB_RUNNING}
 * @param jobId the Job it concerns, when there is one
 */
public record ErasureBlocker(String module, String reason, @Nullable UUID jobId) {

	public ErasureBlocker {
		Objects.requireNonNull(module, "module");
		Objects.requireNonNull(reason, "reason");
	}

}
