package vn.vibefix.files.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFile;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * Stored files' part in AD-24. An export lists what the account uploaded and still has (name, type, size,
 * date), never the bytes, which the owning module's screens show to whoever may see them; a deleted file,
 * such as a retired student card (Story 19.7), is left out. An erasure deletes the account's files, bytes
 * first; the owning modules erase their own records of them.
 */
@Component
class FilesPersonalData implements PersonalDataParticipant {

	private final JdbcStoredFiles files;

	private final JdbcClient jdbc;

	FilesPersonalData(JdbcStoredFiles files, JdbcClient jdbc) {
		this.files = files;
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("files.stored_file");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<StoredFile> uploaded = this.files.keptBy(accountId);
		if (uploaded.isEmpty()) {
			return Optional.empty();
		}
		List<Map<String, Object>> rows = uploaded.stream()
			.map(file -> Map.<String, Object>of("fileName", file.fileName(), "mediaType", file.mediaType(), "size",
					file.size(), "storedAt", file.storedAt().toString()))
			.toList();
		return Optional.of(new PersonalDataSection("files", Map.of("uploads", rows)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		for (StoredFile file : this.files.uploadedBy(accountId)) {
			this.files.delete(file.id());
		}
		this.jdbc.sql("DELETE FROM files.stored_file WHERE account_id = :account").param("account", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		// A Product's files belong to its Jobs' records; the owning modules delete them with the Product.
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
