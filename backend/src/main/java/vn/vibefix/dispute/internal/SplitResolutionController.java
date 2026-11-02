package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.dispute.internal.SplitResolutions.Resolution;
import vn.vibefix.dispute.internal.SplitResolutions.Statement;
import vn.vibefix.dispute.internal.SplitResolutions.Status;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.CaseParty;
import vn.vibefix.job.MilestoneSplits.Resumed;
import vn.vibefix.job.MilestoneSplits.SplitCase;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-10's Split Resolutions (FR-84, AD-20, AD-40, A-18, UX-DR106): a party raises one, adds a statement, withdraws theirs, and
 * reads every case of the Milestone. They decide from the split's party rows, never the role in the Job, so a former member and
 * a bare row work. {@code payment}'s composed read cannot depend on {@code dispute}, so the screen reads the cases here. The
 * Customer and everyone else find nothing, and an unlocked account is required (AD-19).
 */
@RestController
@Access(Gate.SIGNED_IN)
class SplitResolutionController {

	/**
	 * "Gửi yêu cầu".
	 * @param splitVersionNo the version the screen showed; an older one answers {@code STALE}
	 */
	record SplitResolutionRequest(@NotNull Integer splitVersionNo, @NotBlank @Size(max = 2000) String reason) {
	}

	/** One party's statement on a case. */
	record SplitResolutionStatementRequest(@NotBlank @Size(max = 2000) String text) {
	}

	/**
	 * A statement as every party reads it.
	 * @param name what screens call the person; absent once an erasure anonymized them
	 * @param mentor it is the Mentor's
	 */
	record SplitResolutionStatementView(UUID partyId, @Nullable String name, boolean mentor, String text, Instant submittedAt) {
	}

	/**
	 * A case as every party of the split reads it (A-18).
	 * @param status {@code OPEN}, {@code WITHDRAWN} or {@code DECIDED}
	 * @param raiserPartyId the raiser's party, whose line reads "Đã nhờ xem xét"
	 * @param raiserName what screens call the raiser; absent once an erasure anonymized them
	 * @param reasoning the Administrator's written reasoning, once decided
	 * @param endedAt when it was withdrawn or decided
	 * @param viewerIsRaiser the reader raised it
	 * @param viewerMayState the reader may add their one statement now
	 */
	record SplitResolutionView(UUID id, int splitVersionNo, Status status, UUID raiserPartyId, @Nullable String raiserName,
			String reason, Instant raisedAt, @Nullable Instant endedAt, @Nullable String reasoning, boolean viewerIsRaiser, boolean viewerMayState,
			List<SplitResolutionStatementView> statements) {
	}

	/**
	 * Every case of the Milestone, the newest first.
	 * @param viewerMayRaise the reader may ask for a resolution now
	 */
	record SplitResolutionsView(boolean viewerMayRaise, List<SplitResolutionView> resolutions) {
	}

	/**
	 * What a withdrawal did.
	 * @param splitStatus {@code PROPOSED} with a new round, or {@code FINAL} when everyone had confirmed
	 * @param confirmationDueAt the new round's due time
	 */
	record SplitResolutionWithdrawnView(MilestoneSplits.Status splitStatus, @Nullable Instant confirmationDueAt) {
	}

	private final SplitResolutions resolutions;

	private final SplitResolutionPeople people;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	SplitResolutionController(SplitResolutions resolutions, SplitResolutionPeople people, ActiveAccount gate,
			CurrentCaller callers) {
		this.resolutions = resolutions;
		this.people = people;
		this.gate = gate;
		this.callers = callers;
	}

	@PostMapping("/api/v1/splits/{splitId}/resolutions")
	@ResponseStatus(HttpStatus.CREATED)
	SplitResolutionsView raise(@PathVariable UUID splitId, @Valid @RequestBody SplitResolutionRequest body) {
		UUID me = person();
		this.resolutions.raise(me, splitId, body.splitVersionNo(), body.reason());
		return read(me, splitId);
	}

	@GetMapping("/api/v1/splits/{splitId}/resolutions")
	SplitResolutionsView resolutions(@PathVariable UUID splitId) {
		return read(person(), splitId);
	}

	@PostMapping("/api/v1/split-resolutions/{resolutionId}/statements")
	@ResponseStatus(HttpStatus.CREATED)
	SplitResolutionsView addStatement(@PathVariable UUID resolutionId, @Valid @RequestBody SplitResolutionStatementRequest body) {
		UUID me = person();
		this.resolutions.addStatement(me, resolutionId, body.text());
		return read(me, splitOf(resolutionId));
	}

	/**
	 * @throws ApiException {@code FORBIDDEN} for another party and for the case's own Administrator, who decides it and cannot
	 * withdraw it; an Administrator with no relation to it finds nothing (SG-31)
	 */
	@PostMapping("/api/v1/split-resolutions/{resolutionId}/withdraw")
	SplitResolutionWithdrawnView withdraw(@PathVariable UUID resolutionId) {
		Caller caller = this.callers.get();
		if (caller.isAdministrator() && this.resolutions.find(resolutionId)
			.filter(resolution -> caller.requireAccountId().equals(resolution.assigneeId()))
			.isPresent()) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		Resumed resumed = this.resolutions.withdraw(person(), resolutionId);
		return new SplitResolutionWithdrawnView(resumed.status(), resumed.confirmationDueAt());
	}

	private UUID splitOf(UUID resolutionId) {
		return this.resolutions.find(resolutionId).map(Resolution::splitId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private SplitResolutionsView read(UUID me, UUID splitId) {
		SplitCase split = this.resolutions.caseFor(me, splitId);
		CaseParty mine = split.parties().stream().filter(party -> me.equals(party.accountId())).findFirst().orElseThrow();
		List<Resolution> cases = this.resolutions.ofSplit(splitId);
		Map<UUID, UUID> accountOf = split.parties()
			.stream()
			.filter(party -> party.accountId() != null)
			.collect(Collectors.toMap(CaseParty::partyId, party -> Objects.requireNonNull(party.accountId())));
		Map<UUID, String> names = this.people.names(accountOf.values(), split.mentorId());
		Set<UUID> mentors = split.parties().stream().filter(CaseParty::mentor).map(CaseParty::partyId).collect(Collectors.toSet());
		List<SplitResolutionView> views = new ArrayList<>();
		for (Resolution resolution : cases) {
			List<Statement> statements = this.resolutions.statements(resolution.id());
			boolean isRaiser = mine.partyId().equals(resolution.raiserPartyId());
			boolean written = statements.stream().anyMatch(statement -> statement.partyId().equals(mine.partyId()));
			UUID raiser = resolution.raiserId();
			views.add(new SplitResolutionView(resolution.id(), resolution.splitVersionNo(), resolution.status(),
					resolution.raiserPartyId(), raiser == null ? null : names.get(raiser), resolution.reason(), resolution.raisedAt(), resolution.endedAt(),
					resolution.reasoning(), isRaiser, resolution.open() && !isRaiser && !written,
					statements.stream()
						.map(statement -> new SplitResolutionStatementView(statement.partyId(),
								statement.accountId() == null ? null : names.get(statement.accountId()),
								mentors.contains(statement.partyId()), statement.text(), statement.submittedAt()))
						.toList()));
		}
		boolean mayRaise = !mine.mentor() && split.status() == MilestoneSplits.Status.PROPOSED && !split.pastDue()
				&& cases.stream().noneMatch(resolution -> mine.partyId().equals(resolution.raiserPartyId()) || resolution.open());
		return new SplitResolutionsView(mayRaise, views);
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
