package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * A result page's Rating summaries in one call (FR-14, SG-43, UX-DR47): CUS-8's Mentor search shows
 * each card's Rating or "Mới" without asking once per card. Only approved, unlocked Mentors answer; any
 * other ID is left out, so the call tells nothing a search would not.
 */
@RestController
@Access(Gate.SIGNED_IN)
class MentorSummaryController {

	/** One page's worth (AD-36). */
	static final int MAX_IDS = 20;

	/**
	 * @param newcomer no Job closed with Accepted work: "Mới" (FR-14)
	 */
	record MentorSummaryView(UUID accountId, @Nullable BigDecimal average, int count, boolean newcomer) {
	}

	private final Reputation reputation;

	private final MentorDirectory mentors;

	MentorSummaryController(Reputation reputation, MentorDirectory mentors) {
		this.reputation = reputation;
		this.mentors = mentors;
	}

	@GetMapping("/api/v1/reputation/mentor-summaries")
	List<MentorSummaryView> summaries(@RequestParam(name = "id", required = false) @Nullable List<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		if (ids.size() > MAX_IDS) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		List<UUID> shown = this.mentors.cards(ids.stream().distinct().toList())
			.stream()
			.filter(MentorCard::available)
			.map(MentorCard::accountId)
			.toList();
		Map<UUID, RatingSummary> summaries = this.reputation.visibleSummaries(shown);
		Set<UUID> newcomers = this.reputation.newcomers(shown);
		return shown.stream().map(id -> {
			RatingSummary summary = summaries.getOrDefault(id, RatingSummary.NONE);
			return new MentorSummaryView(id, summary.average(), summary.count(), newcomers.contains(id));
		}).toList();
	}

}
