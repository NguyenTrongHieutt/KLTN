package vn.vibefix.files.internal;

/**
 * The {@code reason} of a {@code files} refusal (AD-23); the SPA reads {@code reason.files.{REASON}}
 * in {@code vi.json} (EXPERIENCE.md, Messages from Error Codes).
 */
enum FilesReason {

	/** Above 10 MB. */
	FILE_TOO_LARGE,

	/** A kind the upload does not accept, SVG or HTML, or an image that cannot be read. */
	FILE_TYPE_NOT_ALLOWED,

	/** Past 30 uploads by the account in an hour (AD-34). */
	UPLOAD_QUOTA,

	/** An image above 40 megapixels, refused from its header (AD-21). */
	IMAGE_TOO_LARGE

}
