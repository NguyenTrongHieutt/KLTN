package vn.vibefix.dispute.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.vibefix.dispute.internal.SplitEvidenceSnapshots.SplitEvidenceSnapshot;
import vn.vibefix.dispute.internal.SplitResolutionDecisions.Outcome;
import vn.vibefix.dispute.internal.SplitResolutionDecisions.Recorded;
import vn.vibefix.dispute.internal.SplitResolutions.Resolution;
import vn.vibefix.dispute.internal.SplitResolutions.Status;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.CaseParty;
import vn.vibefix.job.MilestoneSplits.PercentLine;
import vn.vibefix.job.MilestoneSplits.SplitCase;
import vn.vibefix.payment.SplitPools;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-4's "Phân chia tiền" tab (FR-84, AD-20, AD-40, UX-DR107): the Split Resolutions, the open ones first, with the "undecided
 * 7+ days" filter, read from each row's stored due time; one with "Nhận xử lý", and the decision form with its VND preview under
 * the step-up. Every Administrator sees that a case exists; its raiser, reason, statements, parties and decision are for its
 * assignee (A-18). The Split Evidence Package is {@link AdminSplitEvidenceController}'s.
 */
@RestController
@RequestMapping("/api/v1/admin/split-resolutions")
@Access(Gate.ADMINISTRATOR)
class AdminSplitResolutionController {

	/**
	 * A case in the queue.
	 * @param status {@code OPEN}, {@code WITHDRAWN} or {@code DECIDED}
	 * @param undecidedLong still open at or past its stored due time
	 */
	record AdminSplitResolutionRow(UUID id, UUID jobId, @Nullable String jobTitle, int milestoneNumber, Status status,
			Instant raisedAt, boolean undecidedLong, boolean assigned, boolean assignedToMe) {
	}

	/** A party as the assignee's decision form shows them. */
	record AdminSplitPartyView(UUID partyId, @Nullable String name, boolean mentor, boolean wholeInterval, boolean activeAtRelease) {
	}

	/** A statement as the assignee reads it. */
	record AdminSplitStatementView(UUID partyId, @Nullable String name, String text, Instant submittedAt) {
	}

	/** One party's line of the decision, with its VND from the record. */
	record AdminSplitDecisionLineView(UUID partyId, @Nullable String name, int percent, long amountVnd) {
	}

	/**
	 * The decision's record (AD-24).
	 * @param evidenceSnapshot the IDs and versions of the evidence read
	 */
	record AdminSplitDecisionView(String reasoning, Instant decidedAt, int versionNo, long pooledVnd,
			List<AdminSplitDecisionLineView> lines, SplitEvidenceSnapshot evidenceSnapshot) {
	}

	/**
	 * One case.
	 * @param pooledVnd the Milestone's whole pool
	 * @param daysUndecided whole days since the raise, until it ended
	 * @param undecidedLong still open at or past its stored due time
	 * @param evidenceReadable the viewer reads the Split Evidence Package now
	 * @param raiserName the assignee's only; absent too once an erasure anonymized the raiser
	 * @param reason the assignee's only
	 * @param statements the assignee's only
	 * @param parties the assignee's only
	 * @param cap the cap stored on the latest version; absent when none applies (X5), and for anyone but the assignee
	 * @param capApplies a party other than the Mentor was still in the Crew at the release
	 */
	record AdminSplitResolutionDetail(UUID id, UUID jobId, @Nullable String jobTitle, int milestoneNumber, long pooledVnd,
			Status status, Instant raisedAt, long daysUndecided, boolean undecidedLong, @Nullable Instant endedAt, boolean assigned,
			boolean assignedToMe, boolean evidenceReadable, @Nullable String raiserName, @Nullable String reason,
			List<AdminSplitStatementView> statements, List<AdminSplitPartyView> parties, boolean capApplies, @Nullable Integer cap,
			@Nullable AdminSplitDecisionView decision) {
	}

	/** "Nhận xử lý" went through. */
	record AdminSplitResolutionTakenView(UUID id, boolean assignedToMe) {
	}

