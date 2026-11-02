package vn.vibefix.payment.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.LineRead;
import vn.vibefix.job.MilestoneSplits.PartyRead;
import vn.vibefix.job.MilestoneSplits.PercentLine;
import vn.vibefix.job.MilestoneSplits.PreviewBasis;
import vn.vibefix.job.MilestoneSplits.SplitRead;
import vn.vibefix.job.MilestoneSplits.Status;
import vn.vibefix.job.MilestoneSplits.VersionKind;
import vn.vibefix.job.MilestoneSplits.VersionRead;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CrewSplit;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-10's reads (FR-83, AD-20, AD-33, X11): {@code payment} alone knows the pool, so it composes {@code job}'s split rows
 * with it, and every VND amount comes from {@link CrewSplit} here, so the screen computes no money. {@code job} decides
 * who reads what, from the party rows: a party reads every line of the splits they are a party of and nothing of another
 * Milestone's, the Job's Mentor with no split yet gets an empty list, and everyone else finds nothing, the Customer
 * always. No answer names an entry type. A split stays readable after its Job is gone.
 */
@RestController
@Access(Gate.SIGNED_IN)
class MilestoneSplitController {

	/** One party's percentage as the Mentor typed it, a number so that a fraction reaches {@code job}'s check. */
	record SplitPreviewShareRequest(@NotNull UUID partyId, @NotNull BigDecimal percent) {
	}

	/** The percentages to preview: one per party. */
	record SplitPreviewRequest(@NotNull List<@NotNull @Valid SplitPreviewShareRequest> shares) {
	}

	/** One party's VND in a preview. */
	record SplitPreviewLineView(UUID partyId, long amountVnd) {
	}

	/** The server's VND preview through {@link CrewSplit}: the remainder of a whole dong goes to the Mentor. */
	record SplitPreviewView(long pooledVnd, List<SplitPreviewLineView> lines) {
	}

	/**
	 * A party of the split.
	 * @param name what screens call the person; absent once an erasure anonymized them
	 * @param wholeInterval active from the Milestone's funding to its release: above 0% while the cap applies
	 * @param activeAtRelease still in the Crew at the release
	 * @param leftAt when they left, a lock included, while their Crew row exists
	 */
	record SplitPartyView(UUID partyId, @Nullable String name, boolean mentor, boolean wholeInterval, boolean activeAtRelease,
			@Nullable Instant leftAt) {
	}

	/**
	 * One party's line of a version.
	 * @param amountVnd their part of the pool by this version
	 * @param confirmed they confirmed this version; the Mentor never does
	 */
	record SplitLineView(UUID partyId, int percent, long amountVnd, boolean confirmed) {
	}

	/**
	 * A version, read-only.
	 * @param capPercent the cap it was checked against; absent when none applied
	 */
	record SplitVersionView(int versionNo, VersionKind kind, @Nullable Integer capPercent, Instant createdAt,
			List<SplitLineView> lines) {
	}

	/**
	 * One accepted Milestone's card on JOB-10.
	 * @param jobTitle the Job's title while a row of the Job exists
	 * @param pooledVnd the whole of the Milestone's release
	 * @param paid the final split reached the Balances
	 * @param cap the cap a save is held to now; absent when none applies (X5)
	 * @param editsLeft the edits the Mentor has left, of {@code editLimit}
	 * @param draftNear the draft deadline is within 48 h
	 * @param draftLate the draft deadline has passed and the default is on its way
	 * @param nextConfirmationDueAt when a round started now would be due: N-29's date
	 * @param confirmationNear the confirmation deadline is within 48 h
	 * @param pastDue past the confirmation due time, being finalized by the version it has: no actions
	 * @param k the parties other than the Mentor who confirmed the latest version
	 * @param n the parties other than the Mentor
	 * @param latest the latest version, once one exists
	 * @param earlier the earlier versions, newest first
	 */
	record SplitCardView(UUID splitId, UUID milestoneId, int milestoneNumber, @Nullable String jobTitle, Status status,
			@Nullable Finality finality, long pooledVnd, boolean paid, boolean capApplies, @Nullable Integer cap,
			int editsLeft, int editLimit, Instant draftDueAt, boolean draftNear, boolean draftLate, Instant nextConfirmationDueAt,
			@Nullable Instant confirmationDueAt, boolean confirmationNear, boolean pastDue, @Nullable Instant finalAt, int k,
			int n, boolean viewerIsMentor, boolean viewerConfirmed, List<SplitPartyView> parties, @Nullable SplitVersionView latest,
			List<SplitVersionView> earlier) {
	}

	/** The Milestone's pool, and whether its final split reversed it. */
	private record Pool(long amountVnd, boolean reversed) {
	}

	private static final UUID NOBODY = new UUID(0, 0);

	private final MilestoneSplits splits;

	private final PaymentHistory history;

