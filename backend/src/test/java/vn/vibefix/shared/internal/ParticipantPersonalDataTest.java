package vn.vibefix.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/** AD-24: exports collect every section, and an erasure runs only when nobody blocks it. */
class ParticipantPersonalDataTest {

	private static final UUID ACCOUNT = UUID.fromString("0190f0c3-0000-7000-8000-00000000d001");

	private static final UUID JOB = UUID.fromString("0190f0c3-0000-7000-8000-00000000d002");

	private final FakeParticipant identity = new FakeParticipant("identity", List.of());

	private final FakeParticipant job = new FakeParticipant("job", List.of());

	@Test
	void exportCollectsTheSectionsOfEveryParticipantThatHasData() {
		FakeParticipant empty = new FakeParticipant("files", List.of());
		empty.section = null;

		List<PersonalDataSection> sections = new ParticipantPersonalData(List.of(this.identity, empty, this.job))
			.export(ACCOUNT);

		assertThat(sections).extracting(PersonalDataSection::module).containsExactly("identity", "job");
	}

	@Test
	void erasureRunsThroughEveryParticipantWhenNothingBlocksIt() {
		ErasureOutcome outcome = new ParticipantPersonalData(List.of(this.identity, this.job)).erase(ACCOUNT);

		assertThat(outcome).isInstanceOf(ErasureOutcome.Erased.class);
		assertThat(this.identity.erased).containsExactly(ACCOUNT);
		assertThat(this.job.erased).containsExactly(ACCOUNT);
	}

	@Test
	void aBlockerStopsTheWholeErasure() {
		ErasureBlocker running = new ErasureBlocker("job", "JOB_RUNNING", JOB, "Sửa lỗi đặt bàn");
		FakeParticipant blocking = new FakeParticipant("job", List.of(running));

		ErasureOutcome outcome = new ParticipantPersonalData(List.of(this.identity, blocking)).erase(ACCOUNT);

		assertThat(outcome).isEqualTo(new ErasureOutcome.Blocked(List.of(running)));
		assertThat(this.identity.erased).isEmpty();
		assertThat(blocking.erased).isEmpty();
	}

	@Test
	void productDeletionReachesEveryParticipant() {
		UUID product = UUID.fromString("0190f0c3-0000-7000-8000-00000000d003");

		new ParticipantPersonalData(List.of(this.identity, this.job)).deleteProduct(product);

		assertThat(this.identity.deletedProducts).containsExactly(product);
		assertThat(this.job.deletedProducts).containsExactly(product);
	}

	static final class FakeParticipant implements PersonalDataParticipant {

		private final String module;

		private final List<ErasureBlocker> blockers;

		PersonalDataSection section;

		final List<UUID> erased = new ArrayList<>();

		final List<UUID> deletedProducts = new ArrayList<>();

		FakeParticipant(String module, List<ErasureBlocker> blockers) {
			this.module = module;
			this.blockers = blockers;
			this.section = new PersonalDataSection(module, Map.of("k", "v"));
		}

		@Override
		public Set<String> tables() {
			return Set.of(this.module + ".t");
		}

		@Override
		public Optional<PersonalDataSection> export(UUID accountId) {
			return Optional.ofNullable(this.section);
		}

		@Override
		public void eraseAccount(UUID accountId) {
			this.erased.add(accountId);
		}

		@Override
		public void deleteProduct(UUID productId) {
			this.deletedProducts.add(productId);
		}

		@Override
		public List<ErasureBlocker> erasureBlockers(UUID accountId) {
			return this.blockers;
		}

	}

}
