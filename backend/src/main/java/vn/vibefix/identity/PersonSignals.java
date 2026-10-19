package vn.vibefix.identity;

import java.util.UUID;

/**
 * One person, one side (AD-19, D1): whether two accounts share a person-link signal, a verified
 * email address or a LinkedIn profile. Other modules ask only this; they never see a signal.
 */
public interface PersonSignals {

	boolean sharesPersonSignal(UUID accountId, UUID otherAccountId);

}
