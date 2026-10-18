package vn.vibefix.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * The contract every {@link PersonalDataParticipant} must meet (AD-24, AD-20). A module implements
 * this interface in a test of its own story and fills in the hooks: an export never holds another
 * person's data, and an erasure removes the subject's erasable data and nobody else's.
 */
public interface PersonalDataParticipantContract {

	UUID SUBJECT = UUID.fromString("0190f0c3-0000-7000-8000-00000000e001");

	UUID OTHER = UUID.fromString("0190f0c3-0000-7000-8000-00000000e002");

	PersonalDataParticipant participant();

	/**
	 * Seeds data of {@link #SUBJECT} and of {@link #OTHER} in the participant's tables, and returns a
	 * marker that appears only in {@link #OTHER}'s data.
	 */
	String seedSubjectAndOther();

	/** Whether the participant still holds data of {@code accountId} that an erasure must remove. */
	boolean holdsErasableDataOf(UUID accountId);

	@Test
	default void anExportHoldsNothingOfAnotherPerson() {
		String othersMarker = seedSubjectAndOther();

		String export = participant().export(SUBJECT).map(PersonalDataSection::toString).orElse("");

		assertThat(export).doesNotContain(othersMarker).doesNotContain(OTHER.toString());
	}

	@Test
	default void anErasureRemovesTheSubjectsDataOnly() {
		seedSubjectAndOther();
		assertThat(holdsErasableDataOf(SUBJECT)).as("seeded data of the subject").isTrue();

		participant().eraseAccount(SUBJECT);

		assertThat(holdsErasableDataOf(SUBJECT)).isFalse();
		assertThat(holdsErasableDataOf(OTHER)).isTrue();
	}

}
