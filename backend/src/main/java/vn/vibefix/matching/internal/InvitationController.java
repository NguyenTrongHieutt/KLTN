package vn.vibefix.matching.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.identity.PublicProfiles.VerifiedFact;
import vn.vibefix.job.JobRoles;
import vn.vibefix.matching.internal.Invitations.Invitation;
import vn.vibefix.matching.internal.Invitations.MentorInvitation;
import vn.vibefix.matching.internal.Invitations.Quota;
import vn.vibefix.matching.internal.Invitations.Relation;
import vn.vibefix.matching.internal.Invitations.Status;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.SearchWindow;

/**
 * CUS-8 "Tìm người hướng dẫn" and invitations to apply (FR-14, AD-20, AD-36, SG-41, Story 21.3): the Job's Customer
 * searches the public fields of GitHub-verified, unlocked users by keyword and Skills, within the one search window
 * shared with "Tìm việc" (60 a minute), and invites them to bid ("Mời chào giá"); the invited user reads their
 * invitation on MEN-3. Contact details, Balances and Team sizes never appear (SG-42).
 */
@RestController
@Access(Gate.SIGNED_IN)
class InvitationController {

	/** A result page (AD-36). */
	static final int PAGE_SIZE = 20;

	/** "Mời chào giá": the user's account ID from a search card. */
	record InviteRequest(@NotNull UUID mentorId) {
	}

	/**
	 * A Profile result card (UX-DR100): no contact details, no Balance, no Team size.
	 * @param yearsOfExperience "Tự khai"; absent while the user has not declared it
	 * @param verified the three Verified facts, in their fixed order
	 * @param relation whether the Customer invited this user or the user bid
	 * @param available whether the user may be invited: unlocked and with GitHub linked, as every listed one is
	 */
	record MentorResultView(UUID accountId, @Nullable String githubLogin, String displayName,
			@Nullable Integer yearsOfExperience, List<String> skills, List<VerifiedFact> verified, Relation relation,
			boolean available) {
	}

	record MentorSearchView(List<MentorResultView> items, int page, int size, long total) {
	}

	/**
	 * What the Customer may still send today.
	 * @param limit the used-up counter, if any
	 */
	record QuotaView(int left, @Nullable MatchingReason limit) {

		static QuotaView of(Quota quota) {
			return new QuotaView(quota.left(), quota.limit());
		}

	}

	record SentInvitationView(UUID mentorId, String displayName, Status status, Instant invitedAt) {
	}

	record InvitationsView(int left, @Nullable MatchingReason limit, List<SentInvitationView> invitations) {
	}

	/** MEN-3's invitation note, or its lapsed card. */
	/** The invitation as its Mentor sees it: never the Customer's name before selection (author decision 2026-10-21). */
	record MentorInvitationView(UUID id, String jobTitle, Status status, Instant invitedAt) {

		static MentorInvitationView of(MentorInvitation invitation) {
			return new MentorInvitationView(invitation.id(), invitation.jobTitle(), invitation.status(),
					invitation.invitedAt());
		}

	}

	private final Invitations invitations;

	private final MentorDirectory mentors;

	private final PublicProfiles profiles;

	private final SearchWindow searches;

	private final Catalog catalog;

	private final GitHubGate gate;

	private final CurrentCaller callers;

	private final JobRoles roles;

	InvitationController(Invitations invitations, MentorDirectory mentors, PublicProfiles profiles,
			SearchWindow searches, Catalog catalog, GitHubGate gate, CurrentCaller callers, JobRoles roles) {
		this.roles = roles;
		this.invitations = invitations;
		this.mentors = mentors;
		this.profiles = profiles;
		this.searches = searches;
		this.catalog = catalog;
		this.gate = gate;
		this.callers = callers;
	}

