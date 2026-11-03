package vn.vibefix.dispute.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobRules;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.CaseParty;
import vn.vibefix.job.MilestoneSplits.Resumed;
import vn.vibefix.job.MilestoneSplits.SplitCase;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Split Resolutions (FR-84, AD-29, AD-40): a party other than the Mentor asks an Administrator to decide a Milestone Split they
 * think unfair, so a Mentor cannot pay a second account of their own first. A raise holds the whole Milestone's money until the
 * raiser withdraws it or an Administrator decides; a lock on anyone ends nothing. Each write is one transaction that takes
 * {@code job.lockForSplit} and then the Milestone's Split Resolution rows (AD-4), and decides from the split's own party rows,
 * never the role in the Job, so it works for a former member, after J9 or J10 and on a bare row. It takes no busy marker, reads
 * no Job state, freezes nothing, and a freeze or a Dispute never pauses it (AD-20). The Customer is told nothing.
 */
@Component
class SplitResolutions {

	static final String REQUESTED = "dispute.split_resolution_requested";

	static final String WITHDRAWN = "dispute.split_resolution_withdrawn";

	static final String DECIDED = "dispute.split_resolution_decided";

	static final String UNDECIDED = "dispute.split_resolution_undecided";

	/** The Job's copied days until an undecided case is flagged (AD-7), and FR-61's default for a Job published before them. */
	static final String UNDECIDED_RULE = "SPLIT_UNDECIDED_FLAG_DAYS";

	static final int DEFAULT_UNDECIDED_DAYS = 7;

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	/** Where a case stands: open until its raiser withdraws it or an Administrator decides it. */
	enum Status {
		OPEN, WITHDRAWN, DECIDED
	}

	/**
	 * A Split Resolution's row.
	 * @param raiserPartyId the raiser's party row, which an erasure leaves: one raise per party stays one
	 * @param raiserId empty once an erasure anonymized them
	 * @param splitVersionNo the version the raiser was shown
	 * @param undecidedDueAt when it counts as undecided, from the Job's copied days at the raise
	 * @param decisionVersionNo the {@code DECISION} version, once decided
	 * @param endedAt when it was withdrawn or decided
	 */
	record Resolution(UUID id, UUID splitId, UUID jobId, UUID productId, UUID milestoneId, int milestoneNumber,
			UUID raiserPartyId, @Nullable UUID raiserId, int splitVersionNo, String reason, Status status, Instant raisedAt,
			Instant undecidedDueAt, @Nullable UUID assigneeId, @Nullable UUID grantId, @Nullable Instant endedAt,
			@Nullable Integer decisionVersionNo, @Nullable String reasoning) {

		boolean open() {
			return this.status == Status.OPEN;
		}

	}

	/**
	 * One party's statement on a case.
	 * @param accountId empty once an erasure anonymized them
	 */
	record Statement(UUID partyId, @Nullable UUID accountId, String text, Instant submittedAt) {
	}

	private final JdbcClient jdbc;

	private final MilestoneSplits splits;

	private final JobRules rules;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Administrators administrators;

	private final SplitResolutionPeople people;

