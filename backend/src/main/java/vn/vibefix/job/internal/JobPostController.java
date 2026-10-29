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
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.catalog.SkillCategory;
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.CustomerNames.CustomerSince;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.JobPhase;
import vn.vibefix.job.JobPosts.JobNeed;
import vn.vibefix.job.internal.JobController.ScreenshotView;
import vn.vibefix.job.internal.JobPostQueries.Filter;
import vn.vibefix.job.internal.JobPostQueries.Page;
import vn.vibefix.job.internal.JobPostQueries.Post;
import vn.vibefix.job.internal.JobPostQueries.Recruitment;
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

/**
 * MEN-2 and MEN-3, "Tìm việc" (X1; FR-12, FR-13, AD-20, AD-36): every Job that needs people (Story 21.1), for any
 * user, with no approval (Story 19.7) and no account role (Story 20.3). Reading needs GitHub linked ({@code FORBIDDEN
 * GITHUB_NOT_LINKED}, FR-80), and a locked account is refused (AD-19), both read on every request; an Administrator
 * finds nothing. The list never shows a Job where the caller holds a role (AD-36, AD-43); the detail of one still
 * reads while it is listed, and the SPA shows "Bạn là {role} của công việc này" in place of an action. A row or the
 * detail shows the Job Post, its phase, what it needs, the Customer's kind and month joined (never their name or
 * contact details), the Mentor once there is one, and while recruiting the open slots, the Skills sought and the
 * note: never the Agreement, Milestones, prices, Crew, bids or chats (UX-DR97).
 */
@RestController
@RequestMapping("/api/v1/mentor")
@Access(Gate.SIGNED_IN)
class JobPostController {

	/**
	 * One "Tìm việc" row (UX-DR97); the Rating summary comes from {@code reputation} for the page in one call.
	 * @param openedAt when the Job Post opened
	 * @param phase the phase label; never empty for a listed Job
	 * @param customerKind {@code INDIVIDUAL} or {@code BUSINESS}; {@code null} before the Customer saved CUS-11
	 * @param customerJoinedAt when the Customer's account was created
	 * @param mentor the Job's Mentor, once J3 recorded one
	 * @param recruitment what the Mentor's recruitment seeks, while it is on
	 */
	record JobPostSummaryView(UUID id, String title, String productName, List<JobType> jobTypes, List<String> skills,
			@Nullable Budget budget, @Nullable LocalDate deadline, Instant openedAt, @Nullable JobPhase phase,
			JobNeed need, @Nullable String customerKind, @Nullable Instant customerJoinedAt,
			@Nullable PostMentorView mentor, @Nullable PostRecruitmentView recruitment) {
	}

	/**
	 * The Job's Mentor as their public profile names them, linked to it (PUB-5 at {@code /mentor/{login}} until
	 * Story 21.3).
	 */
	record PostMentorView(String displayName, @Nullable String githubLogin) {
	}

	/**
	 * What the Mentor's Crew recruitment seeks while it is on (AD-41).
	 * @param openSlots the places still open: "Còn {n} chỗ"
	 * @param skills the Skills sought, by their current names
	 * @param note the Mentor's note
	 */
	record PostRecruitmentView(int openSlots, List<String> skills, @Nullable String note) {
	}

	record JobPostSkillView(UUID id, String name, String hint) {
	}

