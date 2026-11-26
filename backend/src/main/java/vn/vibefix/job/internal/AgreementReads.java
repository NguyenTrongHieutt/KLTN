package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;

/**
 * The newest Agreement version each Crew reader opened (FR-75, FR-81, AD-41, Story 25.1b), in {@code job.agreement_read}:
 * a picked applicant or a member who read a version is told once when a newer one becomes readable to them
 * ({@code job.agreement_draft_changed}), and JOB-6 says so until they open it. Someone who never opened a version is
 * told nothing.
 */
@Component
class AgreementReads {

	static final String DRAFT_CHANGED = "job.agreement_draft_changed";

	/**
	 * A version a Crew reader may open. A confirmed version is newer than any shared draft, since no draft is shared
	 * after the first confirmation; within a kind the number decides.
	 */
	record Mark(boolean confirmed, int versionNo) {

		boolean newerThan(Mark other) {
			return this.confirmed == other.confirmed ? this.versionNo > other.versionNo : this.confirmed;
		}

	}

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Notifications notifications;

	private final Clock clock;

	AgreementReads(JdbcClient jdbc, CrewService crews, Notifications notifications, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** The newest version the account opened in the Job, if it opened any. */
	@Transactional(readOnly = true)
	Optional<Mark> of(UUID jobId, UUID accountId) {
		return this.jdbc.sql("SELECT confirmed, version_no FROM job.agreement_read WHERE job_id = :job AND account_id = :account")
			.param("job", jobId)
			.param("account", accountId)
			.query((rs, n) -> new Mark(rs.getBoolean("confirmed"), rs.getInt("version_no")))
			.optional();
	}

	/** A version a Crew reader may open, with when it was shared or confirmed. */
	record Readable(Mark mark, Instant at) {
	}

	/**
	 * The versions a picked applicant or an active member may open, oldest first (FR-75, AD-41): every confirmed version
	 * once there is one; until then the latest shared draft alone, which the first confirmation turns into version 1 (no
	 * draft is shared after it). Empty before the first share.
	 * <p>
	 * In a Self-mentored Job nobody shares a draft (AD-47, R54): until the confirmation the reader opens the Customer's
	 * live draft, named by its revision, once it was saved for the first time.
	 */
	@Transactional(readOnly = true)
	List<Readable> readable(UUID jobId) {
		List<Readable> confirmed = this.jdbc
			.sql("SELECT version_no, confirmed_at FROM job.confirmed_agreement WHERE job_id = :job ORDER BY version_no")
			.param("job", jobId)
			.query((rs, n) -> new Readable(new Mark(true, rs.getInt("version_no")), rs.getTimestamp("confirmed_at").toInstant()))
			.list();
		if (!confirmed.isEmpty()) {
			return confirmed;
		}
		if (selfMentored(jobId)) {
			return this.jdbc.sql("SELECT revision, saved_at FROM job.agreement_draft WHERE job_id = :job AND revision > 0")
				.param("job", jobId)
				.query((rs, n) -> new Readable(new Mark(false, rs.getInt("revision")), rs.getTimestamp("saved_at").toInstant()))
				.list();
		}
		return this.jdbc
			.sql("SELECT version_no, created_at FROM job.agreement_version WHERE job_id = :job ORDER BY version_no DESC LIMIT 1")
			.param("job", jobId)
			.query((rs, n) -> new Readable(new Mark(false, rs.getInt("version_no")), rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	/**
	 * The reader has a version in front of them: their row moves forward, never back. It holds the Job row shared, which
	 * a share, a confirmation and a Change Request hold exclusively, so the Crew row and the versions checked here are
	 * the ones the write goes with: a read never lands beside a version it did not see.
	 * @return {@code false}, writing nothing, when the account is no picked applicant or active member of the Job now,
	 * or may not open that version now
	 */
	@Transactional
	boolean opened(UUID jobId, UUID accountId, Mark mark) {
		boolean live = this.jdbc.sql("SELECT 1 FROM job.job WHERE id = :id AND " + Jobs.LIVE + " FOR SHARE")
			.param("id", jobId)
			.query(Integer.class)
			.optional()
			.isPresent();
		Relationship kind = live ? this.crews.crewRelationship(jobId, accountId).kind() : Relationship.NONE;
		if ((kind != Relationship.ASSIGNED && kind != Relationship.ACTIVE)
				|| readable(jobId).stream().noneMatch(version -> version.mark().equals(mark))) {
			return false;
		}
		this.jdbc.sql("""
				INSERT INTO job.agreement_read (job_id, account_id, confirmed, version_no, read_at)
				VALUES (:job, :account, :confirmed, :no, :now)
				ON CONFLICT (job_id, account_id) DO UPDATE
				SET confirmed = EXCLUDED.confirmed, version_no = EXCLUDED.version_no, read_at = EXCLUDED.read_at
				WHERE (job.agreement_read.confirmed, job.agreement_read.version_no) < (EXCLUDED.confirmed, EXCLUDED.version_no)
				""")
			.param("job", jobId)
			.param("account", accountId)
			.param("confirmed", mark.confirmed())
			.param("no", mark.versionNo())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return true;
	}

	/**
	 * A newer version became readable to the Crew: each picked applicant and active member whose row is behind it is told,
	 * once per version. Their rows stay where they are until they open it.
	 */
	@Transactional
	void newer(UUID jobId, String jobTitle, Mark newest) {
		Set<UUID> behind = new HashSet<>(this.jdbc.sql("""
				SELECT account_id FROM job.agreement_read
				WHERE job_id = :job AND (confirmed, version_no) < (CAST(:confirmed AS BOOLEAN), CAST(:no AS INTEGER))
				""")
			.param("job", jobId)
			.param("confirmed", newest.confirmed())
			.param("no", newest.versionNo())
			.query(UUID.class)
			.list());
		if (behind.isEmpty()) {
			return;
		}
		List<Recipient> recipients = new ArrayList<>();
		for (CrewMember member : this.crews.crewOf(jobId, Selection.UNRELEASED)) {
			// A lock commits before its listener releases the row (AD-19): a locked account is told nothing.
			if (behind.contains(member.studentId()) && !this.crews.locked(member.studentId())) {
				recipients.add(new Recipient(member.studentId(),
						"ACTIVE".equals(member.status()) ? Audience.CREW_ACTIVE : Audience.CREW_ASSIGNED));
			}
		}
		if (recipients.isEmpty()) {
			return;
		}
		UUID cause = UUID.nameUUIDFromBytes((jobId + ":agreement-readable:" + newest.confirmed() + ":" + newest.versionNo())
			.getBytes(StandardCharsets.UTF_8));
		this.notifications.send(new NotificationRequest(DRAFT_CHANGED, cause, recipients, Map.of("job", jobTitle),
				Agreements.agreementLink(jobId), jobId, null));
	}

	/** The Job's mode (FR-85): its Customer is also its Mentor, and the Crew reads the live draft. */
	boolean selfMentored(UUID jobId) {
		return this.jdbc.sql("SELECT self_mentored FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	/**
	 * The Customer of a Self-mentored Job saved the draft the Crew reads (AD-47, R67): each picked applicant and active
	 * member who opened an earlier revision is told, once for the revision they opened, however many saves follow. Their
	 * rows stay where they are until they open the draft again.
	 */
	@Transactional
	void draftChanged(UUID jobId, String jobTitle, int revision) {
		Map<UUID, Integer> behind = new HashMap<>();
		this.jdbc.sql("""
				SELECT account_id, version_no FROM job.agreement_read
				WHERE job_id = :job AND confirmed = FALSE AND version_no < :revision
				""")
			.param("job", jobId)
			.param("revision", revision)
			.query((rs, n) -> behind.put(rs.getObject("account_id", UUID.class), rs.getInt("version_no")))
			.list();
		if (behind.isEmpty()) {
			return;
		}
		for (CrewMember member : this.crews.crewOf(jobId, Selection.UNRELEASED)) {
			Integer opened = behind.get(member.studentId());
			// A lock commits before its listener releases the row (AD-19): a locked account is told nothing.
			if (opened == null || this.crews.locked(member.studentId())) {
				continue;
			}
			UUID cause = UUID.nameUUIDFromBytes((jobId + ":agreement-draft-changed-since:" + opened)
				.getBytes(StandardCharsets.UTF_8));
			this.notifications.send(new NotificationRequest(DRAFT_CHANGED, cause,
					List.of(new Recipient(member.studentId(),
							"ACTIVE".equals(member.status()) ? Audience.CREW_ACTIVE : Audience.CREW_ASSIGNED)),
					Map.of("job", jobTitle), Agreements.agreementLink(jobId), jobId, null));
		}
	}

	/**
	 * The first confirmation turns the approved draft into version 1 with the same words, so whoever read that draft has
	 * read version 1 and is told nothing; a reader behind that draft was told when it was shared and stays behind. The
	 * row keeps its time: it is when the reader opened those words. In a Self-mentored Job {@code draftVersionNo} is the
	 * revision of the live draft the Customer confirmed.
	 */
	@Transactional
	void confirmedFromDraft(UUID jobId, int draftVersionNo, int versionNo) {
		this.jdbc.sql("""
				UPDATE job.agreement_read SET confirmed = TRUE, version_no = :no
				WHERE job_id = :job AND confirmed = FALSE AND version_no = :draft
				""").param("job", jobId).param("no", versionNo).param("draft", draftVersionNo).update();
	}

}
