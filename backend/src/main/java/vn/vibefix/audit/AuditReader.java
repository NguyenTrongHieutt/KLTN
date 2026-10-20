package vn.vibefix.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * ADM-8 (FR-63, SG-28): reading the Audit Log, newest first. The Job filter matches entries carrying the
 * Job and entries naming it as an object; the user filter matches entries by the account and entries
 * naming it. For Administrators only.
 */
public interface AuditReader {

	/** A page holds this many entries. */
	int PAGE_SIZE = 50;

	/** @param actorAccountId {@code null} for the System */
	record Entry(UUID id, Instant occurredAt, @Nullable UUID actorAccountId, String action, @Nullable UUID jobId,
			List<AuditObject> objects) {
	}

	record Page(List<Entry> items, long total, int page, int size) {
	}

	Page search(@Nullable UUID jobId, @Nullable UUID accountId, int page);

}