	/**
	 * One listed Job as MEN-3 shows it.
	 * @param lapsesAt when the Job Post lapses, while Open
	 */
	record JobPostView(UUID id, UUID productId, String title, String productName, String productDescription,
			List<String> productTools, int productOpenFindings, @Nullable String problem, @Nullable String expected,
			@Nullable String appUsers, @Nullable String doneCheck, List<JobType> jobTypes, List<JobPostSkillView> skills,
			@Nullable Budget budget, @Nullable LocalDate deadline, Instant openedAt, @Nullable Instant lapsesAt,
			List<ScreenshotView> screenshots, @Nullable JobPhase phase, JobNeed need, @Nullable PostMentorView mentor,
			@Nullable PostRecruitmentView recruitment) {
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

	private final CustomerNames customers;

	private final MentorDirectory mentors;

	private final Clock clock;

	JobPostController(JobPostQueries posts, Jobs jobs, MentorCapacity capacity, Catalog catalog,
			ProductDirectory products, Scans scans, GitHubGate gitHub, SearchWindow searches, CurrentCaller callers,
			CustomerNames customers, MentorDirectory mentors, Clock clock) {
		this.posts = posts;
		this.jobs = jobs;
		this.capacity = capacity;
		this.catalog = catalog;
		this.products = products;
		this.scans = scans;
		this.gitHub = gitHub;
		this.searches = searches;
		this.callers = callers;
		this.customers = customers;
		this.mentors = mentors;
		this.clock = clock;
	}

	/**
	 * MEN-2: 20 listed Jobs a page, newest first, or by relevance to a keyword. Each filter given applies at once;
	 * the phase filter matches any of the phases given, and the Skill filter any of the active chosen Skills and the
	 * active Skills of the chosen Categories (AD-38). A keyword counts toward the search limit (AD-34). The Customers'
	 * kinds, the phases and the Mentors are read for the whole page at once.
	 * @param q the keyword, accents and letter case ignored (AD-36)
	 * @param phases "Tình trạng": the phases to keep
	 * @param within keeps Jobs due by today plus this many days, in Vietnam
	 */
	@GetMapping("/job-posts")
	PageResponse<JobPostSummaryView> list(@RequestParam(required = false) @Nullable String q,
			@RequestParam(name = "phase", required = false) @Nullable List<JobPhase> phases,
			@RequestParam(name = "type", required = false) @Nullable List<JobType> types,
			@RequestParam(name = "skill", required = false) @Nullable List<UUID> skills,
			@RequestParam(name = "category", required = false) @Nullable List<UUID> categories,
			@RequestParam(name = "budget", required = false) @Nullable List<Budget> budgets,
			@RequestParam(required = false) @Nullable Integer within, @RequestParam(defaultValue = "0") int page) {
		UUID reader = linkedReader();
		if (page < 0 || (within != null && (within < 1 || within > 366))) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		KeywordQuery keyword = q == null ? null : KeywordQuery.of(q, "search_vector").orElse(null);
		if (keyword != null) {
			this.searches.record(reader);
		}
		LocalDate dueBy = within == null ? null : LocalDate.now(this.clock.withZone(J1Publish.VIETNAM)).plusDays(within);
		Filter filter = new Filter(orEmpty(phases), orEmpty(types), skillFilter(orEmpty(skills), orEmpty(categories)),
				orEmpty(budgets), dueBy, keyword);
		Page found = this.posts.list(filter, reader, page);
		Map<UUID, JobPhase> phaseOf = this.posts.phases(found.items());
		Map<UUID, CustomerSince> since = this.customers
			.sinces(found.items().stream().map(post -> post.job().customerId()).toList());
		Map<UUID, PostMentorView> mentorOf = mentors(found.items());
		Map<UUID, String> names = new HashMap<>();
		List<JobPostSummaryView> items = found.items().stream().map(post -> {
			Job job = post.job();
			CustomerSince customer = since.get(job.customerId());
			return new JobPostSummaryView(job.id(), job.title(), productName(names, job.productId()), job.jobTypes(),
					job.skillNames(), job.budget(), job.deadline(), post.openedAt(), phaseOf.get(job.id()), post.need(),
					customer == null ? null : customer.kind(), customer == null ? null : customer.joinedAt(),
					job.mentorId() == null ? null : mentorOf.get(job.mentorId()), recruitment(post.recruitment()));
		}).toList();
		return new PageResponse<>(items, found.page(), JobPostQueries.PAGE_SIZE, found.total());
	}

	/** MEN-3's FR-13 banner and the bid form's gate. */
	@GetMapping("/capacity")
	CapacityView capacity() {
		Capacity mine = this.capacity.of(linkedReader());
		return new CapacityView(mine.teamSize(), mine.freeStudents(), mine.activeJobs(), mine.jobLimit(), mine.reasons());
	}

	/** MEN-3: one listed Job; its ID is the Job's. A Job no longer listed answers {@code NOT_FOUND}. */
	@GetMapping("/job-posts/{postId}")
	JobPostView post(@PathVariable UUID postId) {
		linkedReader();
		Post post = this.posts.find(postId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Job job = post.job();
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
				job.deadline(), post.openedAt(), job.state() == JobState.OPEN ? job.lapsesAt() : null,
				this.jobs.screenshots(job.id()).stream().map(ScreenshotView::of).toList(),
				this.posts.phases(List.of(post)).get(job.id()), post.need(),
				job.mentorId() == null ? null : mentors(List.of(post)).get(job.mentorId()),
				recruitment(post.recruitment()));
	}

	/** The Mentors of these Jobs that have one, by account, from one {@code identity} read. */
	private Map<UUID, PostMentorView> mentors(List<Post> posts) {
		List<UUID> ids = posts.stream().map(post -> post.job().mentorId()).filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		return this.mentors.cards(ids)
			.stream()
			.collect(Collectors.toMap(MentorCard::accountId,
					card -> new PostMentorView(card.displayName(), card.githubLogin()), (first, second) -> first));
	}

	/** What the recruitment seeks while it is on; nothing while it is off. */
	private static @Nullable PostRecruitmentView recruitment(Recruitment recruitment) {
		return recruitment.on()
				? new PostRecruitmentView(recruitment.openSlots(), recruitment.skillNames(), recruitment.note()) : null;
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

	private static <T> List<T> orEmpty(@Nullable List<T> values) {
		return values == null ? List.of() : values;
	}

	/**
	 * The user calling, unlocked and with GitHub linked (X1, AD-19, re-read on every request); an Administrator
	 * finds nothing.
	 */
	private UUID linkedReader() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID reader = caller.requireAccountId();
		this.gitHub.requireGitHubLinked(reader);
		return reader;
	}

}
