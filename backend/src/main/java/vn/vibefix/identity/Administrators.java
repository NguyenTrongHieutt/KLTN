package vn.vibefix.identity;

import java.util.List;
import java.util.UUID;

/**
 * The Administrators as other modules see them (FR-2, SG-10): how many can act, who, for alerts, and whether an
 * account is one.
 */
public interface Administrators {

	/** Administrators who can sign in: enrolled, not locked, not erased. */
	long activeCount();

	/** The accounts of those Administrators, for the alerts every Administrator receives (AD-27). */
	List<UUID> activeIds();

	/**
	 * Whether the account is of the {@code ADMINISTRATOR} kind, read from the database on every call (AD-19),
	 * enrolled or not and locked or not. An Administrator holds no role in any Job (AD-43).
	 */
	boolean isAdministrator(UUID accountId);

}