	/**
	 * The Job's Customer searches GitHub-verified, unlocked users (FR-14, AD-36): 20 a page by relevance, then account
	 * ID, never by Rating; the Skill filter keeps users with any active chosen Skill or any active Skill of the
	 * chosen Categories (AD-38). A blank keyword finds no one and is no search; a keyword counts in the one search
	 * window (AD-34).
	 * @throws vn.vibefix.shared.RateLimitedException past 60 searches in a minute, "Tìm việc"'s included
	 */
	@GetMapping("/api/v1/jobs/{jobId}/mentors")
	MentorSearchView search(@PathVariable UUID jobId, @RequestParam(defaultValue = "") @Size(max = 200) String q,
			@RequestParam(name = "skill", required = false) @Nullable List<UUID> skills,
			@RequestParam(name = "category", required = false) @Nullable List<UUID> categories,
			@RequestParam(defaultValue = "0") int page) {
		UUID customer = customerOf(jobId);
		if (page < 0) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Set<UUID> skillIds = SkillFilter.resolve(this.catalog, skills, categories);
		if (q.isBlank()) {
			return new MentorSearchView(List.of(), page, PAGE_SIZE, 0);
		}
		this.searches.record(customer);
		if (skillIds != null && skillIds.isEmpty()) {
			return new MentorSearchView(List.of(), page, PAGE_SIZE, 0);
		}
		// The Job's Customer is never its Mentor (AD-43), so the search never lists them.
		Page<MentorCard> found = this.mentors.search(q, skillIds == null ? Set.of() : skillIds,
				this.callers.get().requireAccountId(), PageRequest.of(page, PAGE_SIZE));
		List<UUID> ids = found.getContent().stream().map(MentorCard::accountId).toList();
		Map<UUID, Relation> relations = this.invitations.relations(jobId, ids);
		Map<UUID, PersonFacts> facts = this.profiles.factsOf(ids);
		List<MentorResultView> items = found.getContent()
			.stream()
			.map(card -> new MentorResultView(card.accountId(), card.githubLogin(), card.displayName(),
					card.yearsOfExperience(), card.skills(), verified(facts.get(card.accountId())),
					relations.getOrDefault(card.accountId(), Relation.NONE), card.available()))
			.toList();
		return new MentorSearchView(items, page, PAGE_SIZE, found.getTotalElements());
	}

	/** The quota and the invitations the Customer sent for the Job (UX-DR49). */
	@GetMapping("/api/v1/jobs/{jobId}/invitations")
	InvitationsView invitations(@PathVariable UUID jobId) {
		UUID customer = customerOf(jobId);
		Quota quota = this.invitations.quota(customer, jobId);
		List<Invitation> sent = this.invitations.ofJob(customer, jobId);
		Map<UUID, MentorCard> cards = this.mentors.cards(sent.stream().map(Invitation::mentorId).toList())
			.stream()
			.collect(Collectors.toMap(MentorCard::accountId, Function.identity()));
		return new InvitationsView(quota.left(), quota.limit(), sent.stream().map(invitation -> {
			MentorCard card = cards.get(invitation.mentorId());
			return new SentInvitationView(invitation.mentorId(), card == null ? "" : card.displayName(),
					invitation.status(), invitation.invitedAt());
		}).toList());
	}

	/** "Mời chào giá" (FR-14, SG-41): no Confirmation dialog; the server's answer is the new quota. */
	@PostMapping("/api/v1/jobs/{jobId}/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	QuotaView invite(@PathVariable UUID jobId, @Valid @RequestBody InviteRequest body) {
		return QuotaView.of(this.invitations.invite(customerOf(jobId), jobId, body.mentorId()));
	}

	/** MEN-3: the Mentor's own invitation, open, answered, or lapsed; behind the GitHub gate of "Tìm việc" (X1). */
	@GetMapping("/api/v1/mentor/job-posts/{postId}/invitation")
	MentorInvitationView mine(@PathVariable UUID postId) {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireGitHubLinked(mentor);
		return this.invitations.forMentor(mentor, postId)
			.map(MentorInvitationView::of)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** A person's Verified facts, or none for one {@code identity} no longer knows. */
	static List<VerifiedFact> verified(@Nullable PersonFacts facts) {
		return facts == null ? List.of() : facts.verified();
	}

	/** The Job's Customer calling, by their role in the Job (AD-43); anyone else finds nothing. */
	private UUID customerOf(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isCustomer()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
