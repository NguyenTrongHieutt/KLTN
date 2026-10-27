package vn.vibefix.shared;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Something that keeps an account from being erased now (AD-24); the waiting deletion request
 * lists it, on ALL-2 and ADM-2.
 * @param module the module reporting it
 * @param reason the module's reason code, for example {@code JOB_RUNNING}
 * @param jobId the Job it concerns, when there is one
 * @param name the title of that Job, when there is one (Story 20.4)
 */
public record ErasureBlocker(String module, String reason, @Nullable UUID jobId, @Nullable String name) {

	public ErasureBlocker {
		Objects.requireNonNull(module, "module");
		Objects.requireNonNull(reason, "reason");
	}

}
