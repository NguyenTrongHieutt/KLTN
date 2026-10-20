package vn.vibefix.job;

import java.util.List;
import java.util.UUID;

/**
 * The Skills of a Job's confirmed Agreement (FR-50, AD-38), in their order, for the Portfolio Entries J9
 * freezes; empty when the Agreement names none, as an Assessment's does.
 */
public interface AgreementSkills {

	List<UUID> skillIdsOf(UUID jobId);

}
