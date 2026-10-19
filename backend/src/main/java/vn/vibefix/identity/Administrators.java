package vn.vibefix.identity;

import java.util.List;
import java.util.UUID;

/** The Administrators as other modules see them (FR-2, SG-10): how many can act, and who, for alerts. */
public interface Administrators {

	/** Administrators who can sign in: enrolled, not locked, not erased. */
	long activeCount();

	/** The accounts of those Administrators, for the alerts every Administrator receives (AD-27). */
	List<UUID> activeIds();

}
