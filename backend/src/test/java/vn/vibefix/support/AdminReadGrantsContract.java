package vn.vibefix.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * The contract every implementation of {@link AdminReadGrants} must meet (AD-20, AD-8). A module
 * holding Job content ({@code chat}, {@code work}, {@code job}) implements this interface in a test
 * of its own story (usually an {@code IntegrationTest}) and fills in the hooks; the tests then prove
 * that it reads nothing without a grant, nothing outside a grant's items or after its end, and
 * audits every read a grant permits.
 */
public interface AdminReadGrantsContract {

	UUID ADMIN = UUID.fromString("0190f0c3-0000-7000-8000-00000000c001");

	UUID OTHER_ADMIN = UUID.fromString("0190f0c3-0000-7000-8000-00000000c002");

	UUID REASON = UUID.fromString("0190f0c3-0000-7000-8000-00000000c003");

	/** One seeded piece of Job content, as the serving module reads it. */
	record Seeded(UUID jobId, AdminReadScope scope, UUID objectId) {
	}

	/** The implementation under test. */
	AdminReadGrants grants();

	/** A scope this module serves, used for every test. */
	AdminReadScope scope();

	/** Seeds one object of {@link #scope()} on a new Job. */
	Seeded seed();

	/**
	 * Reads {@code item} as the Administrator {@code adminId} through the module's own read path,
	 * which throws {@link ApiException} with {@code NOT_FOUND} when no grant permits it.
	 */
	void read(UUID adminId, Seeded item);

	/** How many audit entries the module wrote for reads by {@code adminId}. */
	long auditedReads(UUID adminId);

	/** The time the module reads its clock at; tests move it with {@link #advance}. */
	Instant now();

	void advance(Duration duration);

	@Test
	default void anAdministratorWithoutAGrantReadsNothing() {
		Seeded item = seed();

		assertNotFound(() -> read(ADMIN, item));
		assertThat(auditedReads(ADMIN)).isZero();
	}

	@Test
	default void anOpenGrantPermitsItsItemsAndEveryReadIsAudited() {
		Seeded item = seed();
		grants().grantAdminRead(UUID.randomUUID(), ADMIN, item.jobId(), Set.of(AdminReadItem.all(scope())), REASON);

		read(ADMIN, item);
		read(ADMIN, item);

		assertThat(auditedReads(ADMIN)).isEqualTo(2);
		assertNotFound(() -> read(OTHER_ADMIN, item));
	}

	@Test
	default void aGrantDoesNotReachOutsideItsItems() {
		Seeded item = seed();
		grants().grantAdminRead(UUID.randomUUID(), ADMIN, item.jobId(),
				Set.of(AdminReadItem.one(scope(), UUID.randomUUID())), REASON);

		assertNotFound(() -> read(ADMIN, item));
	}

	@Test
	default void aGrantForAnotherJobDoesNotApply() {
		Seeded item = seed();
		grants().grantAdminRead(UUID.randomUUID(), ADMIN, UUID.randomUUID(), Set.of(AdminReadItem.all(scope())),
				REASON);

		assertNotFound(() -> read(ADMIN, item));
	}

	@Test
	default void anEndedGrantStopsAtItsEnd() {
		Seeded item = seed();
		UUID grant = UUID.randomUUID();
		grants().grantAdminRead(grant, ADMIN, item.jobId(), Set.of(AdminReadItem.all(scope())), REASON);
		grants().endAdminRead(grant, now().plus(Duration.ofDays(30)));

		advance(Duration.ofDays(29));
		read(ADMIN, item);
		advance(Duration.ofDays(1));
		assertNotFound(() -> read(ADMIN, item));
		assertThat(auditedReads(ADMIN)).isOne();
	}

	@Test
	default void reassignmentEndsTheGrantAtOnce() {
		Seeded item = seed();
		UUID grant = UUID.randomUUID();
		grants().grantAdminRead(grant, ADMIN, item.jobId(), Set.of(AdminReadItem.all(scope())), REASON);
		grants().endAdminRead(grant, now());

		assertNotFound(() -> read(ADMIN, item));
	}

	private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable read) {
		assertThatThrownBy(read).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}

}
