package vn.vibefix.files;

import java.util.UUID;

import vn.vibefix.shared.Caller;

/**
 * Who may read the files of one purpose (AD-20 port): the owning module answers from its own data
 * before {@code files} serves a byte. A purpose without a policy is served to nobody.
 */
public interface FileReadPolicy {

	/** The purpose this policy answers for, for example {@code work.attachment}. */
	String purpose();

	/**
	 * Whether {@code caller} may read the file now. The owner may record the read here, for example
	 * as an audit entry (FR-63); it is called once per download.
	 */
	boolean mayRead(UUID fileId, Caller caller);

}
