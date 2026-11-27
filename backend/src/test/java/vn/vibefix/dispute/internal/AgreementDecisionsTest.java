package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.vibefix.dispute.internal.AdminEvidenceController.EvidenceDecision;
import vn.vibefix.job.AgreementHistory.ConfirmedVersion;

/** How the Evidence Package tells that an Agreement version was confirmed (FR-53, AD-47, Story 25.3). */
class AgreementDecisionsTest {

	private static final Instant APPROVED = Instant.parse("2026-11-20T03:00:00Z");

	private static final Instant CONFIRMED = Instant.parse("2026-11-21T03:00:00Z");

	@Test
	void aNormalJobsVersionIsApprovedByTheCustomerAndThenConfirmedByTheMentor() {
		List<EvidenceDecision> lines = AdminEvidenceController
			.agreementDecisions(new ConfirmedVersion(2, APPROVED, CONFIRMED, "Phạm vi", 800_000L, List.of(), false));

		assertThat(lines).extracting(EvidenceDecision::kind).containsExactly("AGREEMENT_APPROVED", "AGREEMENT_CONFIRMED");
		assertThat(lines).extracting(EvidenceDecision::at).containsExactly(APPROVED, CONFIRMED);
		assertThat(lines).extracting(EvidenceDecision::versionNo).containsOnly(2);
	}

	@Test
	void aVersionConfirmedAloneIsOneStepAndNeverAnApproval() {
		List<EvidenceDecision> lines = AdminEvidenceController
			.agreementDecisions(new ConfirmedVersion(1, CONFIRMED, CONFIRMED, "Phạm vi", 800_000L, List.of(), true));

		assertThat(lines).extracting(EvidenceDecision::kind).containsExactly("AGREEMENT_CONFIRMED_ALONE");
		assertThat(lines.getFirst().at()).isEqualTo(CONFIRMED);
		assertThat(lines.getFirst().versionNo()).isEqualTo(1);
	}

}
