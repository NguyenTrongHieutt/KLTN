package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.GitHubLinkBlocker;
import vn.vibefix.identity.GitHubLinkParticipant;
import vn.vibefix.job.CrewRecruitment.ClosedBy;
import vn.vibefix.job.CrewRecruitmentClosed;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code job}'s part in exports, erasure, and Product deletion (AD-24): the Customer's Jobs, the External
 * Access identifiers a Mentor or a Crew member entered (FR-66), the Crew recruitments a Mentor set, with their note
 * to applicants (Story 21.4), and the reasons a member gave when leaving (Story 21.6). Erasure and Product deletion delete
 * the Jobs and their screenshot files, and erasure deletes the person's identifiers and clears those reasons; a running
 * Job blocks the erasure of
 * its Customer, its Mentor, and its unreleased Crew, and a Milestone a former member will be a split party of
 * blocks theirs (Story 20.4), as an open split blocks its parties'.
 * <p>
 * Milestone Splits are financial records under NFR-10's legal retention (AD-24, A-01): nothing here deletes them, an
 * erasure only anonymizes the person in them, and the person's export lists their parts. A Product deletion never waits
 * for a split: a Job with a split not {@code FINAL} loses its other rows at once and keeps a bare row
 * ({@code product_deleted_at}) that every read but the split ports treats as gone, removed once the Job's last split is
 * final.
 * <p>
 * It also answers for the GitHub link (FR-80, R14): a role in a Job that is not Closed or Cancelled keeps it,
 * since the Job's repositories and grants know the person by their GitHub ID, and so does a Milestone a former
 * member will be a split party of.
 */
@Component
class JobPersonalData implements PersonalDataParticipant, GitHubLinkParticipant {

	static final String MILESTONE_PENDING = "MILESTONE_PENDING";

	static final String SPLIT_PENDING = "SPLIT_PENDING";

	/** The Customer of a bare Job row after an erasure: the column stays required, and it names nobody (AD-24). */
	static final UUID NOBODY = new UUID(0, 0);

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	JobPersonalData(JdbcClient jdbc, StoredFiles files, ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public Set<String> tables() {
		return Set.of("job.job", "job.job_screenshot", "job.agreement_draft", "job.milestone", "job.acceptance_criterion",
				"job.external_access", "job.agreement_version", "job.crew_row", "job.crew_terms_version", "job.crew_terms_share",
				"job.crew_terms_confirmation", "job.agreement_comment", "job.agreement_change_request", "job.agreement_approval",
				"job.confirmed_agreement", "job.closure_proposal", "job.change_request", "job.cancellation_proposal",
				"job.external_access_identifier", "job.admin_read_grant", "job.milestone_split", "job.milestone_split_party",
				"job.milestone_split_version", "job.milestone_split_share");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> jobs = this.jdbc.sql("""
				SELECT title, state, problem, expected, app_users, done_check, array_to_string(job_types, ',') AS types,
				       array_to_string(skill_names, ', ') AS skills, budget, deadline, created_at, updated_at
				FROM job.job WHERE customer_account_id = :id AND product_deleted_at IS NULL ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> job = new LinkedHashMap<>();
			job.put("title", rs.getString("title"));
			job.put("state", rs.getString("state"));
			job.put("problem", rs.getString("problem"));
			job.put("expected", rs.getString("expected"));
			job.put("appUsers", rs.getString("app_users"));
			job.put("doneCheck", rs.getString("done_check"));
			job.put("jobTypes", rs.getString("types"));
			job.put("skills", rs.getString("skills"));
			job.put("budget", rs.getString("budget"));
			job.put("deadline", rs.getDate("deadline") == null ? null : rs.getDate("deadline").toString());
			job.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
			job.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
			return job;
		}).list();
		List<Map<String, Object>> identifiers = this.jdbc.sql("""
				SELECT i.job_id, a.system, i.identifier, i.updated_at FROM job.external_access_identifier i
				JOIN job.external_access a ON a.id = i.access_id
				WHERE i.account_id = :id ORDER BY i.updated_at, i.access_id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("system", rs.getString("system"));
			row.put("identifier", rs.getString("identifier"));
			row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
			return row;
		}).list();
		// The Mentor's Crew recruitment (Story 21.4): the note to applicants they wrote, with the Skills sought.
		List<Map<String, Object>> recruitments = this.jdbc.sql("""
				SELECT id, recruiting, open_slots, array_to_string(recruit_skill_names, ', ') AS skills, recruit_note
				FROM job.job WHERE mentor_account_id = :id AND product_deleted_at IS NULL
				  AND (recruit_note IS NOT NULL OR recruiting_since IS NOT NULL)
				ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> recruitment = new LinkedHashMap<>();
			recruitment.put("jobId", rs.getObject("id", UUID.class).toString());
			recruitment.put("recruiting", rs.getBoolean("recruiting"));
			recruitment.put("openSlots", rs.getInt("open_slots"));
			recruitment.put("skills", rs.getString("skills"));
			recruitment.put("note", rs.getString("recruit_note"));
			return recruitment;
		}).list();
		// The reason a member gave when leaving (Story 21.6): their own words.
		List<Map<String, Object>> leftNotes = this.jdbc.sql("""
				SELECT job_id, left_note, window_end FROM job.crew_row WHERE member_account_id = :id AND left_note IS NOT NULL
				ORDER BY window_end, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> note = new LinkedHashMap<>();
			note.put("jobId", rs.getObject("job_id", UUID.class).toString());
			note.put("note", rs.getString("left_note"));
			note.put("leftAt", rs.getTimestamp("window_end").toInstant().toString());
			return note;
		}).list();
		List<Map<String, Object>> parts = splitParts(accountId);
		if (jobs.isEmpty() && identifiers.isEmpty() && recruitments.isEmpty() && leftNotes.isEmpty() && parts.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> data = new LinkedHashMap<>();
		if (!jobs.isEmpty()) {
			data.put("jobs", jobs);
		}
		if (!identifiers.isEmpty()) {
			data.put("externalAccessIdentifiers", identifiers);
		}
		if (!recruitments.isEmpty()) {
			data.put("crewRecruitments", recruitments);
		}
		if (!leftNotes.isEmpty()) {
			data.put("crewLeftNotes", leftNotes);
		}
		if (!parts.isEmpty()) {
			data.put("milestoneSplitParts", parts);
		}
		return Optional.of(new PersonalDataSection("job", data));
	}

