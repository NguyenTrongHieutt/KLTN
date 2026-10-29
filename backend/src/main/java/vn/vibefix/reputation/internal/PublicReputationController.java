package vn.vibefix.reputation.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.JobRoles.History;
import vn.vibefix.job.JobRoles.RoleHistory;
import vn.vibefix.reputation.PortfolioEntry;
import vn.vibefix.reputation.RatingRole;
import vn.vibefix.reputation.RatingSummary;
import vn.vibefix.reputation.Reputation;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * PUB-4's two histories (FR-50, FR-51, UX-DR96, R17, Story 21.3), for anyone: "Khi làm người hướng dẫn" and "Khi
 * làm thành viên nhóm", each with the Jobs closed with Accepted work in that role ({@code job.historyOf}), that
 * role's visible Ratings ({@code reputation.visibleSummary}), and that role's published Portfolio Entries. The same
 * history in short serves a page of Bid cards in one call (CUS-8, CUS-13). Never private feedback (AD-33), a running
 * Job, its Customer, or its co-members: Jobs are counted by Job Type, and a Product is named only where its Customer
 * allowed it. A locked person, an unknown username, or one two accounts hold, has no history here.
 */
@RestController
@Access(Gate.PUBLIC)
class PublicReputationController {

	/** The Bid cards of one page (AD-36). */
	static final int MAX_USERNAMES = 20;

	/** A Job Type in one role's history: how many Customers (as Mentor) or Mentors (as Crew member) it counts. */
	record HistoryJobTypeView(String jobType, int count) {
	}

	/**
	 * One role's history on PUB-4.
	 * @param jobs the Jobs closed with Accepted work in the role; 0 for a role never held ("Chưa có")
	 * @param jobTypes per Job Type, one per counterpart (AD-17), by Job Type
	 * @param average the role's visible Ratings; {@code null} without one
	 * @param portfolio the role's published Portfolio Entries, the latest Job first
	 */
	record RoleReputationView(int jobs, List<HistoryJobTypeView> jobTypes, @Nullable BigDecimal average, int count,
			List<PortfolioEntry> portfolio) {
	}

	/** @param newcomer no Job closed with Accepted work as Mentor: "Mới" (FR-14) */
	record PersonReputationView(RoleReputationView mentor, RoleReputationView crew, boolean newcomer) {
	}

	/**
	 * One role's history on a Bid card.
	 * @param portfolio how many published Portfolio Entries the role holds
	 */
	record RoleSummaryView(int jobs, @Nullable BigDecimal average, int count, int portfolio) {
	}

	/** A person's two histories in short, by the username asked for. */
	record ReputationHistoryView(String username, RoleSummaryView mentor, RoleSummaryView crew, boolean newcomer) {
	}

	private final Reputation reputation;

	private final Portfolios portfolios;

	private final PublicProfiles profiles;

	private final JobRoles roles;

	PublicReputationController(Reputation reputation, Portfolios portfolios, PublicProfiles profiles, JobRoles roles) {
		this.reputation = reputation;
		this.portfolios = portfolios;
		this.profiles = profiles;
		this.roles = roles;
	}

	@GetMapping("/api/v1/users/{username}/reputation")
	PersonReputationView person(@PathVariable String username) {
		UUID person = this.profiles.unlockedAccountsOf(List.of(username)).get(username);
		if (person == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		History history = this.roles.historyOf(person);
		List<PortfolioEntry> portfolio = this.reputation.portfolio(person);
		return new PersonReputationView(
				role(history.mentor(), this.reputation.visibleSummary(person, RatingRole.MENTOR), portfolio, "MENTOR"),
				role(history.crew(), this.reputation.visibleSummary(person, RatingRole.CREW), portfolio, "CREW"),
				this.reputation.newcomer(person));
	}

	/**
	 * The histories in short of up to a page of people, in the order asked; a username with no history here is left
	 * out, so the call tells nothing PUB-4 would not.
	 */
	@GetMapping("/api/v1/reputation/histories")
	List<ReputationHistoryView> histories(
			@RequestParam(name = "username", required = false) @Nullable List<String> usernames) {
		if (usernames == null || usernames.isEmpty()) {
			return List.of();
		}
		List<String> asked = usernames.stream().distinct().toList();
		if (asked.size() > MAX_USERNAMES) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Map<String, UUID> accounts = this.profiles.unlockedAccountsOf(asked);
		List<UUID> ids = accounts.values().stream().distinct().toList();
		Map<UUID, History> histories = this.roles.historiesOf(ids);
		Map<UUID, RatingSummary> asMentor = this.reputation.visibleSummaries(ids, RatingRole.MENTOR);
		Map<UUID, RatingSummary> asCrew = this.reputation.visibleSummaries(ids, RatingRole.CREW);
		Map<UUID, Map<String, Integer>> entries = this.portfolios.publishedCounts(ids);
		Set<UUID> newcomers = this.reputation.newcomers(ids);
		List<ReputationHistoryView> found = new ArrayList<>();
		for (String username : asked) {
			UUID person = accounts.get(username);
			if (person == null) {
				continue;
			}
			History history = histories.getOrDefault(person, History.EMPTY);
			Map<String, Integer> counts = entries.getOrDefault(person, Map.of());
			found.add(new ReputationHistoryView(username,
					summary(history.mentor(), asMentor.getOrDefault(person, RatingSummary.NONE),
							counts.getOrDefault("MENTOR", 0)),
					summary(history.crew(), asCrew.getOrDefault(person, RatingSummary.NONE), counts.getOrDefault("CREW", 0)),
					newcomers.contains(person)));
		}
		return found;
	}

	private static RoleReputationView role(RoleHistory history, RatingSummary ratings, List<PortfolioEntry> portfolio,
			String role) {
		List<HistoryJobTypeView> jobTypes = new TreeMap<>(history.jobTypes()).entrySet()
			.stream()
			.map(type -> new HistoryJobTypeView(type.getKey(), type.getValue()))
			.toList();
		return new RoleReputationView(history.jobs(), jobTypes, ratings.average(), ratings.count(),
				portfolio.stream().filter(entry -> role.equals(entry.role())).toList());
	}

	private static RoleSummaryView summary(RoleHistory history, RatingSummary ratings, int portfolio) {
		return new RoleSummaryView(history.jobs(), ratings.average(), ratings.count(), portfolio);
	}

}
