package vn.vibefix.shared.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/** Fans an export, erasure, or Product deletion out to every participant bean (AD-24). */
@Component
class ParticipantPersonalData implements PersonalData {

	private final List<PersonalDataParticipant> participants;

	ParticipantPersonalData(List<PersonalDataParticipant> participants) {
		this.participants = List.copyOf(participants);
	}

	@Override
	@Transactional(readOnly = true)
	public List<PersonalDataSection> export(UUID accountId) {
		List<PersonalDataSection> sections = new ArrayList<>();
		this.participants.forEach(participant -> participant.export(accountId).ifPresent(sections::add));
		return sections;
	}

	@Override
	@Transactional
	public ErasureOutcome erase(UUID accountId) {
		List<ErasureBlocker> blockers = erasureBlockers(accountId);
		if (!blockers.isEmpty()) {
			return new ErasureOutcome.Blocked(blockers);
		}
		this.participants.forEach(participant -> participant.eraseAccount(accountId));
		return new ErasureOutcome.Erased();
	}

	@Override
	@Transactional(readOnly = true)
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		List<ErasureBlocker> blockers = new ArrayList<>();
		this.participants.forEach(participant -> blockers.addAll(participant.erasureBlockers(accountId)));
		return blockers;
	}

	@Override
	@Transactional
	public void deleteProduct(UUID productId) {
		this.participants.forEach(participant -> participant.deleteProduct(productId));
	}

	@Override
	@Transactional
	public void retireJobData(UUID productId) {
		this.participants.forEach(participant -> participant.retireJobData(productId));
	}

}