	/**
	 * The person's own part in each version of each split they are a party of, and only theirs (AD-20); a split with no
	 * version yet lists the membership alone.
	 */
	private List<Map<String, Object>> splitParts(UUID accountId) {
		return this.jdbc.sql("""
				SELECT s.job_id, s.milestone_number, s.status, s.final_version_no, v.version_no, v.kind, h.percent, v.created_at
				FROM job.milestone_split_party p JOIN job.milestone_split s ON s.id = p.split_id
				LEFT JOIN job.milestone_split_share h ON h.party_id = p.id AND h.split_id = p.split_id
				LEFT JOIN job.milestone_split_version v ON v.split_id = h.split_id AND v.version_no = h.version_no
				WHERE p.party_account_id = :id ORDER BY s.created_at, s.id, v.version_no
				""").param("id", accountId).query((rs, n) -> {
			Integer version = rs.getObject("version_no", Integer.class);
			Timestamp created = rs.getTimestamp("created_at");
			Map<String, Object> part = new LinkedHashMap<>();
			part.put("jobId", rs.getObject("job_id", UUID.class).toString());
			part.put("milestone", rs.getInt("milestone_number"));
			part.put("status", rs.getString("status"));
			part.put("versionNo", version);
			part.put("kind", rs.getString("kind"));
			part.put("percent", rs.getObject("percent", Integer.class));
			part.put("final", version != null && version.equals(rs.getObject("final_version_no", Integer.class)));
			part.put("createdAt", created == null ? null : created.toInstant().toString());
			return part;
		}).list();
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM job.external_access_identifier WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM job.admin_read_grant WHERE admin_account_id = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE job.crew_row SET left_note = NULL WHERE member_account_id = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE job.closure_proposal SET proposer_account_id = NULL WHERE proposer_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE job.closure_proposal SET declined_by = NULL WHERE declined_by = :id").param("id", accountId).update();
		for (String column : List.of("proposer_account_id", "confirmed_by")) {
			this.jdbc.sql("UPDATE job.cancellation_proposal SET %s = NULL WHERE %s = :id".formatted(column, column))
				.param("id", accountId)
				.update();
		}
		for (String column : List.of("proposer_account_id", "customer_account_id", "mentor_account_id")) {
			this.jdbc.sql("UPDATE job.change_request SET %s = NULL WHERE %s = :id".formatted(column, column))
				.param("id", accountId)
				.update();
		}
		// A split stays with its numbers, without the person (A-01).
		for (String table : List.of("job.milestone_split SET mentor_account_id = NULL WHERE mentor_account_id",
				"job.milestone_split_party SET party_account_id = NULL WHERE party_account_id",
				"job.milestone_split_version SET author_account_id = NULL WHERE author_account_id")) {
			this.jdbc.sql("UPDATE " + table + " = :id").param("id", accountId).update();
		}
		clearRecruitments(accountId);
		deleteJobs("customer_account_id", accountId);
		this.jdbc.sql("UPDATE job.job SET customer_account_id = :nobody WHERE customer_account_id = :id")
			.param("nobody", NOBODY)
			.param("id", accountId)
			.update();
	}

