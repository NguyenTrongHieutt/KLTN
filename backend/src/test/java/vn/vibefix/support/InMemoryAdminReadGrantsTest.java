package vn.vibefix.support;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import vn.vibefix.shared.AdminReadGrant;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.Policies;

/**
 * Runs {@link AdminReadGrantsContract} against the smallest correct implementation, so the contract
 * itself is known to pass before the first real module implements it.
 */
class InMemoryAdminReadGrantsTest implements AdminReadGrantsContract {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T03:00:00Z"));

	private final InMemoryContent content = new InMemoryContent(this.clock);

	@Override
	public AdminReadGrants grants() {
		return this.content;
	}

	@Override
	public AdminReadScope scope() {
		return AdminReadScope.JOB_CHAT;
	}

	@Override
	public Seeded seed() {
		return new Seeded(UUID.randomUUID(), scope(), UUID.randomUUID());
	}

	@Override
	public void read(UUID adminId, Seeded item) {
		this.content.read(adminId, item);
	}

	@Override
	public long auditedReads(UUID adminId) {
		return this.content.audited.getOrDefault(adminId, new AtomicLong()).get();
	}

	@Override
	public Instant now() {
		return this.clock.instant();
	}

	@Override
	public void advance(Duration duration) {
		this.clock.advance(duration);
	}

	/** A module's Job content and its grants, held in memory. */
	static final class InMemoryContent implements AdminReadGrants {

		private final MutableClock clock;

		private final Map<UUID, AdminReadGrant> grants = new HashMap<>();

		final Map<UUID, AtomicLong> audited = new HashMap<>();

		InMemoryContent(MutableClock clock) {
			this.clock = clock;
		}

		@Override
		public void grantAdminRead(UUID grantId, UUID adminId, UUID jobId, Set<AdminReadItem> items, UUID reasonId) {
			this.grants.putIfAbsent(grantId, AdminReadGrant.open(grantId, adminId, jobId, items, reasonId));
		}

		@Override
		public void endAdminRead(UUID grantId, Instant at) {
			this.grants.computeIfPresent(grantId, (id, grant) -> grant.endingAt(at));
		}

		void read(UUID adminId, Seeded item) {
			Policies.requireVisible(this.grants.values()
				.stream()
				.anyMatch(grant -> grant.permits(adminId, item.jobId(), item.scope(), item.objectId(), List.of(),
						this.clock.instant())));
			this.audited.computeIfAbsent(adminId, id -> new AtomicLong()).incrementAndGet();
		}

	}

}
