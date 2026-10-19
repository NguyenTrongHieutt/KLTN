package vn.vibefix.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vibefix.identity.*}.
 * @param tokenKeyVersion the version of {@code identity-token-key-{n}} that seals new tokens (AD-22)
 * @param privacyNoticeVersion the current version of the personal-data notice N-10, which a sign-up
 * must name; the SPA shows the same value ({@code vi.json} {@code notice.privacy.version})
 * @param supportEmail where a locked account is told to write (FR-59)
 * @param signInLimitPerMinute sign-in attempts allowed per client address and minute (AD-34)
 * @param personSignalKeyVersion the version of {@code identity-person-signal-key-{n}} new person-link
 * signals are computed under (AD-19, AD-22)
 */
@ConfigurationProperties("vibefix.identity")
record IdentityProperties(int tokenKeyVersion, String privacyNoticeVersion, String supportEmail,
		int signInLimitPerMinute, int personSignalKeyVersion) {
}
