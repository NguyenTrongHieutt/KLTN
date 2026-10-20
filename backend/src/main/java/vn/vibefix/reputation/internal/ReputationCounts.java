package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import vn.vibefix.reputation.PrivateFeedback;
import vn.vibefix.shared.PlatformCounts;

/**
 * ADM-9 (FR-62, FR-78): the private-feedback figure, only when it aggregates at least 5 feedbacks across
 * at least 3 Mentors; the average is in tenths of a star.
 */
@Component
class ReputationCounts implements PlatformCounts {

	private final PrivateFeedback feedback;

	ReputationCounts(PrivateFeedback feedback) {
		this.feedback = feedback;
	}

	@Override
	public List<Count> counts() {
		List<Count> counts = new ArrayList<>();
		this.feedback.statistics().ifPresent(statistics -> {
			counts.add(new Count("privateFeedback", "COUNT", statistics.count()));
			counts.add(new Count("privateFeedback", "AVERAGE_TENTHS",
					statistics.average().multiply(BigDecimal.TEN).longValue()));
		});
		return counts;
	}

}
