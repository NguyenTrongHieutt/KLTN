package vn.vibefix.matching.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.matching.internal.BidProofs.Item;
import vn.vibefix.matching.internal.BidProofs.ProofKind;
import vn.vibefix.matching.internal.BidProofs.Proof;
import vn.vibefix.matching.internal.BidProofs.Upload;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * A Crew Application's proof (FR-81, AD-20, AD-21, AD-43, X3, Story 21.4), by the bid proof's rules ({@link BidProofs}):
 * at most {@value BidProofs#MAX} items in the applicant's order, each a file that {@code files} checks and stores (an
 * image or a PDF) or an {@code https} link, with a caption. A file is uploaded before the application is sent and waits,
 * the applicant's alone, until "Gửi đơn" attaches it; the applicant may remove it before then. Only the applicant and
 * the Job's Mentor read an application's proof ({@link CrewApplicationProofFiles}); the Customer never does.
 */
@Component
class CrewApplicationProofs {

	static final String PURPOSE = "matching.crew_application_proof";

	/**
	 * Whose a stored proof file is: the applicant's, and once attached, that application's.
	 * @param withdrawn the application carrying it was withdrawn: the Mentor no longer sees it
	 */
	record Owner(UUID fileId, UUID jobId, UUID applicantId, @Nullable UUID applicationId, boolean withdrawn) {
	}

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final IdGenerator ids;

	private final Clock clock;

	CrewApplicationProofs(JdbcClient jdbc, StoredFiles files, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.ids = ids;
		this.clock = clock;
	}

	/** Checks a file outside any transaction: an image goes through the sandbox (AD-21). */
	PreparedFile prepare(UUID applicantId, String fileName, byte[] content) {
		return this.files.prepare(new FileUpload(PURPOSE, applicantId, fileName, content, BidProofs.KINDS));
	}

