package vn.vibefix.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * AD-8: an entry holds internal IDs and codes only, so it can never carry personal data, and the
 * audit API offers no way to change or remove an entry (FR-63).
 */
class AuditEntryTest {

	private static final UUID ID = UUID.fromString("0190f0c3-0000-7000-8000-000000000001");

	@Test
	void actionsAndObjectTypesAreDottedModuleCodes() {
		assertThat(AuditEntry.of(AuditActor.system(), "payment.release_written", AuditObject.of("payment.order", ID))
			.action()).isEqualTo("payment.release_written");

		for (String code : List.of("released", "Payment.release", "payment.", "payment.release written",
				"payment.nguyễn", "payment.a@b.c", "")) {
			assertThatThrownBy(() -> AuditEntry.of(AuditActor.system(), code, AuditObject.of("payment.order", ID)))
				.as(code)
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> AuditObject.of(code, ID)).as(code).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void anEntryNamesAtLeastOneObject() {
		assertThatThrownBy(() -> AuditEntry.of(AuditActor.system(), "payment.release_written"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void objectsAreAnImmutableCopy() {
		AuditEntry entry = AuditEntry.of(AuditActor.user(ID), "job.job_published", AuditObject.of("job.job", ID));

		assertThatThrownBy(() -> entry.objects().add(AuditObject.of("job.job", ID)))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(entry.jobId()).isNull();
		assertThat(entry.forJob(ID).jobId()).isEqualTo(ID);
	}

	@Test
	void aUserActorNeedsAnAccount() {
		assertThatThrownBy(() -> AuditActor.user(null)).isInstanceOf(NullPointerException.class);
		assertThat(AuditActor.system().accountId()).isNull();
	}

	@Test
	void theAuditApiOnlyRecords() {
		List<String> methods = Arrays.stream(AuditLog.class.getMethods())
			.filter(method -> !Modifier.isStatic(method.getModifiers()))
			.map(Method::getName)
			.toList();

		assertThat(methods).containsExactly("record");
	}

}