	private final List<AdminReadGrants> grants;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	SplitResolutions(JdbcClient jdbc, MilestoneSplits splits, JobRules rules, Deadlines deadlines, Notifications notifications,
			Administrators administrators, SplitResolutionPeople people, List<AdminReadGrants> grants, AuditLog auditLog,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.splits = splits;
		this.rules = rules;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.administrators = administrators;
		this.people = people;
		this.grants = grants;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * "Nhờ quản trị viên xem xét": holds the split, the whole pool staying pooled, and tells the Mentor, the other parties and
	 * the Administrators.
	 * @param shownVersionNo the version the screen showed
	 * @throws ApiException {@code NOT_FOUND} for a non-party or before any draft; {@code FORBIDDEN PARTY_ONLY} for the Mentor;
	 * {@code GUARD_FAILED} with {@code SPLIT_RESOLUTION_USED}, {@code SPLIT_IN_RESOLUTION}, or {@code SPLIT_FINAL}, also past the
	 * due time however late the Deadline runs (A-09); {@code STALE} on an old version; {@code VALIDATION}
	 */
	@Transactional
	Resolution raise(UUID accountId, UUID splitId, int shownVersionNo, String reasonText) {
		SplitCase split = lockedCase(splitId);
		CaseParty party = party(split, accountId);
		if (split.status() == MilestoneSplits.Status.AWAITING_DRAFT) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (party.mentor()) {
			throw new ApiException(ErrorCode.FORBIDDEN, DisputeReason.PARTY_ONLY);
		}
		lockRows(split.milestoneId());
		if (raised(split.milestoneId(), party.partyId())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_RESOLUTION_USED);
		}
		if (split.status() == MilestoneSplits.Status.IN_RESOLUTION) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_IN_RESOLUTION);
		}
		if (split.status() == MilestoneSplits.Status.FINAL) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_FINAL);
		}
		if (split.pastDue()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_FINAL, Map.of("pastDue", true));
		}
		if (shownVersionNo != split.latestVersionNo()) {
			throw new ApiException(ErrorCode.STALE);
		}
		String reason = reasonText.strip();
		if (reason.isEmpty() || reason.length() > 2000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Instant now = this.clock.instant();
		int days = this.rules.value(split.jobId(), UNDECIDED_RULE, DEFAULT_UNDECIDED_DAYS);
		Instant due = now.plus(Duration.ofDays(days));
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO dispute.split_resolution (id, split_id, job_id, product_id, milestone_id, milestone_number, raiser_party_id,
				    raiser_account_id, split_version_no, reason, status, raised_at, undecided_due_at)
				VALUES (:id, :split, :job, :product, :milestone, :number, :party, :raiser, :version, :reason, 'OPEN', :now, :due)
				""")
			.param("id", id)
			.param("split", split.splitId())
			.param("job", split.jobId())
			.param("product", split.productId())
			.param("milestone", split.milestoneId())
			.param("number", split.milestoneNumber())
			.param("party", party.partyId())
			.param("raiser", accountId)
			.param("version", split.latestVersionNo())
			.param("reason", reason)
			.param("now", Timestamp.from(now))
			.param("due", Timestamp.from(due))
			.update();
		this.splits.holdSplit(split.milestoneId(), id);
		this.deadlines.schedule(new DeadlineRequest(SplitResolutionUndecidedDeadline.KEY, id, id.toString(), split.jobId(), due));
		Map<String, String> params = Map.of("name", this.people.nameOf(accountId, split.mentorId()), "n",
				String.valueOf(split.milestoneNumber()), "job", title(split));
		tellParties(REQUESTED, id, split, partiesExcept(split, accountId), params);
		tellAdministrators(REQUESTED, id, params);
		audit(AuditActor.user(accountId), "dispute.split_resolution_raised", id, split.jobId());
		return find(id).orElseThrow();
	}

	/**
	 * One statement of a party other than the raiser, whose reason is theirs, while the case is open. The inventory has no
	 * notice for it: JOB-10 polls, and the assigned Administrator's case reads it.
	 * @throws ApiException {@code NOT_FOUND} for a non-party; {@code GUARD_FAILED} with {@code SPLIT_RESOLUTION_ENDED} or
	 * {@code SPLIT_STATEMENT_USED}; {@code VALIDATION}
	 */
	@Transactional
	Statement addStatement(UUID accountId, UUID resolutionId, String statementText) {
		Resolution resolution = lockedResolution(resolutionId);
		SplitCase split = this.splits.caseOf(resolution.splitId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		CaseParty party = party(split, accountId);
		if (!resolution.open()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_RESOLUTION_ENDED);
		}
		boolean written = party.partyId().equals(resolution.raiserPartyId())
				|| statements(resolutionId).stream().anyMatch(statement -> statement.partyId().equals(party.partyId()));
		if (written) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_STATEMENT_USED);
		}
		String text = statementText.strip();
		if (text.isEmpty() || text.length() > 2000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO dispute.split_resolution_statement (resolution_id, party_id, account_id, text, submitted_at)
				VALUES (:resolution, :party, :account, :text, :now)
				""")
			.param("resolution", resolutionId)
			.param("party", party.partyId())
			.param("account", accountId)
			.param("text", text)
			.param("now", Timestamp.from(now))
			.update();
		audit(AuditActor.user(accountId), "dispute.split_statement_added", resolutionId, resolution.jobId());
		return new Statement(party.partyId(), accountId, text, now);
	}

	/**
	 * "Rút yêu cầu", the raiser's only, before a decision: {@code job.resumeSplit} starts a new full round for the latest version,
	 * or makes it final when its confirmations already cover every party but the Mentor (A-08). The raiser's one raise stays
	 * used; another party may raise. The case's grants end at once.
	 * @throws ApiException {@code NOT_FOUND} for a non-party; {@code FORBIDDEN} for another party;
	 * {@code GUARD_FAILED SPLIT_RESOLUTION_ENDED}
	 */
	@Transactional
	Resumed withdraw(UUID accountId, UUID resolutionId) {
		Resolution resolution = lockedResolution(resolutionId);
		SplitCase split = this.splits.caseOf(resolution.splitId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		CaseParty party = party(split, accountId);
		if (!party.partyId().equals(resolution.raiserPartyId())) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		if (!resolution.open()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_RESOLUTION_ENDED);
		}
		Resumed resumed = this.splits.resumeSplit(resolution.milestoneId());
		Instant now = this.clock.instant();
		this.jdbc.sql("UPDATE dispute.split_resolution SET status = 'WITHDRAWN', ended_at = :now WHERE id = :id")
			.param("now", Timestamp.from(now))
			.param("id", resolutionId)
			.update();
		if (resolution.grantId() != null) {
			this.grants.forEach(module -> module.endAdminRead(resolution.grantId(), now));
		}
		Map<String, String> params = Map.of("n", String.valueOf(resolution.milestoneNumber()), "next", nextStep(resumed));
		tellParties(WITHDRAWN, resolutionId, split, partiesExcept(split, null), params);
		tellAdministrators(WITHDRAWN, resolutionId, params);
		audit(AuditActor.user(accountId), "dispute.split_resolution_withdrawn", resolutionId, resolution.jobId());
		return resumed;
	}

	/** Either of {@code dispute.split_resolution_withdrawn}'s endings (EXPERIENCE.md, A-08). */
	private static String nextStep(Resumed resumed) {
		Instant due = resumed.confirmationDueAt();
		return due == null ? "Mọi người đã xác nhận nên phân chia đã chốt."
				: "Hạn xác nhận mới: " + DAY_MONTH.format(due.atZone(VIETNAM)) + ".";
	}

	/** The split a party reads: its rows answer, never the role in the Job, so a former member and a bare row work. */
	@Transactional(readOnly = true)
	SplitCase caseFor(UUID accountId, UUID splitId) {
		SplitCase split = this.splits.caseOf(splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		party(split, accountId);
		return split;
	}

	/** Every case of the split, the newest first. */
	@Transactional(readOnly = true)
	List<Resolution> ofSplit(UUID splitId) {
		return this.jdbc.sql("SELECT * FROM dispute.split_resolution WHERE split_id = :split ORDER BY raised_at DESC, id")
			.param("split", splitId)
			.query(SplitResolutions::resolution)
			.list();
	}

	@Transactional(readOnly = true)
	Optional<Resolution> find(UUID id) {
		return this.jdbc.sql("SELECT * FROM dispute.split_resolution WHERE id = :id")
			.param("id", id)
			.query(SplitResolutions::resolution)
			.optional();
	}

	/** The case's statements, the oldest first. */
	@Transactional(readOnly = true)
	List<Statement> statements(UUID resolutionId) {
		return this.jdbc.sql("""
				SELECT party_id, account_id, text, submitted_at FROM dispute.split_resolution_statement
				WHERE resolution_id = :id ORDER BY submitted_at, party_id
				""")
			.param("id", resolutionId)
			.query((rs, n) -> new Statement(rs.getObject("party_id", UUID.class), rs.getObject("account_id", UUID.class),
					rs.getString("text"), rs.getTimestamp("submitted_at").toInstant()))
			.list();
	}

	/**
	 * The case under {@code job.lockForSplit} and then its Milestone's Split Resolution rows, in AD-4's order.
	 * @throws ApiException {@code NOT_FOUND}
	 */
	Resolution lockedResolution(UUID resolutionId) {
		Resolution found = find(resolutionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		this.splits.lockForSplit(found.jobId(), found.milestoneId());
		lockRows(found.milestoneId());
		return find(resolutionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** Tells the Mentor and the parties, with JOB-10's link, a person anonymized by an erasure having nobody to reach. */
	void tellParties(String type, UUID resolutionId, SplitCase split, List<Recipient> recipients, Map<String, String> params) {
		if (recipients.isEmpty()) {
			return;
		}
		this.notifications.send(new NotificationRequest(type, resolutionId, recipients, params,
				"/cong-viec/" + split.jobId() + "/phan-chia/" + split.milestoneId(), split.jobId(), null, split.milestoneId()));
	}

	/** Tells every Administrator, with ADM-4's link. */
	void tellAdministrators(String type, UUID resolutionId, Map<String, String> params) {
		List<Recipient> recipients = this.administrators.activeIds()
			.stream()
			.map(admin -> new Recipient(admin, Audience.ADMIN))
			.toList();
		if (recipients.isEmpty()) {
			return;
		}
		this.notifications.send(new NotificationRequest(type, resolutionId, recipients, params,
				"/quan-tri/tranh-chap/phan-chia/" + resolutionId, null, null));
	}

	/** The Mentor and each other party who still has an account, leaving out {@code except} when it is named. */
	static List<Recipient> partiesExcept(SplitCase split, @Nullable UUID except) {
		List<Recipient> recipients = new ArrayList<>();
		for (CaseParty party : split.parties()) {
			UUID account = party.accountId();
			if (account != null && !account.equals(except)) {
				recipients.add(new Recipient(account, party.mentor() ? Audience.MENTOR : Audience.SPLIT_PARTY));
			}
		}
		return recipients;
	}

	/** What a notice calls the Job: the title while a row of it exists. */
	static String title(SplitCase split) {
		String title = split.jobTitle();
		return title == null ? "" : title;
	}

	private void audit(AuditActor actor, String action, UUID resolutionId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(actor, action, AuditObject.of("dispute.split_resolution", resolutionId)).forJob(jobId));
	}

	/** The split under the Job row lock, read again after it (AD-4); a split unknown or gone is not there. */
	private SplitCase lockedCase(UUID splitId) {
		SplitCase found = this.splits.caseOf(splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return this.splits.lockForSplit(found.jobId(), found.milestoneId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The account's party row; a stranger, the Customer always, finds nothing. */
	private static CaseParty party(SplitCase split, UUID accountId) {
		return split.parties()
			.stream()
			.filter(candidate -> accountId.equals(candidate.accountId()))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private boolean raised(UUID milestoneId, UUID partyId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM dispute.split_resolution WHERE milestone_id = :milestone AND raiser_party_id = :party)
				""").param("milestone", milestoneId).param("party", partyId).query(Boolean.class).single();
	}

	private void lockRows(UUID milestoneId) {
		this.jdbc.sql("SELECT id FROM dispute.split_resolution WHERE milestone_id = :milestone ORDER BY id FOR UPDATE")
			.param("milestone", milestoneId)
			.query(UUID.class)
			.list();
	}

	private static Resolution resolution(ResultSet rs, int n) throws SQLException {
		Timestamp ended = rs.getTimestamp("ended_at");
		return new Resolution(rs.getObject("id", UUID.class), rs.getObject("split_id", UUID.class),
				rs.getObject("job_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getInt("milestone_number"), rs.getObject("raiser_party_id", UUID.class),
				rs.getObject("raiser_account_id", UUID.class), rs.getInt("split_version_no"), rs.getString("reason"),
				Status.valueOf(rs.getString("status")), rs.getTimestamp("raised_at").toInstant(),
				rs.getTimestamp("undecided_due_at").toInstant(), rs.getObject("assigned_admin_id", UUID.class),
				rs.getObject("grant_id", UUID.class), ended == null ? null : ended.toInstant(),
				rs.getObject("decision_version_no", Integer.class), rs.getString("decision_reasoning"));
	}

}
