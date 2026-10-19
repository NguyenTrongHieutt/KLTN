package vn.vibefix.timers;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** A Deadline whose time has come, as its handler sees it. */
public record DueDeadline(UUID id, String kind, UUID subjectId, String generation, @Nullable UUID jobId,
		Instant dueAt) {
}
