package vn.vibefix.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The files volume ({@code vibefix.files.root}): one file per ID under a directory named by the ID's
 * last two hex digits, written to a temporary name first and moved into place, so a reader never
 * sees half a file.
 */
@Component
class LocalFileStore implements FileStore {

	private final Path root;

	LocalFileStore(FilesProperties properties) {
		// Directories are created on the first write: a start that stores nothing (the CLI) needs none.
		this.root = properties.root().toAbsolutePath().normalize();
	}

	@Override
	public void write(UUID fileId, byte[] content) {
		Path target = path(fileId);
		try {
			Files.createDirectories(target.getParent());
			Path partial = target.resolveSibling(target.getFileName() + ".partial");
			Files.write(partial, content);
			try {
				Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public InputStream read(UUID fileId) {
		try {
			return Files.newInputStream(path(fileId));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public void delete(UUID fileId) {
		try {
			Files.deleteIfExists(path(fileId));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public boolean exists(UUID fileId) {
		return Files.exists(path(fileId));
	}

	private Path path(UUID fileId) {
		String name = fileId.toString();
		return this.root.resolve(name.substring(name.length() - 2)).resolve(name);
	}

}
