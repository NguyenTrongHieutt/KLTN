package vn.vibefix.matching.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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

import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.HttpsLinks;
import vn.vibefix.shared.IdGenerator;

/**
 * A bid's proof of competence (FR-13, AD-20, AD-21, X3): at most {@value #MAX} items in the bidder's order, each a
 * file that {@code files} checks and stores (an image or a PDF under NFR-3's size and hourly quota) or an {@code https}
 * link by the shared rule ({@link HttpsLinks}), with a caption of 1 to {@value #MAX_CAPTION} characters. A file is
 * uploaded before the bid is sent and waits, the uploader's alone, until "Gửi chào giá" attaches it, as a handover's
 * screenshots do; the uploader may remove it before then. Only the bidder and the Job's Customer read a bid's proof
 * ({@link BidProofFiles}). A bid that ends unselected keeps its proof for {@link #RETENTION} ({@link BidProofPurge}).
 */
@Component
class BidProofs {

	static final String PURPOSE = "matching.bid_proof";

	/** Proof items per bid, files and links together. */
	static final int MAX = 5;

	static final int MAX_CAPTION = 200;

	/** What a proof file may be (AD-21): an image, re-encoded, or a PDF. */
	static final Set<FileKind> KINDS = Set.of(FileKind.IMAGE, FileKind.PDF);

	/** How long an unselected bid's proof, or an upload no bid took, is kept (X3). */
	static final Duration RETENTION = Duration.ofDays(90);

	enum ProofKind {
		FILE, LINK
	}

	/** One proof item as the bidder sends it: a waiting upload's file, or a link. */
	record Item(ProofKind kind, @Nullable UUID fileId, @Nullable String url, String caption) {
	}

	/** An upload waiting for the bidder's bid on the Job. */
	record Upload(UUID fileId, UUID jobId, UUID bidderId, String fileName, long size, Instant addedAt) {
	}

	/** One proof item of a sent bid, in its position. */
	record Proof(UUID applicationId, int position, ProofKind kind, String caption, @Nullable UUID fileId,
			@Nullable String fileName, @Nullable Long size, @Nullable String url) {

		/** A link's domain, which every screen shows beside it; empty for a file. */
		String domain() {
			return HttpsLinks.domain(this.url);
		}

	}

	/**
	 * Whose a stored proof file is: the bidder's, and once attached, that bid's.
	 * @param withdrawn the bid carrying it was withdrawn: the Customer no longer sees it
	 */
	record Owner(UUID fileId, UUID jobId, UUID bidderId, @Nullable UUID applicationId, boolean withdrawn) {
	}

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final IdGenerator ids;

	private final Clock clock;

