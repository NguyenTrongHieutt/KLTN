package vn.vibefix.matching.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobPosts.OpenPost;
import vn.vibefix.matching.internal.Invitations.Relation;
import vn.vibefix.matching.internal.Recommendations.Candidate;
import vn.vibefix.matching.internal.Recommendations.JobTypeMatch;
import vn.vibefix.matching.internal.Recommendations.RecommendedPost;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;
import vn.vibefix.team.Teams;

/**
 * CUS-8's "Đề xuất cho công việc này" and MEN-2's "Đề xuất cho bạn" (FR-14, AD-17, UX-DR47): the
 * signals are codes the SPA words ("Hợp với …", "Đã làm … công việc …", "Mới"); the Skill filter
 * narrows them like the search.
 */
@RestController
@Access(Gate.SIGNED_IN)
class RecommendationController {

	/**
	 * Why a Recommendation is there.
	 * @param sharedSkills the shared Skills' current names, in the Job Post's order
	 * @param newcomer no Job closed with Accepted work: "Mới"
	 */
	record SignalsView(List<String> sharedSkills, List<JobTypeMatchView> jobTypeMatches, boolean newcomer) {
	}

	record JobTypeMatchView(String jobType, int count) {

		static List<JobTypeMatchView> of(List<JobTypeMatch> matches) {
			return matches.stream().map(match -> new JobTypeMatchView(match.jobType(), match.count())).toList();
		}

	}

	/**
	 * A Mentor for the Job Post: the search card's fields and the signals.
	 * @param available always true: the pool holds only Mentors who may be invited (AD-17)
	 */
	record RecommendedMentorView(UUID accountId, @Nullable String githubLogin, String displayName,
			@Nullable Integer yearsOfExperience, List<String> skills, int teamSize, Relation relation, boolean available,
			SignalsView signals) {
	}

	/**
	 * A Job Post for the Mentor.
	 * @param invited a Customer's open invitation to apply (FR-14): "Bạn được mời"
	 */
	record RecommendedPostView(UUID id, String title, String productName, List<String> jobTypes,
			@Nullable String budget, @Nullable LocalDate deadline, Instant openedAt, boolean invited,
			SignalsView signals) {
	}

	private final Recommendations recommendations;

	private final Invitations invitations;

	private final JobPosts posts;

	private final MentorDirectory mentors;

	private final Teams teams;

	private final ProductDirectory products;

	private final Catalog catalog;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	RecommendationController(Recommendations recommendations, Invitations invitations, JobPosts posts,
			MentorDirectory mentors, Teams teams, ProductDirectory products, Catalog catalog, ActiveAccount gate,
			CurrentCaller callers) {
		this.recommendations = recommendations;
		this.invitations = invitations;
		this.posts = posts;
		this.mentors = mentors;
		this.teams = teams;
		this.products = products;
		this.catalog = catalog;
		this.gate = gate;
		this.callers = callers;
	}

	/** The owning Customer's Recommendations for their Job; none once it is not Open. */
	@GetMapping("/api/v1/jobs/{jobId}/recommendations")
	List<RecommendedMentorView> forJobPost(@PathVariable UUID jobId,
			@RequestParam(name = "skill", required = false) @Nullable List<UUID> skills,
			@RequestParam(name = "category", required = false) @Nullable List<UUID> categories) {
		JobPostState post = ownPost(jobId);
		if (!post.open() || post.hidden()) {
			return List.of();
		}
		List<Candidate> found = this.recommendations.forJobPost(post, SkillFilter.resolve(this.catalog, skills, categories));
		List<UUID> ids = found.stream().map(Candidate::mentorId).toList();
		Map<UUID, MentorCard> cards = this.mentors.cards(ids)
			.stream()
			.collect(Collectors.toMap(MentorCard::accountId, Function.identity()));
		Map<UUID, List<UUID>> members = this.teams.membersOf(ids);
		Map<UUID, Relation> relations = this.invitations.relations(jobId, ids);
		Map<UUID, String> names = skillNames(found.stream().flatMap(candidate -> candidate.sharedSkillIds().stream()).toList());
		return found.stream().filter(candidate -> cards.containsKey(candidate.mentorId())).map(candidate -> {
			MentorCard card = cards.get(candidate.mentorId());
			return new RecommendedMentorView(card.accountId(), card.githubLogin(), card.displayName(),
					card.yearsOfExperience(), card.skills(), members.getOrDefault(card.accountId(), List.of()).size(),
					relations.getOrDefault(card.accountId(), Relation.NONE), card.available(),
					new SignalsView(named(candidate.sharedSkillIds(), names), JobTypeMatchView.of(candidate.jobTypeMatches()),
							candidate.newcomer()));
		}).toList();
	}

	/** A Mentor's Recommendations on MEN-2. */
	@GetMapping("/api/v1/mentor/recommendations")
	List<RecommendedPostView> forMentor(@RequestParam(name = "skill", required = false) @Nullable List<UUID> skills,
			@RequestParam(name = "category", required = false) @Nullable List<UUID> categories) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		List<RecommendedPost> found = this.recommendations.forMentor(mentor,
				SkillFilter.resolve(this.catalog, skills, categories));
		Set<UUID> invited = this.invitations.openFor(mentor);
		Map<UUID, String> names = skillNames(found.stream().flatMap(post -> post.sharedSkillIds().stream()).toList());
		Map<UUID, String> productNames = new HashMap<>();
		return found.stream().map(recommended -> {
			OpenPost post = recommended.post();
			String productName = productNames.computeIfAbsent(post.productId(),
					id -> this.products.find(id).map(ProductInfo::name).orElse(""));
			return new RecommendedPostView(post.jobId(), post.title(), productName, post.jobTypes(), post.budget(),
					post.deadline(), post.openedAt(), invited.contains(post.jobId()),
					new SignalsView(named(recommended.sharedSkillIds(), names),
							JobTypeMatchView.of(recommended.jobTypeMatches()), false));
		}).toList();
	}

	private Map<UUID, String> skillNames(List<UUID> ids) {
		return this.catalog.byIds(ids.stream().distinct().toList())
			.stream()
			.collect(Collectors.toMap(Skill::id, Skill::name));
	}

	private static List<String> named(List<UUID> ids, Map<UUID, String> names) {
		return ids.stream().map(names::get).filter(name -> name != null).toList();
	}

	/** The Job's Customer calling; anyone else finds nothing. */
	private JobPostState ownPost(UUID jobId) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.CUSTOMER)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.posts.post(jobId)
			.filter(post -> post.customerId().equals(caller.requireAccountId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
