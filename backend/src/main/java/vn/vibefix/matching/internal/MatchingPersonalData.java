package vn.vibefix.matching.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.GitHubLinkBlocker;
import vn.vibefix.identity.GitHubLinkParticipant;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code matching}'s part in exports, erasure, Product deletion, and Job data retention (AD-24): a bidder exports
 * their own bids with their proof, their waiting proof uploads, and NDA signatures, and both sides export the
 * invitations to apply they sent or received; erasing a bidder or a Customer deletes the bids, their proof with its
 * files, and the invitations they made or received, and deleting a Product deletes those to its Jobs. At the end of
 * the Job data's retention (NFR-10) the proof files of the Product's bids go, the bids stay. An applicant exports their
 * Crew Applications with their proof and waiting uploads; erasing the applicant, the Customer or the Mentor deletes
 * them (the applicant's pending ones withdrawn first, each Mentor told), and so do deleting the Product and the end of
 * the Job data's retention, each deletion audited (X3, Story 21.4). Nothing here blocks an
 * erasure (a running Job blocks it through {@code job}): the erasure first withdraws the person's open bids through
 * the lock's path, so each Customer is told, and then deletes (Story 20.4). It runs before the other participants,
 * while {@code identity} still has the Mentor's name for those notices.
 * <p>
 * It also answers for the GitHub link (FR-80, R14): an open bid keeps it, since the Customer's contributor
 * check knows the Mentor by their GitHub ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MatchingPersonalData implements PersonalDataParticipant, GitHubLinkParticipant {

	private final JdbcClient jdbc;

	private final ObjectProvider<JobPosts> posts;

	private final ObjectProvider<Applications> applications;

	private final ObjectProvider<BidProofs> proofs;

	private final ObjectProvider<CrewApplications> crewApplications;

	private final ObjectProvider<CrewApplicationProofs> crewProofs;

	private final ObjectProvider<AuditLog> auditLog;

	MatchingPersonalData(JdbcClient jdbc, ObjectProvider<JobPosts> posts, ObjectProvider<Applications> applications,
			ObjectProvider<BidProofs> proofs, ObjectProvider<CrewApplications> crewApplications,
			ObjectProvider<CrewApplicationProofs> crewProofs, ObjectProvider<AuditLog> auditLog) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.applications = applications;
		this.proofs = proofs;
		this.crewApplications = crewApplications;
		this.crewProofs = crewProofs;
		this.auditLog = auditLog;
	}

	@Override
	public Set<String> tables() {
		return Set.of("matching.application", "matching.bid_proof", "matching.nda_signature", "matching.invitation",
				"matching.crew_application", "matching.crew_application_proof");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> applications = this.jdbc.sql("""
				SELECT job_id, status, price_vnd, timeline_days, approach, submitted_at, ended_at, end_reason
				FROM matching.application WHERE mentor_account_id = :id ORDER BY submitted_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> application = new LinkedHashMap<>();
			application.put("jobId", rs.getObject("job_id", UUID.class).toString());
			application.put("status", rs.getString("status"));
			application.put("priceVnd", rs.getLong("price_vnd"));
			application.put("timelineDays", rs.getInt("timeline_days"));
			application.put("approach", rs.getString("approach"));
			application.put("submittedAt", rs.getTimestamp("submitted_at").toInstant().toString());
			application.put("endedAt", rs.getTimestamp("ended_at") == null ? null
					: rs.getTimestamp("ended_at").toInstant().toString());
			application.put("endReason", rs.getString("end_reason"));
			return application;
		}).list();
		List<Map<String, Object>> signatures = this.jdbc.sql("""
				SELECT job_id, nda_version, signed_at FROM matching.nda_signature WHERE account_id = :id ORDER BY signed_at
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> signature = new LinkedHashMap<>();
			signature.put("jobId", rs.getObject("job_id", UUID.class).toString());
			signature.put("ndaVersion", rs.getString("nda_version"));
			signature.put("signedAt", rs.getTimestamp("signed_at").toInstant().toString());
			return signature;
		}).list();
		List<Map<String, Object>> invitations = this.jdbc.sql("""
				SELECT job_id, status, invited_at, ended_at, customer_account_id = :id AS sent
				FROM matching.invitation WHERE customer_account_id = :id OR mentor_account_id = :id
				ORDER BY invited_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> invitation = new LinkedHashMap<>();
			invitation.put("jobId", rs.getObject("job_id", UUID.class).toString());
			invitation.put("direction", rs.getBoolean("sent") ? "SENT" : "RECEIVED");
			invitation.put("status", rs.getString("status"));
			invitation.put("invitedAt", rs.getTimestamp("invited_at").toInstant().toString());
			invitation.put("endedAt", rs.getTimestamp("ended_at") == null ? null
					: rs.getTimestamp("ended_at").toInstant().toString());
			return invitation;
		}).list();
		// The bidder's proof (Story 21.2): each item of their bids, and the uploads no bid took yet; never the bytes.
		List<Map<String, Object>> proof = this.jdbc.sql("""
				SELECT job_id, application_id, position, kind, caption, file_name, size_bytes, url, added_at
				FROM matching.bid_proof WHERE bidder_account_id = :id ORDER BY added_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("jobId", rs.getObject("job_id", UUID.class).toString());
			item.put("attached", rs.getObject("application_id") != null);
			item.put("position", rs.getObject("position", Integer.class));
			item.put("kind", rs.getString("kind"));
			item.put("caption", rs.getString("caption"));
			item.put("fileName", rs.getString("file_name"));
			item.put("sizeBytes", rs.getObject("size_bytes", Long.class));
			item.put("url", rs.getString("url"));
			item.put("addedAt", rs.getTimestamp("added_at").toInstant().toString());
			return item;
		}).list();
		// The applicant's Crew Applications (Story 21.4), and their proof with the uploads no application took yet.
		List<Map<String, Object>> crewApplications = this.jdbc.sql("""
				SELECT job_id, status, note, array_to_string(skill_names, ', ') AS skills, submitted_at, lapses_at, ended_at,
				    end_reason
				FROM matching.crew_application WHERE applicant_account_id = :id ORDER BY submitted_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> application = new LinkedHashMap<>();
			application.put("jobId", rs.getObject("job_id", UUID.class).toString());
			application.put("status", rs.getString("status"));
			application.put("note", rs.getString("note"));
			application.put("skills", rs.getString("skills"));
			application.put("submittedAt", rs.getTimestamp("submitted_at").toInstant().toString());
			application.put("lapsesAt", rs.getTimestamp("lapses_at").toInstant().toString());
			application.put("endedAt", rs.getTimestamp("ended_at") == null ? null
					: rs.getTimestamp("ended_at").toInstant().toString());
			application.put("endReason", rs.getString("end_reason"));
			return application;
		}).list();
		List<Map<String, Object>> crewProof = this.jdbc.sql("""
				SELECT job_id, crew_application_id, position, kind, caption, file_name, size_bytes, url, added_at
				FROM matching.crew_application_proof WHERE applicant_account_id = :id ORDER BY added_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("jobId", rs.getObject("job_id", UUID.class).toString());
			item.put("attached", rs.getObject("crew_application_id") != null);
			item.put("position", rs.getObject("position", Integer.class));
			item.put("kind", rs.getString("kind"));
			item.put("caption", rs.getString("caption"));
			item.put("fileName", rs.getString("file_name"));
			item.put("sizeBytes", rs.getObject("size_bytes", Long.class));
			item.put("url", rs.getString("url"));
			item.put("addedAt", rs.getTimestamp("added_at").toInstant().toString());
			return item;
		}).list();
		if (applications.isEmpty() && proof.isEmpty() && signatures.isEmpty() && invitations.isEmpty()
				&& crewApplications.isEmpty() && crewProof.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("matching", Map.of("applications", applications, "bidProof", proof,
				"ndaSignatures", signatures, "invitations", invitations, "crewApplications", crewApplications,
				"crewApplicationProof", crewProof)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.applications.getObject().withdrawOpenBids(accountId);
		this.crewApplications.getObject().withdrawForAccount(accountId);
		// The Crew Applications go with their proof, files first: the applicant's own, and those to an erased Customer's
		// or Mentor's Jobs (Story 21.4).
		this.crewProofs.getObject()
			.deleteWhere("applicant_account_id = :id OR crew_application_id IN (SELECT id FROM matching.crew_application"
					+ " WHERE customer_account_id = :id OR mentor_account_id = :id)", Map.of("id", accountId));
		this.jdbc.sql("DELETE FROM matching.crew_application"
				+ " WHERE applicant_account_id = :id OR customer_account_id = :id OR mentor_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("DELETE FROM matching.nda_signature WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM matching.invitation WHERE mentor_account_id = :id OR customer_account_id = :id")
			.param("id", accountId)
			.update();
		// The proof goes with its bids, files first: the account's own, and that of the bids on an erased Customer's Jobs.
		this.proofs.getObject()
			.deleteWhere("bidder_account_id = :id OR application_id IN"
					+ " (SELECT id FROM matching.application WHERE customer_account_id = :id)", Map.of("id", accountId));
		this.jdbc.sql("DELETE FROM matching.application WHERE mentor_account_id = :id OR customer_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("""
				DELETE FROM matching.nda_signature WHERE job_id IN
				    (SELECT job_id FROM matching.application WHERE product_id = :id)
				""").param("id", productId).update();
		this.proofs.getObject().deleteWhere("product_id = :id", Map.of("id", productId));
		this.jdbc.sql("DELETE FROM matching.application WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM matching.invitation WHERE product_id = :id").param("id", productId).update();
		this.crewProofs.getObject().deleteWhere("product_id = :id", Map.of("id", productId));
		this.jdbc.sql("DELETE FROM matching.crew_application WHERE product_id = :id").param("id", productId).update();
	}

	/**
	 * NFR-10: the proof of the Product's bids, files included, goes with its Job data, and the bids stay; the Crew
	 * Applications to its Jobs go with their proof, each deletion audited (X3, Story 21.4).
	 */
	@Override
	public void retireJobData(UUID productId) {
		this.proofs.getObject().deleteWhere("product_id = :id", Map.of("id", productId));
		this.crewProofs.getObject().deleteWhere("product_id = :id", Map.of("id", productId));
		List<Map.Entry<UUID, UUID>> gone = this.jdbc.sql("DELETE FROM matching.crew_application WHERE product_id = :id"
				+ " RETURNING id, job_id")
			.param("id", productId)
			.query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class)))
			.list();
		gone.forEach(application -> this.auditLog.getObject()
			.record(AuditEntry.of(AuditActor.system(), CrewApplicationPurge.PURGED,
					AuditObject.of("matching.crew_application", application.getKey()))
				.forJob(application.getValue())));
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

	/** Each Application of the Mentor still waiting for the Customer's choice: submitted, or on hold. */
	@Override
	public List<GitHubLinkBlocker> githubLinkBlockers(UUID accountId) {
		return this.jdbc.sql("""
				SELECT job_id FROM matching.application WHERE mentor_account_id = :id AND status IN ('SUBMITTED', 'ON_HOLD')
				ORDER BY submitted_at, id
				""")
			.param("id", accountId)
			.query(UUID.class)
			.list()
			.stream()
			.map(job -> new GitHubLinkBlocker("matching", "OPEN_BID", job,
					this.posts.getObject().post(job).map(JobPostState::title).orElse("")))
			.toList();
	}

}