	/**
	 * An erased Mentor's Crew recruitment (Story 21.4): the note and the Skills sought, their words, leave the Customer's
	 * Jobs, and a recruitment still on turns off with {@code CrewRecruitmentClosed}, so its applications lapse.
	 */
	private void clearRecruitments(UUID mentorId) {
		List<UUID> recruiting = this.jdbc.sql("SELECT id FROM job.job WHERE mentor_account_id = :id AND recruiting")
			.param("id", mentorId)
			.query(UUID.class)
			.list();
		this.jdbc.sql("""
				UPDATE job.job SET recruiting = false, recruit_note = NULL, recruit_skill_ids = '{}', recruit_skill_names = '{}',
				    crew_version = crew_version + 1
				WHERE mentor_account_id = :id AND (recruiting OR recruit_note IS NOT NULL OR cardinality(recruit_skill_ids) > 0)
				""").param("id", mentorId).update();
		Instant now = this.clock.instant();
		recruiting.forEach(job -> this.events.publishEvent(new CrewRecruitmentClosed(this.ids.newId(), job, ClosedBy.MENTOR,
				now)));
	}

	@Override
	public void deleteProduct(UUID productId) {
		deleteJobs("product_id", productId);
	}

	/**
	 * NFR-10: the Job Post screenshots, the External Access identifiers, the Agreement comments, and the reasons members
	 * gave when leaving go; the Job, its Milestones, its Agreement versions, the Crew Terms, and the Milestone Splits stay, as {@code payment} and
	 * {@code reputation} refer to them.
	 */
	@Override
	public void retireJobData(UUID productId) {
		this.jdbc.sql("""
				SELECT s.file_id FROM job.job_screenshot s JOIN job.job j ON j.id = s.job_id WHERE j.product_id = :id
				""").param("id", productId).query(UUID.class).list().forEach(this.files::delete);
		for (String table : List.of("job.job_screenshot", "job.external_access_identifier", "job.agreement_comment")) {
			this.jdbc.sql("DELETE FROM %s WHERE job_id IN (SELECT id FROM job.job WHERE product_id = :id)".formatted(table))
				.param("id", productId)
				.update();
		}
		this.jdbc.sql("UPDATE job.crew_row SET left_note = NULL WHERE job_id IN (SELECT id FROM job.job WHERE product_id = :id)")
			.param("id", productId)
			.update();
	}