	BidProofs(JdbcClient jdbc, StoredFiles files, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * The proof as sent, checked before anything is read or written: at most {@value #MAX} items, each link kept by the
	 * shared rule, each caption stripped.
	 * @throws ApiException {@code VALIDATION PROOF_LIMIT} for a sixth item; {@code VALIDATION LINK_INVALID} for a link
	 * the rule does not keep; {@code VALIDATION PROOF_FILE_UNKNOWN} for a file item without its file
	 */
	static List<Item> requireShape(List<Item> items) {
		if (items.size() > MAX) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PROOF_LIMIT);
		}
		List<Item> shaped = new ArrayList<>();
		for (Item item : items) {
			String caption = item.caption().strip();
			if (item.kind() == ProofKind.LINK) {
				String url = HttpsLinks.valid(item.url())
					.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION, MatchingReason.LINK_INVALID));
				shaped.add(new Item(ProofKind.LINK, null, url, caption));
			}
			else {
				if (item.fileId() == null) {
					throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PROOF_FILE_UNKNOWN);
				}
				shaped.add(new Item(ProofKind.FILE, item.fileId(), null, caption));
			}
		}
		return List.copyOf(shaped);
	}

	/** Checks a file outside any transaction: an image goes through the sandbox (AD-21). */
	PreparedFile prepare(UUID bidderId, String fileName, byte[] content) {
		return this.files.prepare(new FileUpload(PURPOSE, bidderId, fileName, content, KINDS));
	}

	/**
	 * Keeps a checked file for the bidder's bid on the Job, under the bidder's lock (AD-4).
	 * @throws ApiException {@code VALIDATION PROOF_LIMIT} past {@value #MAX} uploads waiting
	 */
	@Transactional
	Upload add(UUID bidderId, UUID jobId, UUID productId, PreparedFile file) {
		AdvisoryLocks.lockAccount(this.jdbc, bidderId);
		if (pending(jobId, bidderId).size() >= MAX) {
			throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PROOF_LIMIT);
		}
		StoredFile stored = this.files.store(file);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO matching.bid_proof (id, job_id, product_id, bidder_account_id, kind, file_id, file_name, size_bytes,
				    added_at)
				VALUES (:id, :job, :product, :bidder, 'FILE', :file, :name, :size, :now)
				""")
			.param("id", this.ids.newId())
			.param("job", jobId)
			.param("product", productId)
			.param("bidder", bidderId)
			.param("file", stored.id())
			.param("name", stored.fileName())
			.param("size", stored.size())
			.param("now", Timestamp.from(now))
			.update();
		return new Upload(stored.id(), jobId, bidderId, stored.fileName(), stored.size(), now);
	}

	/**
	 * Removes an upload no bid took yet, and its file.
	 * @throws ApiException {@code NOT_FOUND} for anyone else's, or an attached one
	 */
	@Transactional
	void remove(UUID bidderId, UUID jobId, UUID fileId) {
		int removed = this.jdbc.sql("""
				DELETE FROM matching.bid_proof
				WHERE file_id = :file AND job_id = :job AND bidder_account_id = :bidder AND application_id IS NULL
				""").param("file", fileId).param("job", jobId).param("bidder", bidderId).update();
		if (removed == 0) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.files.delete(fileId);
	}

	/** The bidder's uploads waiting for their bid on the Job, oldest first. */
	@Transactional(readOnly = true)
	List<Upload> pending(UUID jobId, UUID bidderId) {
		return this.jdbc.sql("""
				SELECT file_id, job_id, bidder_account_id, file_name, size_bytes, added_at FROM matching.bid_proof
				WHERE job_id = :job AND bidder_account_id = :bidder AND application_id IS NULL ORDER BY added_at, file_id
				""").param("job", jobId).param("bidder", bidderId).query((rs, n) -> new Upload(rs.getObject("file_id", UUID.class),
				rs.getObject("job_id", UUID.class), rs.getObject("bidder_account_id", UUID.class), rs.getString("file_name"),
				rs.getLong("size_bytes"), rs.getTimestamp("added_at").toInstant())).list();
	}

	/**
	 * Attaches the proof to the bid, in the caller's transaction and in the items' order: each file item takes the
	 * bidder's waiting upload, and each link item is written.
	 * @throws ApiException {@code VALIDATION PROOF_FILE_UNKNOWN} for a file that is not the bidder's waiting upload on
	 * the Job, or one named twice
	 */
	void attach(UUID applicationId, UUID jobId, UUID productId, UUID bidderId, List<Item> items) {
		Set<UUID> named = new HashSet<>();
		Instant now = this.clock.instant();
		for (int index = 0; index < items.size(); index++) {
			Item item = items.get(index);
			int position = index + 1;
			if (item.kind() == ProofKind.FILE) {
				int attached = named.add(item.fileId()) ? this.jdbc.sql("""
						UPDATE matching.bid_proof SET application_id = :application, position = :position, caption = :caption
						WHERE file_id = :file AND job_id = :job AND bidder_account_id = :bidder AND application_id IS NULL
						""")
					.param("application", applicationId)
					.param("position", position)
					.param("caption", item.caption())
					.param("file", item.fileId())
					.param("job", jobId)
					.param("bidder", bidderId)
					.update() : 0;
				if (attached == 0) {
					throw new ApiException(ErrorCode.VALIDATION, MatchingReason.PROOF_FILE_UNKNOWN);
				}
				continue;
			}
			this.jdbc.sql("""
					INSERT INTO matching.bid_proof (id, application_id, job_id, product_id, bidder_account_id, position, kind, url,
					    caption, added_at)
					VALUES (:id, :application, :job, :product, :bidder, :position, 'LINK', :url, :caption, :now)
					""")
				.param("id", this.ids.newId())
				.param("application", applicationId)
				.param("job", jobId)
				.param("product", productId)
				.param("bidder", bidderId)
				.param("position", position)
				.param("url", item.url())
				.param("caption", item.caption())
				.param("now", Timestamp.from(now))
				.update();
		}
	}

	/** The proof of these bids, each bid's in its order; a bid without proof maps to an empty list. */
	@Transactional(readOnly = true)
	Map<UUID, List<Proof>> of(Collection<UUID> applicationIds) {
		Map<UUID, List<Proof>> proof = new LinkedHashMap<>();
		applicationIds.forEach(id -> proof.put(id, new ArrayList<>()));
		if (applicationIds.isEmpty()) {
			return proof;
		}
		this.jdbc.sql("""
				SELECT application_id, position, kind, caption, file_id, file_name, size_bytes, url FROM matching.bid_proof
				WHERE application_id IN (:ids) ORDER BY application_id, position
				""").param("ids", List.copyOf(applicationIds)).query(BidProofs::proof).list()
			.forEach(item -> proof.computeIfAbsent(item.applicationId(), id -> new ArrayList<>()).add(item));
		return proof;
	}

	/** Whose the stored proof file is, unless it is no proof file. */
	@Transactional(readOnly = true)
	Optional<Owner> owner(UUID fileId) {
		return this.jdbc.sql("""
				SELECT p.file_id, p.job_id, p.bidder_account_id, p.application_id, a.status = 'WITHDRAWN' AS withdrawn
				FROM matching.bid_proof p LEFT JOIN matching.application a ON a.id = p.application_id WHERE p.file_id = :file
				""").param("file", fileId).query((rs, n) -> new Owner(rs.getObject("file_id", UUID.class),
				rs.getObject("job_id", UUID.class), rs.getObject("bidder_account_id", UUID.class),
				rs.getObject("application_id", UUID.class), rs.getBoolean("withdrawn"))).optional();
	}

	/**
	 * Deletes the proof rows {@code where} selects, files first, in the caller's transaction (AD-24).
	 * @param where a condition on {@code matching.bid_proof}'s columns, with named parameters from {@code params}
	 * @return how many rows were deleted
	 */
	int deleteWhere(String where, Map<String, ?> params) {
		JdbcClient.StatementSpec select = this.jdbc.sql("SELECT file_id FROM matching.bid_proof WHERE file_id IS NOT NULL AND (" + where + ")");
		JdbcClient.StatementSpec delete = this.jdbc.sql("DELETE FROM matching.bid_proof WHERE " + where);
		for (Map.Entry<String, ?> param : params.entrySet()) {
			select = select.param(param.getKey(), param.getValue());
			delete = delete.param(param.getKey(), param.getValue());
		}
		select.query(UUID.class).list().forEach(this.files::delete);
		return delete.update();
	}

	private static Proof proof(ResultSet rs, int n) throws SQLException {
		return new Proof(rs.getObject("application_id", UUID.class), rs.getInt("position"), ProofKind.valueOf(rs.getString("kind")),
				rs.getString("caption"), rs.getObject("file_id", UUID.class), rs.getString("file_name"),
				rs.getObject("size_bytes", Long.class), rs.getString("url"));
	}

}
