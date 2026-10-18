package vn.vibefix.notification;

import java.util.UUID;

/**
 * The to-do store (AD-27, UX-DR15): the module that owns an expected action opens and closes it;
 * the next-action strip and the to-do lists read only from here. Users never delete a to-do.
 */
public interface Todos {

	/**
	 * Opens the to-do in the caller's transaction; opening one that is already open changes nothing.
	 * @throws IllegalArgumentException as for {@link Notifications#send}
	 */
	void open(TodoRequest request);

	/** Closes the recipient's open to-do, if any. */
	void close(String key, UUID recipientAccountId, UUID subjectId);

	/** Closes every recipient's open to-do of {@code key} about {@code subjectId}. */
	void closeAll(String key, UUID subjectId);

}
