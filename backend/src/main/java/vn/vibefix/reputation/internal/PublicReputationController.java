package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.reputation.PortfolioEntry;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * PUB-5's and PUB-4's Ratings and Portfolio Entries (FR-51), for anyone: visible Ratings only, never
 * private feedback (AD-33). The profiles themselves are {@code team}'s.
 */
@RestController
@Access(Gate.PUBLIC)
class PublicReputationController {

	/**
	 * @param newcomer no Job closed with Accepted work: PUB-5's "Mới" (FR-14)
	 */
	record MentorReputationView(@Nullable BigDecimal average, int count, boolean newcomer,
			List<PortfolioEntry> portfolio) {
	}

	record StudentReputationView(@Nullable BigDecimal average, int count, List<PortfolioEntry> portfolio) {
	}

	private final Reputation reputation;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	PublicReputationController(Reputation reputation, MentorDirectory mentors, StudentDirectory students) {
		this.reputation = reputation;
		this.mentors = mentors;
		this.students = students;
	}

	@GetMapping("/api/v1/mentors/{username}/reputation")
	MentorReputationView mentor(@PathVariable String username) {
		UUID mentor = this.mentors.byLogin(username)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))
			.card()
			.accountId();
		RatingSummary ratings = this.reputation.visibleSummary(mentor);
		return new MentorReputationView(ratings.average(), ratings.count(), this.reputation.newcomer(mentor),
				this.reputation.portfolio(mentor));
	}

	@GetMapping("/api/v1/students/{studentLogin}/reputation")
	StudentReputationView student(@PathVariable String studentLogin) {
		UUID student = this.students.profileByLogin(studentLogin)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND))
			.card()
			.accountId();
		RatingSummary ratings = this.reputation.visibleSummary(student);
		return new StudentReputationView(ratings.average(), ratings.count(), this.reputation.portfolio(student));
	}

}
