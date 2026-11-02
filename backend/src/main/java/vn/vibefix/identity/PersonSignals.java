package vn.vibefix.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * One person, one side (AD-19, D1): whether two accounts share a person-link signal, a verified
 * email address or a LinkedIn profile. Other modules ask only this; they never see a signal.
 */
public interface PersonSignals {

	boolean sharesPersonSignal(UUID accountId, UUID otherAccountId);

	/**
	 * Two accounts that share a signal of {@code kind}: a pair by kind, never the signal itself (AD-19).
	 * @param kind {@code EMAIL} or {@code LINKEDIN}
	 */
	record SignalMatch(UUID accountId, UUID otherAccountId, String kind) {
	}

	/** The matches among these accounts, each pair and kind once, for a Split Evidence Package (AD-40). */
	List<SignalMatch> matchesAmong(Collection<UUID> accountIds);

}
