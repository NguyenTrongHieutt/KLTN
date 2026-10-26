package vn.vibefix.settings;

import java.util.List;
import java.util.Optional;

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

	/**
	 * The allowlisted domain that allows {@code address}, the most specific one when several do: the
	 * public school of a student verification (FR-5), since the allowlist has no school names yet (OQ8).
	 */
	Optional<String> schoolOf(String address);

	/** The allowlisted school domains, in alphabetical order. */
	List<String> schoolDomains();

}
