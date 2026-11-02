package vn.vibefix.job.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.job.MilestoneSplits.PercentLine;
import vn.vibefix.job.MilestoneSplits.Status;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-10's commands (FR-83, AD-20, AD-23): the Mentor saves a version, a party confirms one. Both decide from the split's
 * party rows, never the role in the Job, so a former member and a bare row work, and each names the version its screen
 * showed. The Customer and everyone else find nothing; an unlocked account is required (AD-19).
 */
@RestController
@Access(Gate.SIGNED_IN)
class SplitController {

	/**
	 * One party's percentage.
	 * @param percent a number, so a fraction reaches the check instead of being cut to a whole one
	 */
	record SplitShareRequest(@NotNull UUID partyId, @NotNull BigDecimal percent) {
	}

	/**
	 * "Gửi để xác nhận".
	 * @param shownVersionNo the latest version the screen showed, 0 for none; an older one answers {@code STALE}
	 * @param shares one percentage per party
	 */
	record SplitVersionRequest(@NotNull Integer shownVersionNo,
			@NotNull @Size(max = 100) List<@NotNull @Valid SplitShareRequest> shares) {
	}

	/** "Xác nhận": the version the screen showed; an older one answers {@code STALE}. */
	record SplitConfirmationRequest(@NotNull Integer splitVersionNo) {
	}

	/** The new version, out for confirmation. */
	record SavedSplitView(int versionNo, Status status, Instant confirmationDueAt) {
	}

	/** The split after a confirmation, {@code FINAL} when it was the last. */
	record ConfirmedSplitView(int versionNo, Status status) {
	}

	private final SplitCommands commands;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	SplitController(SplitCommands commands, ActiveAccount gate, CurrentCaller callers) {
		this.commands = commands;
		this.gate = gate;
		this.callers = callers;
	}

	@PostMapping("/api/v1/splits/{splitId}/versions")
	SavedSplitView save(@PathVariable UUID splitId, @Valid @RequestBody SplitVersionRequest body) {
		List<PercentLine> lines = body.shares().stream().map(share -> new PercentLine(share.partyId(), share.percent())).toList();
		SplitCommands.Saved saved = this.commands.save(person(), splitId, body.shownVersionNo(), lines);
		return new SavedSplitView(saved.versionNo(), saved.status(), saved.confirmationDueAt());
	}

	@PostMapping("/api/v1/splits/{splitId}/confirmations")
	ConfirmedSplitView confirm(@PathVariable UUID splitId, @Valid @RequestBody SplitConfirmationRequest body) {
		SplitCommands.Confirmed confirmed = this.commands.confirm(person(), splitId, body.splitVersionNo());
		return new ConfirmedSplitView(confirmed.versionNo(), confirmed.status());
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
