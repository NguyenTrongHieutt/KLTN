package vn.vibefix.delivery;

import java.util.UUID;

import vn.vibefix.shared.Caller;

/**
 * Who reads a handover's images besides the Job's own readers (FR-53, FR-63, FR-84, AD-8, AD-20, AD-31, AD-40): the
 * Administrator assigned to a case whose Evidence Package shows that handover, while the case lets them read it. The
 * port is {@code delivery}'s, and the module that owns the cases answers it.
 * <p>
 * It is a check, not a grant. {@code delivery} keeps no grant, no grant scope and no state behind it: each read asks
 * the case as it stands at that moment, so a takeover, a withdrawal or the end of the reading period ends it with
 * nothing to revoke. {@code delivery} asks it only for an image a handover carries, never for an upload no handover
 * took yet, and only for an Administrator its own readers refused.
 */
public interface HandoverEvidenceReaders {

	/**
	 * Whether {@code caller} may see the image {@code fileId} of a handover of the Milestone {@code milestoneId} on the
	 * Job {@code jobId} now. A yes is the read: the implementation writes it to the Audit Log in the same call, naming
	 * the case and the image, so each image read is its own entry.
	 * @param fileId the stored file of the image, as {@code /api/v1/files/{fileId}} names it
	 */
	boolean mayRead(UUID jobId, UUID milestoneId, UUID fileId, Caller caller);

}
