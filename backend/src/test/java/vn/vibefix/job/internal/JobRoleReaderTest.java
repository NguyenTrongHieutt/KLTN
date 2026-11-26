package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.Administrators;
import vn.vibefix.job.Crews.CrewRelationship;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Kind;

/** AD-43: an anonymous caller and an Administrator hold no role in any Job, answered without a read. */
class JobRoleReaderTest {

	@Test
	void anAnonymousCallerAndAnAdministratorGetNoneWithoutARead() {
		JobRoleReader reader = new JobRoleReader(unread(JdbcClient.class), null, unread(Administrators.class));
		UUID job = UUID.randomUUID();

		assertThat(reader.roleOf(job, Caller.anonymous())).isEqualTo(JobRole.NONE);
		assertThat(reader.roleOf(job, Caller.account(UUID.randomUUID(), Kind.ADMINISTRATOR)))
			.isEqualTo(JobRole.NONE);
	}

	/**
	 * AD-47, Story 25.2a: the combined role is read from the Job's mode, never from the Customer and the Mentor being one
	 * account, and only once the Job has its Mentor. The schema refuses the first row below; the reader must not rely on
	 * that.
	 */
	@Test
	void theCombinedRoleComesFromTheModeAndNeverFromEqualAccounts() {
		JobRoleReader reader = new JobRoleReader(unread(JdbcClient.class), null, unread(Administrators.class));
		UUID customer = UUID.randomUUID();
		Caller caller = Caller.account(customer, Kind.USER);

		assertThat(reader.roleOf(jobOf(customer, customer, false), caller)).as("equal accounts, normal mode")
			.isEqualTo(JobRole.CUSTOMER);
		assertThat(reader.roleOf(jobOf(customer, null, true), caller)).as("the mode chosen, no Mentor yet")
			.isEqualTo(JobRole.CUSTOMER);
		assertThat(reader.roleOf(jobOf(customer, customer, true), caller)).isEqualTo(JobRole.CUSTOMER_MENTOR);
		assertThat(reader.roleOf(jobOf(customer, customer, true), Caller.account(customer, Kind.ADMINISTRATOR)))
			.as("an Administrator holds no role").isEqualTo(JobRole.NONE);
	}

	private static Job jobOf(UUID customer, @Nullable UUID mentor, boolean selfMentored) {
		return new Job(UUID.randomUUID(), UUID.randomUUID(), customer, JobState.ACTIVE, "Việc", null, null, null, null,
				List.of(JobType.BUG_FIX), List.of(), List.of(), null, null, Instant.EPOCH, Instant.EPOCH, 1, null, null,
				null, 1, null, mentor, null, Instant.EPOCH, selfMentored);
	}

	@Test
	void theOverloadJobPoliciesCallAnswersThemWithoutAReadToo() {
		JobRoleReader reader = new JobRoleReader(unread(JdbcClient.class), null, unread(Administrators.class));
		UUID admin = UUID.randomUUID();
		// An Administrator named as the Job's Customer, which no command writes, still holds no role in it.
		Job job = new Job(UUID.randomUUID(), UUID.randomUUID(), admin, JobState.ACTIVE, "Việc", null, null, null, null,
				List.of(JobType.BUG_FIX), List.of(), List.of(), null, null, Instant.EPOCH, Instant.EPOCH, 1, null, null,
				null, 1, null, UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH, false);

		assertThat(reader.roleOf(job, Caller.anonymous())).isEqualTo(JobRole.NONE);
		assertThat(reader.roleOf(job, Caller.account(admin, Kind.ADMINISTRATOR)))
			.isEqualTo(JobRole.NONE);
	}

	@Test
	void aCrewRoleCarriesItsRelationshipAndNeverNone() {
		assertThat(JobRole.crew(CrewRelationship.NONE)).isEqualTo(JobRole.NONE);
		JobRole active = JobRole.crew(new CrewRelationship(Relationship.ACTIVE, null, null));
		assertThat(active.isCrew()).isTrue();
		assertThat(active.crewKind()).isEqualTo(Relationship.ACTIVE);
		assertThat(JobRole.CUSTOMER.crewKind()).isEqualTo(Relationship.NONE);
		assertThat(JobRole.MENTOR.isMentor()).isTrue();
		assertThat(JobRole.NONE.isCustomer() || JobRole.NONE.isMentor() || JobRole.NONE.isCrew()).isFalse();
	}

	/** A collaborator whose every call fails the test: the code under test must not read through it. */
	@SuppressWarnings("unchecked")
	private static <T> T unread(Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				return method.getName().equals("hashCode") ? 0 : method.getName().equals("equals") ? proxy == args[0]
						: type.getSimpleName();
			}
			throw new AssertionError("read through " + type.getSimpleName() + "." + method.getName());
		});
	}

}
