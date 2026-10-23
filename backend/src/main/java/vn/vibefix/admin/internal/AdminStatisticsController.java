package vn.vibefix.admin.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.PlatformCounts;
import vn.vibefix.shared.PlatformCounts.Unit;

/**
 * ADM-9 (FR-62, FR-35, FR-78, SM-9, SM-C2): every module's figures, grouped in a fixed order and each
 * group's keys in their own order, each with its unit: counts, and since Story 17.2 rates and averages.
 * No figure names a person.
 */
@RestController
@Access(Gate.ADMINISTRATOR)
class AdminStatisticsController {

	static final List<String> ORDER = List.of("usersByRole", "studentsByVerification", "mentorsByApproval",
			"mentorApplicationsByDecision", "lockedAccounts", "teamsBySize", "crewsBySize", "jobsByState", "jobsByType",
			"rates", "workItemReviews", "milestones", "acceptances", "ratings", "privateFeedback", "disputesByOutcome",
			"teamIssuesByOutcome");

	record StatisticView(String key, long value, Unit unit) {
	}

	record StatisticGroupView(String group, List<StatisticView> counts) {
	}

	record StatisticsView(List<StatisticGroupView> groups) {
	}

	private final List<PlatformCounts> sources;

	AdminStatisticsController(List<PlatformCounts> sources) {
		this.sources = sources;
	}

	@GetMapping("/api/v1/admin/statistics")
	StatisticsView statistics() {
		Map<String, List<StatisticView>> groups = new LinkedHashMap<>();
		ORDER.forEach(group -> groups.put(group, new ArrayList<>()));
		this.sources.forEach(source -> source.counts()
			.forEach(count -> groups.computeIfAbsent(count.group(), group -> new ArrayList<>())
				.add(new StatisticView(count.key(), count.value(), count.unit()))));
		return new StatisticsView(groups.entrySet()
			.stream()
			.map(entry -> new StatisticGroupView(entry.getKey(),
					entry.getValue().stream().sorted((a, b) -> a.key().compareTo(b.key())).toList()))
			.toList());
	}

}
