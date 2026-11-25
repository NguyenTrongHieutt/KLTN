package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
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

	/** The reader opened a version: the row moves forward, never back. */
	@Transactional
	void opened(UUID jobId, UUID accountId, Mark mark) {
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
			if (behind.contains(member.studentId())) {
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

	/**
	 * The first confirmation turns the approved draft into version 1 with the same words, so whoever read that draft has
	 * read version 1 and is told nothing; a reader behind that draft was told when it was shared and stays behind.
	 */
	@Transactional
	void confirmedFromDraft(UUID jobId, int draftVersionNo, int versionNo) {
		this.jdbc.sql("""
				UPDATE job.agreement_read SET confirmed = TRUE, version_no = :no
				WHERE job_id = :job AND confirmed = FALSE AND version_no = :draft
				""").param("job", jobId).param("no", versionNo).param("draft", draftVersionNo).update();
	}

}
