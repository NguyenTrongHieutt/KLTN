package vn.vibefix.files.internal;

import java.io.InputStream;
import java.util.UUID;

/**
 * Where the bytes of stored files live (AD-21): the files volume today, behind this interface so an
 * object store can replace it. It knows IDs and bytes only, never names or owners.
 */
interface FileStore {

	/** Writes the file's bytes; an existing file with that ID is replaced. */
	void write(UUID fileId, byte[] content);

	/** The file's bytes; the caller closes the stream. */
	InputStream read(UUID fileId);

	/** Deletes the file's bytes; deleting a missing file changes nothing. */
	void delete(UUID fileId);

	boolean exists(UUID fileId);

}