	/**
	 * Keeps a checked file for the applicant's application to the Job, under the applicant's lock (AD-4).
	 * @throws ApiException {@code VALIDATION PROOF_LIMIT} past {@value BidProofs#MAX} uploads waiting
	 */
	@Transactional
	Upload add(UUID applicantId, UUID jobId, UUID productId, PreparedFile file) {
		AdvisoryLocks.lockAccount(this.jdbc, applicantId);
		if (pending(jobId, applicantId).size() >= BidProofs.MAX) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PROOF_LIMIT);
		}
		StoredFile stored = this.files.store(file);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO matching.crew_application_proof (id, job_id, product_id, applicant_account_id, kind, file_id,
				    file_name, size_bytes, added_at)
				VALUES (:id, :job, :product, :applicant, 'FILE', :file, :name, :size, :now)
				""")
			.param("id", this.ids.newId())
			.param("job", jobId)
			.param("product", productId)
			.param("applicant", applicantId)
			.param("file", stored.id())
			.param("name", stored.fileName())
			.param("size", stored.size())
			.param("now", Timestamp.from(now))
			.update();
		return new Upload(stored.id(), jobId, applicantId, stored.fileName(), stored.size(), now);
	}

	/**
	 * Removes an upload no application took yet, and its file.
	 * @throws ApiException {@code NOT_FOUND} for anyone else's, or an attached one
	 */
	@Transactional
	void remove(UUID applicantId, UUID jobId, UUID fileId) {
		int removed = this.jdbc.sql("""
				DELETE FROM matching.crew_application_proof
				WHERE file_id = :file AND job_id = :job AND applicant_account_id = :applicant AND crew_application_id IS NULL
				""").param("file", fileId).param("job", jobId).param("applicant", applicantId).update();
		if (removed == 0) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.files.delete(fileId);
	}

	/** The applicant's uploads waiting for their application to the Job, oldest first. */
	@Transactional(readOnly = true)
	List<Upload> pending(UUID jobId, UUID applicantId) {
		return this.jdbc.sql("""
				SELECT file_id, job_id, applicant_account_id, file_name, size_bytes, added_at FROM matching.crew_application_proof
				WHERE job_id = :job AND applicant_account_id = :applicant AND crew_application_id IS NULL
				ORDER BY added_at, file_id
				""").param("job", jobId).param("applicant", applicantId).query((rs, n) -> new Upload(
				rs.getObject("file_id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("applicant_account_id", UUID.class), rs.getString("file_name"), rs.getLong("size_bytes"),
				rs.getTimestamp("added_at").toInstant())).list();
	}

	/**
	 * Attaches the proof to the application, in the caller's transaction and in the items' order: each file item takes
	 * the applicant's waiting upload, and each link item is written.
	 * @throws ApiException {@code VALIDATION PROOF_FILE_UNKNOWN} for a file that is not the applicant's waiting upload
	 * on the Job, or one named twice
	 */
	void attach(UUID applicationId, UUID jobId, UUID productId, UUID applicantId, List<Item> items) {
		Set<UUID> named = new HashSet<>();
		Instant now = this.clock.instant();
		for (int index = 0; index < items.size(); index++) {
			Item item = items.get(index);
			int position = index + 1;
			if (item.kind() == ProofKind.FILE) {
				int attached = named.add(item.fileId()) ? this.jdbc.sql("""
						UPDATE matching.crew_application_proof
						SET crew_application_id = :application, position = :position, caption = :caption
						WHERE file_id = :file AND job_id = :job AND applicant_account_id = :applicant
						    AND crew_application_id IS NULL
						""")
					.param("application", applicationId)
					.param("position", position)
					.param("caption", item.caption())
					.param("file", item.fileId())
					.param("job", jobId)
					.param("applicant", applicantId)
					.update() : 0;
				if (attached == 0) {
					throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PROOF_FILE_UNKNOWN);
				}
				continue;
			}
			this.jdbc.sql("""
					INSERT INTO matching.crew_application_proof (id, crew_application_id, job_id, product_id,
					    applicant_account_id, position, kind, url, caption, added_at)
					VALUES (:id, :application, :job, :product, :applicant, :position, 'LINK', :url, :caption, :now)
					""")
				.param("id", this.ids.newId())
				.param("application", applicationId)
				.param("job", jobId)
				.param("product", productId)
				.param("applicant", applicantId)
				.param("position", position)
				.param("url", item.url())
				.param("caption", item.caption())
				.param("now", Timestamp.from(now))
				.update();
		}
	}

	/** The proof of these applications, each in its order; an application without proof maps to an empty list. */
	@Transactional(readOnly = true)
	Map<UUID, List<Proof>> of(Collection<UUID> applicationIds) {
		Map<UUID, List<Proof>> proof = new LinkedHashMap<>();
		applicationIds.forEach(id -> proof.put(id, new ArrayList<>()));
		if (applicationIds.isEmpty()) {
			return proof;
		}
		this.jdbc.sql("""
				SELECT crew_application_id, position, kind, caption, file_id, file_name, size_bytes, url
				FROM matching.crew_application_proof WHERE crew_application_id IN (:ids) ORDER BY crew_application_id, position
				""").param("ids", List.copyOf(applicationIds)).query(CrewApplicationProofs::proof).list()
			.forEach(item -> proof.computeIfAbsent(item.applicationId(), id -> new ArrayList<>()).add(item));
		return proof;
	}

	/** Whose the stored proof file is, unless it is no Crew Application proof file. */
	@Transactional(readOnly = true)
	Optional<Owner> owner(UUID fileId) {
		return this.jdbc.sql("""
				SELECT p.file_id, p.job_id, p.applicant_account_id, p.crew_application_id, a.status = 'WITHDRAWN' AS withdrawn
				FROM matching.crew_application_proof p LEFT JOIN matching.crew_application a ON a.id = p.crew_application_id
				WHERE p.file_id = :file
				""").param("file", fileId).query((rs, n) -> new Owner(rs.getObject("file_id", UUID.class),
				rs.getObject("job_id", UUID.class), rs.getObject("applicant_account_id", UUID.class),
				rs.getObject("crew_application_id", UUID.class), rs.getBoolean("withdrawn"))).optional();
	}

	/**
	 * Deletes the proof rows {@code where} selects, files first, in the caller's transaction (AD-24).
	 * @param where a condition on {@code matching.crew_application_proof}'s columns, with named parameters from
	 * {@code params}
	 * @return how many rows were deleted
	 */
	int deleteWhere(String where, Map<String, ?> params) {
		JdbcClient.StatementSpec select = this.jdbc
			.sql("SELECT file_id FROM matching.crew_application_proof WHERE file_id IS NOT NULL AND (" + where + ")");
		JdbcClient.StatementSpec delete = this.jdbc.sql("DELETE FROM matching.crew_application_proof WHERE " + where);
		for (Map.Entry<String, ?> param : params.entrySet()) {
			select = select.param(param.getKey(), param.getValue());
			delete = delete.param(param.getKey(), param.getValue());
		}
		select.query(UUID.class).list().forEach(this.files::delete);
		return delete.update();
	}

	private static Proof proof(ResultSet rs, int n) throws SQLException {
		return new Proof(rs.getObject("crew_application_id", UUID.class), rs.getInt("position"),
				ProofKind.valueOf(rs.getString("kind")), rs.getString("caption"), rs.getObject("file_id", UUID.class),
				rs.getString("file_name"), rs.getObject("size_bytes", Long.class), rs.getString("url"));
	}

}