	/**
	 * Deletes the Jobs whose {@code column} names {@code id}. It locks the rows first, in ID order (AD-4), and reads the
	 * open splits in a statement of its own, so a finalization under way is waited for and seen. A Job with an open split
	 * keeps a bare row; its splits, like every other Job's, are never touched.
	 */
	private void deleteJobs(String column, UUID id) {
		List<UUID> jobs = this.jdbc.sql("SELECT id FROM job.job WHERE %s = :id ORDER BY id FOR UPDATE".formatted(column))
			.param("id", id)
			.query(UUID.class)
			.list();
		if (jobs.isEmpty()) {
			return;
		}
		Set<UUID> open = new HashSet<>(this.jdbc
			.sql("SELECT DISTINCT job_id FROM job.milestone_split WHERE job_id IN (:jobs) AND status <> 'FINAL'")
			.param("jobs", jobs)
			.query(UUID.class)
			.list());
		this.jdbc.sql("SELECT file_id FROM job.job_screenshot WHERE job_id IN (:jobs)")
			.param("jobs", jobs)
			.query(UUID.class)
			.list()
			.forEach(this.files::delete);
		for (String table : List.of("job.job_screenshot", "job.admin_read_grant", "job.cancellation_proposal",
				"job.change_request", "job.closure_proposal",
				"job.agreement_comment",
				"job.agreement_change_request",
				"job.confirmed_agreement",
				"job.agreement_approval", "job.acceptance_criterion", "job.milestone", "job.external_access_identifier",
				"job.external_access",
				"job.agreement_version", "job.agreement_draft", "job.crew_terms_confirmation", "job.crew_terms_share",
				"job.crew_terms_version", "job.crew_row")) {
			this.jdbc.sql("DELETE FROM %s WHERE job_id IN (:jobs)".formatted(table)).param("jobs", jobs).update();
		}
		if (!open.isEmpty()) {
			this.jdbc.sql("""
					UPDATE job.job SET product_deleted_at = coalesce(product_deleted_at, :now), problem = NULL, expected = NULL,
					    app_users = NULL, done_check = NULL, job_types = '{}', skill_ids = '{}', skill_names = '{}', budget = NULL,
					    deadline = NULL, recruiting = false, recruiting_since = NULL, open_slots = 0, recruit_note = NULL,
					    recruit_skill_ids = '{}', recruit_skill_names = '{}', hidden_at = NULL, hidden_reason = NULL
					WHERE id IN (:open)
					""")
				.param("now", Timestamp.from(this.clock.instant()))
				.param("open", List.copyOf(open))
				.update();
		}
		List<UUID> gone = jobs.stream().filter(job -> !open.contains(job)).toList();
		if (!gone.isEmpty()) {
			this.jdbc.sql("DELETE FROM job.job WHERE id IN (:gone)").param("gone", gone).update();
		}
	}

