package vn.vibefix.job.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.catalog.SkillCategory;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.job.internal.JobController.ScreenshotView;
import vn.vibefix.job.internal.JobPostQueries.Filter;
import vn.vibefix.job.internal.JobPostQueries.Page;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.job.internal.MentorCapacity.Capacity;
import vn.vibefix.job.internal.MentorCapacity.Reason;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.scanning.Scans;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.KeywordQuery;
import vn.vibefix.shared.PageResponse;
import vn.vibefix.shared.Role;

/**
 * MEN-2 and MEN-3, "Tìm việc" (X1; FR-12, FR-13, AD-20): Open Job Posts that are not hidden, for Mentors only,
 * with no approval (Story 19.7). Reading them needs GitHub linked ({@code FORBIDDEN GITHUB_NOT_LINKED}, FR-80),
 * and a locked account is refused (AD-19), both read on every request; anyone else finds nothing until Story
 * 20.3 replaces the role pre-filter. A Job Post never carries the Customer's name or contact details.
 */
@RestController
@RequestMapping("/api/v1/mentor")
@Access(Gate.SIGNED_IN)
class JobPostController {

	record JobPostSummaryView(UUID id, String title, String productName, List<JobType> jobTypes, List<String> skills,
			@Nullable Budget budget, @Nullable LocalDate deadline, Instant openedAt) {
	}

	record JobPostSkillView(UUID id, String name, String hint) {
	}

	/** One Job Post as MEN-3 shows it. */
	record JobPostView(UUID id, UUID productId, String title, String productName, String productDescription,
			List<String> productTools, int productOpenFindings, @Nullable String problem, @Nullable String expected,
			@Nullable String appUsers, @Nullable String doneCheck, List<JobType> jobTypes, List<JobPostSkillView> skills,
			@Nullable Budget budget, @Nullable LocalDate deadline, Instant openedAt, Instant lapsesAt,
			List<ScreenshotView> screenshots) {
	}

	/** The Mentor's FR-13 position, and why they cannot apply now. */
	record CapacityView(int teamSize, int freeStudents, int activeJobs, int jobLimit, List<Reason> reasons) {
	}

	private final JobPostQueries posts;

	private final Jobs jobs;

	private final MentorCapacity capacity;

	private final Catalog catalog;

	private final ProductDirectory products;

	private final Scans scans;

	private final GitHubGate gitHub;

	private final SearchWindow searches;

	private final CurrentCaller callers;

	private final Clock clock;

	JobPostController(JobPostQueries posts, Jobs jobs, MentorCapacity capacity, Catalog catalog,
			ProductDirectory products, Scans scans, GitHubGate gitHub, SearchWindow searches, CurrentCaller callers,
			Clock clock) {
		this.posts = posts;
		this.jobs = jobs;
		this.capacity = capacity;
		this.catalog = catalog;
		this.products = products;
		this.scans = scans;
		this.gitHub = gitHub;
		this.searches = searches;
		this.callers = callers;
		this.clock = clock;
	}

	/**
	 * MEN-2: 20 Job Posts a page, newest first, or by relevance to a keyword. Each filter given applies
	 * at once; the Skill filter matches any of the active chosen Skills and the active Skills of the
	 * chosen Categories (AD-38). A keyword counts toward the search limit (AD-34).
	 * @param q the keyword, accents and letter case ignored (AD-36)
	 * @param within keeps Job Posts due by today plus this many days, in Vietnam
	 */
	@GetMapping("/job-posts")
	PageResponse<JobPostSummaryView> list(@RequestParam(required = false) @Nullable String q,
			@RequestParam(name = "type", required = false) @Nullable List<JobType> types,
			@RequestParam(name = "skill", required = false) @Nullable List<UUID> skills,
			@RequestParam(name = "category", required = false) @Nullable List<UUID> categories,
			@RequestParam(name = "budget", required = false) @Nullable List<Budget> budgets,
			@RequestParam(required = false) @Nullable Integer within, @RequestParam(defaultValue = "0") int page) {
		UUID mentor = linkedMentor();
		if (page < 0 || (within != null && (within < 1 || within > 366))) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		KeywordQuery keyword = q == null ? null : KeywordQuery.of(q, "search_vector").orElse(null);
		if (keyword != null) {
			this.searches.record(mentor);
		}
		LocalDate dueBy = within == null ? null : LocalDate.now(this.clock.withZone(J1Publish.VIETNAM)).plusDays(within);
		Filter filter = new Filter(orEmpty(types), skillFilter(orEmpty(skills), orEmpty(categories)), orEmpty(budgets),
				dueBy, keyword);
		Page found = this.posts.list(filter, page);
		Map<UUID, String> names = new HashMap<>();
		List<JobPostSummaryView> items = found.items()
			.stream()
			.map(job -> new JobPostSummaryView(job.id(), job.title(), productName(names, job.productId()),
					job.jobTypes(), job.skillNames(), job.budget(), job.deadline(), opened(job)))
			.toList();
		return new PageResponse<>(items, found.page(), JobPostQueries.PAGE_SIZE, found.total());
	}

