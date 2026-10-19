package vn.vibefix.files.internal;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * {@code vibefix.files.*} (AD-21, AD-34).
 * @param root the files volume, where the bytes live
 * @param maxSize the largest upload: 10 MB
 * @param uploadsPerHour uploads per account in a rolling hour: 30
 */
@ConfigurationProperties("vibefix.files")
record FilesProperties(Path root, DataSize maxSize, int uploadsPerHour) {

	FilesProperties {
		if (root == null) {
			throw new IllegalArgumentException("vibefix.files.root names the files volume");
		}
		if (maxSize == null || maxSize.toBytes() < 1) {
			throw new IllegalArgumentException("The largest upload is at least one byte");
		}
		if (uploadsPerHour < 1) {
			throw new IllegalArgumentException("An account may upload at least one file an hour");
		}
	}

}