	/** The Job's last split is final: a bare row has done its work and goes. A replay finds nothing to remove. */
	@ApplicationModuleListener
	void onMilestoneSplitFinalized(MilestoneSplitFinalized event) {
		this.jdbc.sql("SELECT id FROM job.job WHERE id = :id AND product_deleted_at IS NOT NULL FOR UPDATE")
			.param("id", event.jobId())
			.query(UUID.class)
			.optional()
			.filter(job -> !this.jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM job.milestone_split WHERE job_id = :job AND status <> 'FINAL')
					""").param("job", job).query(Boolean.class).single())
			.ifPresent(job -> this.jdbc.sql("DELETE FROM job.job WHERE id = :id").param("id", job).update());
	}

	/**
	 * What keeps the account from being erased (AD-24, NFR-11), each with its Job's title: a Job from Selecting to
	 * Closing where it is the Customer, the Mentor, or an unreleased Crew member ({@code JOB_RUNNING}), each Job with a
	 * Milestone {@link #pendingMilestoneJobs pending} for a former member ({@code MILESTONE_PENDING}), and each Job with
	 * a split not {@code FINAL} where it is a party ({@code SPLIT_PENDING}), the latter two once per Job however many of
	 * its Milestones wait. A split's parties are reached through its own rows, so a Job whose Product was deleted counts;
	 * it keeps its title and names no Job, since no page would open it.
	 */
	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		List<ErasureBlocker> blockers = new ArrayList<>(this.jdbc.sql("""
				SELECT id, title FROM job.job WHERE (customer_account_id = :id OR mentor_account_id = :id
				    OR id IN (SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND status <> 'RELEASED'))
				  AND state IN ('SELECTING', 'AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING') ORDER BY id
				""")
			.param("id", accountId)
			.query((rs, n) -> new ErasureBlocker("job", "JOB_RUNNING", rs.getObject("id", UUID.class), rs.getString("title")))
			.list());
		pendingMilestoneJobs(accountId)
			.forEach(job -> blockers.add(new ErasureBlocker("job", MILESTONE_PENDING, job.jobId(), job.title())));
		splitPendingJobs(accountId)
			.forEach(job -> blockers.add(new ErasureBlocker("job", SPLIT_PENDING, job.live() ? job.jobId() : null, job.title())));
		return blockers;
	}

	/**
	 * Each Job, not Closed or Cancelled, where the account is the Customer, the Mentor, or an unreleased Crew member
	 * ({@code JOB_ROLE}), each Job with a Milestone {@link #pendingMilestoneJobs pending} for a former member
	 * ({@code MILESTONE_PENDING}), and each Job with a split not {@code FINAL} where it is a party ({@code SPLIT_PENDING}),
	 * whose Handover Commit authors a Split Resolution reads by GitHub ID, once per Job.
	 */
	@Override
	public List<GitHubLinkBlocker> githubLinkBlockers(UUID accountId) {
		List<GitHubLinkBlocker> blockers = new ArrayList<>(this.jdbc.sql("""
				SELECT id, title FROM job.job WHERE (customer_account_id = :id OR mentor_account_id = :id
				    OR id IN (SELECT job_id FROM job.crew_row WHERE member_account_id = :id AND status <> 'RELEASED'))
				  AND state NOT IN ('CLOSED', 'CANCELLED') ORDER BY created_at, id
				""")
			.param("id", accountId)
			.query((rs, n) -> new GitHubLinkBlocker("job", "JOB_ROLE", rs.getObject("id", UUID.class), rs.getString("title")))
			.list());
		pendingMilestoneJobs(accountId)
			.forEach(job -> blockers.add(new GitHubLinkBlocker("job", MILESTONE_PENDING, job.jobId(), job.title())));
		splitPendingJobs(accountId)
			.forEach(job -> blockers.add(new GitHubLinkBlocker("job", SPLIT_PENDING, job.jobId(), job.title())));
		return blockers;
	}

	/** A Job with a split not {@code FINAL} where the account is a party. */
	private record SplitPendingJob(UUID jobId, String title, boolean live) {
	}

	/** Once per Job however many of its splits wait, a Job whose Product was deleted included. */
	private List<SplitPendingJob> splitPendingJobs(UUID accountId) {
		return this.jdbc.sql("""
				SELECT DISTINCT s.job_id, j.title, j.product_deleted_at IS NULL AS live
				FROM job.milestone_split s JOIN job.milestone_split_party p ON p.split_id = s.id JOIN job.job j ON j.id = s.job_id
				WHERE p.party_account_id = :id AND s.status <> 'FINAL' ORDER BY s.job_id
				""")
			.param("id", accountId)
			.query((rs, n) -> new SplitPendingJob(rs.getObject("job_id", UUID.class), rs.getString("title"), rs.getBoolean("live")))
			.list();
	}

	/** A Job with a Milestone the account will be a split party of. */
	private record PendingJob(UUID jobId, String title) {
	}

	/**
	 * Review 2026-10-27 A-19: each Job with a Milestone funded no later than the end of the window of the account's
	 * released Crew row, bounds inclusive as the party rule's, and still Held: neither released (Accepted or Settled) nor
	 * cancelled. The former member joins its split, so it keeps them; a Job is listed once however many of its Milestones
	 * wait.
	 */
	private List<PendingJob> pendingMilestoneJobs(UUID accountId) {
		return this.jdbc.sql("""
				SELECT j.id, j.title FROM job.job j
				WHERE EXISTS (SELECT 1 FROM job.milestone m JOIN job.crew_row r ON r.job_id = m.job_id
				    WHERE m.job_id = j.id AND m.removed_at IS NULL AND m.funded_at IS NOT NULL AND m.state IN (:held)
				      AND r.member_account_id = :id AND r.status = 'RELEASED' AND r.window_end IS NOT NULL
				      AND m.funded_at <= r.window_end)
				ORDER BY j.id
				""")
			.param("id", accountId)
			.param("held", MilestoneState.held().stream().map(Enum::name).toList())
			.query((rs, n) -> new PendingJob(rs.getObject("id", UUID.class), rs.getString("title")))
			.list();
	}

}
