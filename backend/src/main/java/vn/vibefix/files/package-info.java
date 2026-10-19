/**
 * Layer 1 (AD-3): stored files (AD-21). An upload is checked before it is stored: at most 10 MB, a
 * kind the caller allows detected by magic bytes only ({@code tika-core}, no container parsing), and
 * 30 uploads per account per hour (AD-34). Images are re-encoded by a sandbox job so no metadata
 * survives (AD-32). Files live on the files volume behind {@code files.internal.FileStore}, and are
 * served only by {@code GET /api/v1/files/{fileId}}, as attachments, after the owning module's
 * {@link vn.vibefix.files.FileReadPolicy} says yes (AD-20).
 * <p>
 * Public API: {@link vn.vibefix.files.StoredFiles}, {@link vn.vibefix.files.FileUpload},
 * {@link vn.vibefix.files.PreparedFile}, {@link vn.vibefix.files.StoredFile},
 * {@link vn.vibefix.files.FileKind} and the port {@link vn.vibefix.files.FileReadPolicy}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.files;

import org.springframework.modulith.ApplicationModule;
