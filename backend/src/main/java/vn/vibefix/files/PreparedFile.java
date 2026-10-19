package vn.vibefix.files;

import java.util.UUID;

/**
 * An upload that passed every check and, if it is an image, was re-encoded: what
 * {@link StoredFiles#store} keeps. Only {@link StoredFiles#prepare} makes one.
 */
public interface PreparedFile {

	String purpose();

	UUID accountId();

	/** The normalized name, with the extension of the stored type. */
	String fileName();

	String mediaType();

	FileKind kind();

	long size();

}
