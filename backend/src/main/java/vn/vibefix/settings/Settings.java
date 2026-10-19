package vn.vibefix.settings;

import java.util.List;

/** The current rule values and school domains (FR-61), read live on every call. */
public interface Settings {

	/** The rule's current value, within its bounds. */
	int value(Rule rule);

	/**
	 * Whether {@code address} is on the school allowlist (FR-5): its domain, ignoring letter case, is
	 * an allowlisted domain or a subdomain of one, so {@code sis.hust.edu.vn} is allowed by
	 * {@code hust.edu.vn}. An address without exactly one {@code @} is not allowed.
	 */
	boolean allowsSchoolEmail(String address);

	/** The allowlisted school domains, in alphabetical order. */
	List<String> schoolDomains();

}