	/** One party's percentage, a number so that a fraction reaches {@code job}'s check. */
	record AdminSplitShareRequest(@NotNull UUID partyId, @NotNull BigDecimal percent) {
	}

	/** The percentages to preview: one per party. */
	record AdminSplitPreviewRequest(@NotNull @Size(max = 100) List<@NotNull @Valid AdminSplitShareRequest> shares) {
	}

	/** The decision, after the step-up. */
	record AdminSplitDecisionRequest(@NotNull @Size(max = 100) List<@NotNull @Valid AdminSplitShareRequest> shares,
			@NotBlank @Size(max = 4000) String reasoning, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	/** One party's VND in a preview. */
	record AdminSplitPreviewLineView(UUID partyId, long amountVnd) {
	}

	/** The server's VND preview through {@code shared.CrewSplit}. */
	record AdminSplitPreviewView(long pooledVnd, List<AdminSplitPreviewLineView> lines) {
	}

	private final SplitResolutions resolutions;

	private final SplitResolutionDecisions decisions;

	private final MilestoneSplits splits;

	private final SplitPools pools;

	private final SplitResolutionPeople people;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	private final Clock clock;

	AdminSplitResolutionController(SplitResolutions resolutions, SplitResolutionDecisions decisions, MilestoneSplits splits,
			SplitPools pools, SplitResolutionPeople people, JdbcClient jdbc, CurrentCaller callers, Clock clock) {
		this.resolutions = resolutions;
		this.decisions = decisions;
		this.splits = splits;
		this.pools = pools;
		this.people = people;
		this.jdbc = jdbc;
		this.callers = callers;
		this.clock = clock;
	}

	/**
	 * The open cases, the oldest first, then the ended ones, the most recent first.
	 * @param undecided only the open cases at or past their stored due time
	 */
	@GetMapping
	List<AdminSplitResolutionRow> list(@RequestParam(defaultValue = "false") boolean undecided) {
		UUID me = this.callers.get().requireAccountId();
		Instant now = this.clock.instant();
		String order = "ORDER BY status = 'OPEN' DESC, CASE WHEN status = 'OPEN' THEN raised_at END ASC, ended_at DESC, id LIMIT 200";
		List<UUID> ids = undecided
				? this.jdbc.sql("SELECT id FROM dispute.split_resolution WHERE status = 'OPEN' AND undecided_due_at <= :now " + order)
					.param("now", Timestamp.from(now))
					.query(UUID.class)
					.list()
				: this.jdbc.sql("SELECT id FROM dispute.split_resolution " + order).query(UUID.class).list();
		return ids.stream().map(id -> {
			Resolution resolution = this.resolutions.find(id).orElseThrow();
			String title = this.splits.caseOf(resolution.splitId()).map(SplitCase::jobTitle).orElse(null);
			return new AdminSplitResolutionRow(id, resolution.jobId(), title, resolution.milestoneNumber(), resolution.status(),
					resolution.raisedAt(), undecidedLong(resolution, now), resolution.assigneeId() != null,
					me.equals(resolution.assigneeId()));
		}).toList();
	}

	@GetMapping("/{resolutionId}")
	AdminSplitResolutionDetail detail(@PathVariable UUID resolutionId) {
		return detail(resolutionId, this.callers.get().requireAccountId());
	}

	/**
	 * "Nhận xử lý": the viewer takes it, over another Administrator if need be, with its read grant. The answer holds none of
	 * the case's words: the assignee reads them from the case itself, as every read of them goes.
	 */
	@PostMapping("/{resolutionId}/take")
	AdminSplitResolutionTakenView take(@PathVariable UUID resolutionId) {
		this.decisions.take(resolutionId, this.callers.get().requireAccountId());
		return new AdminSplitResolutionTakenView(resolutionId, true);
	}

	/** The VND lines of the percentages typed: {@code payment} computes them, and the screen computes no money. */
	@PostMapping("/{resolutionId}/preview")
	AdminSplitPreviewView preview(@PathVariable UUID resolutionId, @Valid @RequestBody AdminSplitPreviewRequest body) {
		SplitPools.Parts parts = this.decisions.preview(resolutionId, this.callers.get().requireAccountId(), lines(body.shares()));
		return new AdminSplitPreviewView(parts.pooledVnd(), parts.amounts()
			.entrySet()
			.stream()
			.map(line -> new AdminSplitPreviewLineView(line.getKey(), line.getValue()))
			.toList());
	}

	/** The assignee's decision after the step-up; {@code payment} moves the pool once it commits. */
	@PostMapping("/{resolutionId}/decision")
	AdminSplitResolutionDetail decide(@PathVariable UUID resolutionId, @Valid @RequestBody AdminSplitDecisionRequest body,
			HttpServletRequest request) {
		UUID me = this.callers.get().requireAccountId();
		Outcome outcome = this.decisions.decide(resolutionId, me, lines(body.shares()), body.reasoning(), body.stepUpCode());
		if (outcome instanceof Outcome.Refused refused) {
			if (refused.endSession()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			throw refused.refusal();
		}
		return detail(resolutionId, me);
	}

	private static List<PercentLine> lines(List<AdminSplitShareRequest> shares) {
		return shares.stream().map(share -> new PercentLine(share.partyId(), share.percent())).toList();
	}

	private AdminSplitResolutionDetail detail(UUID resolutionId, UUID me) {
		Resolution resolution = this.resolutions.find(resolutionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		SplitCase split = this.splits.caseOf(resolution.splitId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Instant now = this.clock.instant();
		boolean mine = me.equals(resolution.assigneeId());
		Map<UUID, String> names = mine
				? this.people.names(SplitResolutionDecisions.partyAccounts(split), split.mentorId()) : Map.of();
		Instant until = resolution.endedAt() == null ? now : resolution.endedAt();
		UUID raiser = resolution.raiserId();
		return new AdminSplitResolutionDetail(resolution.id(), resolution.jobId(), split.jobTitle(), resolution.milestoneNumber(),
				this.pools.pooledVnd(resolution.milestoneId()), resolution.status(), resolution.raisedAt(),
				Duration.between(resolution.raisedAt(), until).toDays(), undecidedLong(resolution, now), resolution.endedAt(),
				resolution.assigneeId() != null, mine, this.decisions.evidenceReadable(resolution, me),
				mine && raiser != null ? names.get(raiser) : null, mine ? resolution.reason() : null,
				mine ? this.resolutions.statements(resolutionId)
					.stream()
					.map(statement -> new AdminSplitStatementView(statement.partyId(),
							statement.accountId() == null ? null : names.get(statement.accountId()), statement.text(),
							statement.submittedAt()))
					.toList() : List.of(),
				mine ? split.parties()
					.stream()
					.map(party -> new AdminSplitPartyView(party.partyId(), nameOf(party, names), party.mentor(), party.wholeInterval(),
							party.activeAtRelease()))
					.toList() : List.of(),
				split.capApplies(), mine ? split.latestCap() : null, mine ? decision(resolutionId, split, names) : null);
	}

	private @Nullable AdminSplitDecisionView decision(UUID resolutionId, SplitCase split, Map<UUID, String> names) {
		Recorded recorded = this.decisions.recorded(resolutionId).orElse(null);
		if (recorded == null) {
			return null;
		}
		return new AdminSplitDecisionView(recorded.reasoning(), recorded.decidedAt(), recorded.versionNo(),
				recorded.amounts().pooledVnd(), recorded.amounts()
					.lines()
					.stream()
					.map(line -> new AdminSplitDecisionLineView(line.partyId(),
							split.parties()
								.stream()
								.filter(party -> party.partyId().equals(line.partyId()))
								.findFirst()
								.map(party -> nameOf(party, names))
								.orElse(null),
							line.percent(), line.amountVnd()))
					.toList(),
				recorded.snapshot());
	}

	private static @Nullable String nameOf(CaseParty party, Map<UUID, String> names) {
		UUID account = party.accountId();
		return account == null ? null : names.get(account);
	}

	/** Read from the row's stored due time, the Job's copied days at the raise, never a literal 7 (AD-7). */
	private static boolean undecidedLong(Resolution resolution, Instant now) {
		return resolution.open() && !now.isBefore(resolution.undecidedDueAt());
	}

}
