package vn.vibefix.files;

/**
 * The kinds of file an upload may be (AD-21); each upload names the kinds it accepts. Anything else,
 * SVG and HTML included, is refused.
 */
public enum FileKind {

	/** JPEG, PNG, GIF, or BMP: the raster formats the sandbox can re-encode. */
	IMAGE,

	/** {@code application/pdf}. */
	PDF,

	/** {@code text/plain}. */
	TEXT,

	/** ZIP, 7z, RAR, gzip, tar, bzip2, or xz, stored opaque: it may contain programs. */
	ARCHIVE

}