	/** MEN-2 and MEN-3's FR-13 banners. */
	@GetMapping("/capacity")
	CapacityView capacity() {
		Capacity mine = this.capacity.of(linkedMentor());
		return new CapacityView(mine.teamSize(), mine.freeStudents(), mine.activeJobs(), mine.jobLimit(), mine.reasons());
	}

	/** MEN-3: one Open Job Post that is not hidden; its ID is the Job's. */
	@GetMapping("/job-posts/{postId}")
	JobPostView post(@PathVariable UUID postId) {
		linkedMentor();
		Job job = this.posts.find(postId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		ProductInfo product = this.products.find(job.productId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Map<UUID, Skill> skills = new HashMap<>();
		this.catalog.byIds(job.skillIds()).forEach(skill -> skills.put(skill.id(), skill));
		List<JobPostSkillView> shown = new ArrayList<>();
		for (int i = 0; i < job.skillIds().size(); i++) {
			Skill skill = skills.get(job.skillIds().get(i));
			shown.add(new JobPostSkillView(job.skillIds().get(i), job.skillNames().get(i), skill == null ? "" : skill.hint()));
		}
		int findings = this.scans.openFindingCounts(List.of(product.id())).getOrDefault(product.id(), 0);
		return new JobPostView(job.id(), product.id(), job.title(), product.name(), product.description(), product.tools(),
				findings, job.problem(), job.expected(), job.appUsers(), job.doneCheck(), job.jobTypes(), shown, job.budget(),
				job.deadline(), opened(job), Objects.requireNonNull(job.lapsesAt()),
				this.jobs.screenshots(job.id()).stream().map(ScreenshotView::of).toList());
	}

	/**
	 * The Skills the filter matches, or {@code null} without a Skill filter: the active chosen Skills
	 * and every active Skill of the chosen Categories; inactive ones add nothing.
	 */
	private @Nullable Set<UUID> skillFilter(List<UUID> skills, List<UUID> categories) {
		if (skills.isEmpty() && categories.isEmpty()) {
			return null;
		}
		Set<UUID> ids = new LinkedHashSet<>();
		this.catalog.byIds(skills).stream().filter(Skill::active).forEach(skill -> ids.add(skill.id()));
		if (!categories.isEmpty()) {
			for (SkillCategory category : this.catalog.active()) {
				if (categories.contains(category.id())) {
					category.skills().forEach(skill -> ids.add(skill.id()));
				}
			}
		}
		return ids;
	}

	private String productName(Map<UUID, String> names, UUID productId) {
		return names.computeIfAbsent(productId, id -> this.products.find(id).map(ProductInfo::name).orElse(""));
	}

	private static Instant opened(Job job) {
		return Objects.requireNonNull(job.openedAt());
	}

	private static <T> List<T> orEmpty(@Nullable List<T> values) {
		return values == null ? List.of() : values;
	}

	/**
	 * The Mentor calling, unlocked and with GitHub linked (X1, AD-19, re-read on every request); anyone but a
	 * Mentor finds nothing.
	 */
	private UUID linkedMentor() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gitHub.requireGitHubLinked(mentor);
		return mentor;
	}

}
