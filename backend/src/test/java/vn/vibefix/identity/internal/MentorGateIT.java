package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/** FR-74, AD-19: the two Mentor checks, read from the database on every call. */
class MentorGateIT extends IntegrationTest {

	@Autowired
	private MentorGate gate;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void onlyAnApprovedMentorPassesTheNewWorkCheck() {
		for (MentorApprovalStatus status : new MentorApprovalStatus[] { MentorApprovalStatus.PENDING,
				MentorApprovalStatus.REJECTED, MentorApprovalStatus.REVOKED }) {
			UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, status, null);
			refused(() -> this.gate.requireApprovedMentor(mentor), "MENTOR_NOT_APPROVED");
		}
		UUID approved = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.APPROVED, null);
		assertThatCode(() -> this.gate.requireApprovedMentor(approved)).doesNotThrowAnyException();
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		refused(() -> this.gate.requireApprovedMentor(customer), "MENTOR_NOT_APPROVED");
	}

	@Test
	void aRevokedMentorKeepsJobCommands() {
		UUID revoked = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.REVOKED, null);

		assertThatCode(() -> this.gate.requireActiveAccount(revoked)).doesNotThrowAnyException();
	}

	@Test
	void bothChecksRefuseALockedAccountAndTheStatusIsReadEachTime() {
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.APPROVED, null);
		this.gate.requireApprovedMentor(mentor);

		IdentityTestSupport.lock(this.jdbc, mentor, "gate test");

		refused(() -> this.gate.requireApprovedMentor(mentor), "ACCOUNT_LOCKED");
		refused(() -> this.gate.requireActiveAccount(mentor), "ACCOUNT_LOCKED");
		refused(() -> this.gate.requireActiveAccount(UUID.randomUUID()), "ACCOUNT_LOCKED");
	}

	@Test
	void aRevocationAppliesToTheNextCall() {
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.APPROVED, null);
		this.gate.requireApprovedMentor(mentor);

		this.jdbc.sql("UPDATE identity.account SET mentor_approval_status = 'REVOKED' WHERE id = :id")
			.param("id", mentor)
			.update();

		refused(() -> this.gate.requireApprovedMentor(mentor), "MENTOR_NOT_APPROVED");
	}

	private static void refused(ThrowingCallable call, String reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN);
			assertThat(ex.reason()).hasValue(reason);
		});
	}

}