	private final JdbcClient jdbc;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	MilestoneSplitController(MilestoneSplits splits, PaymentHistory history, JdbcClient jdbc, ActiveAccount gate,
			CurrentCaller callers) {
		this.splits = splits;
		this.history = history;
		this.jdbc = jdbc;
		this.gate = gate;
		this.callers = callers;
	}

	/** JOB-10: the Job's splits the caller is a party of, newest first. */
	@GetMapping("/api/v1/jobs/{jobId}/splits")
	List<SplitCardView> splits(@PathVariable UUID jobId) {
		UUID viewer = person();
		return this.splits.readableBy(jobId, viewer).stream().map(this::card).toList();
	}

	/**
	 * The VND each party would get, for the split's Mentor while drafting (AD-33).
	 * @throws ApiException {@code VALIDATION SPLIT_INVALID} for what a save would refuse
	 */
	@PostMapping("/api/v1/splits/{splitId}/preview")
	SplitPreviewView preview(@PathVariable UUID splitId, @Valid @RequestBody SplitPreviewRequest body) {
		UUID mentor = person();
		PreviewBasis basis = this.splits.previewBasis(splitId, mentor,
				body.shares().stream().map(share -> new PercentLine(share.partyId(), share.percent())).toList());
		long pooled = pool(basis.milestoneId()).map(Pool::amountVnd).orElse(0L);
		Map<UUID, Long> parts = CrewSplit.split(pooled, basis.percents(), basis.mentorPartyId());
		return new SplitPreviewView(pooled, basis.percents()
			.keySet()
			.stream()
			.map(party -> new SplitPreviewLineView(party, parts.getOrDefault(party, 0L)))
			.toList());
	}

	private SplitCardView card(SplitRead split) {
		Pool pool = pool(split.milestoneId()).orElse(new Pool(0, false));
		UUID mentorParty = split.parties().stream().filter(PartyRead::mentor).map(PartyRead::partyId).findFirst().orElseThrow();
		List<SplitVersionView> earlier = new ArrayList<>();
		split.earlier().forEach(version -> earlier.add(version(version, pool, mentorParty)));
		return new SplitCardView(split.splitId(), split.milestoneId(), split.milestoneNumber(), split.jobTitle(),
				split.status(), split.finality(), pool.amountVnd(), pool.reversed(), split.capApplies(), split.cap(),
				split.editsLeft(), split.editLimit(), split.draftDueAt(), split.draftNear(), split.draftLate(), split.nextConfirmationDueAt(),
				split.confirmationDueAt(), split.confirmationNear(), split.pastDue(), split.finalAt(), split.confirmed(),
				split.confirmers(), split.viewerIsMentor(), split.viewerConfirmed(), parties(split),
				split.latest() == null ? null : version(split.latest(), pool, mentorParty), earlier);
	}

	/** The parties by {@code partyId}, named as Balances name them; an anonymized party has no name. */
	private List<SplitPartyView> parties(SplitRead split) {
		UUID mentor = split.parties()
			.stream()
			.filter(PartyRead::mentor)
			.map(PartyRead::accountId)
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(NOBODY);
		List<UUID> accounts = split.parties().stream().map(PartyRead::accountId).filter(Objects::nonNull).toList();
		Map<UUID, String> names = new HashMap<>(this.history.names(accounts, mentor));
		return split.parties()
			.stream()
			.map(party -> new SplitPartyView(party.partyId(), party.accountId() == null ? null : names.get(party.accountId()),
					party.mentor(), party.wholeInterval(), party.activeAtRelease(), party.leftAt()))
			.toList();
	}

	private static SplitVersionView version(VersionRead version, Pool pool, UUID mentorParty) {
		Map<UUID, Integer> percents = new LinkedHashMap<>();
		version.lines().forEach(line -> percents.put(line.partyId(), line.percent()));
		Map<UUID, Long> parts = CrewSplit.split(pool.amountVnd(), percents, mentorParty);
		List<SplitLineView> lines = new ArrayList<>();
		for (LineRead line : version.lines()) {
			lines.add(new SplitLineView(line.partyId(), line.percent(), parts.getOrDefault(line.partyId(), 0L), line.confirmed()));
		}
		return new SplitVersionView(version.versionNo(), version.kind(), version.capPercent(), version.createdAt(), lines);
	}

	private Optional<Pool> pool(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT e.amount_vnd, EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id) AS reversed
				FROM payment.ledger_entry e WHERE e.milestone_id = :id AND e.entry_type = 'RELEASE_POOLED'
				""")
			.param("id", milestoneId)
			.query((rs, n) -> new Pool(rs.getLong("amount_vnd"), rs.getBoolean("reversed")))
			.optional();
	}

	/** A person's unlocked account (AD-19): an Administrator holds no role in any Job, so it finds nothing (SG-31). */
	private UUID person() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID person = caller.requireAccountId();
		this.gate.requireActiveAccount(person);
		return person;
	}

}
