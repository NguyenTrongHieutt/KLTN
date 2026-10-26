package vn.vibefix.files;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One file to check and store (AD-21).
 * @param purpose what the file is for, {@code {owning module}.{kind}}, for example
 * {@code work.attachment}; it names the {@link FileReadPolicy} that decides who may read it
 * @param accountId the uploader, whose hourly quota it counts against
 * @param fileName the name the client sent; it is normalized before it is kept
 * @param content the bytes as uploaded
 * @param allowed the kinds this upload accepts
 */
public record FileUpload(String purpose, UUID accountId, String fileName, byte[] content, Set<FileKind> allowed) {

	private static final Pattern PURPOSE = Pattern.compile("[a-z]+\\.[a-z0-9_]+");

	public FileUpload {
		Objects.requireNonNull(purpose, "purpose");
		if (!PURPOSE.matcher(purpose).matches()) {
			throw new IllegalArgumentException("A file purpose looks like module.kind, not '" + purpose + "'");
		}
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(fileName, "fileName");
		Objects.requireNonNull(content, "content");
		allowed = Set.copyOf(allowed);
		if (allowed.isEmpty()) {
			throw new IllegalArgumentException("An upload accepts at least one kind of file");
		}
	}

}
