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
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.job.JobPosts;
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
import vn.vibefix.shared.Role;
import vn.vibefix.team.Teams;

/**
 * CUS-8 "Tìm người hướng dẫn" and invitations to apply (FR-14, AD-20, AD-36): the Job's Customer
 * searches approved, unlocked Mentors by keyword and Skills and invites them; the invited Mentor reads
 * their invitation on MEN-3. Contact details and Balances never appear (SG-42).
 */
@RestController
@Access(Gate.SIGNED_IN)
class InvitationController {

	/** A result page (AD-36). */
	static final int PAGE_SIZE = 20;

	/** "Mời ứng tuyển": the Mentor's account ID from a search card. */
	record InviteRequest(@NotNull UUID mentorId) {
	}

	/**
	 * A search card: no contact details, no Balance.
	 * @param relation whether the Customer invited this Mentor or the Mentor applied
	 */
	record MentorResultView(UUID accountId, @Nullable String githubLogin, String displayName, int yearsOfExperience,
			List<String> skills, int teamSize, Relation relation) {
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

	private final JobPosts posts;

	private final MentorDirectory mentors;

	private final Teams teams;

	private final Catalog catalog;

	private final MentorGate gate;

	private final CurrentCaller callers;

	InvitationController(Invitations invitations, JobPosts posts, MentorDirectory mentors, Teams teams,
			Catalog catalog, MentorGate gate, CurrentCaller callers) {
		this.invitations = invitations;
		this.posts = posts;
		this.mentors = mentors;
		this.teams = teams;
		this.catalog = catalog;
		this.gate = gate;
		this.callers = callers;
	}

	/**
	 * The Job's Customer searches Mentors (FR-14): 20 a page by relevance, then account ID, never by
	 * Rating; the Skill filter keeps Mentors with any active chosen Skill or any active Skill of the
	 * chosen Categories (AD-38). A blank keyword finds no one.
	 */
	@GetMapping("/api/v1/jobs/{jobId}/mentors")
	MentorSearchView search(@PathVariable UUID jobId, @RequestParam(defaultValue = "") @Size(max = 200) String q,
			@RequestParam(name = "skill", required = false) @Nullable List<UUID> skills,
			@RequestParam(name = "category", required = false) @Nullable List<UUID> categories,
			@RequestParam(defaultValue = "0") int page) {
		customerOf(jobId);
		if (page < 0) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Set<UUID> skillIds = SkillFilter.resolve(this.catalog, skills, categories);
		if (q.isBlank() || (skillIds != null && skillIds.isEmpty())) {
			return new MentorSearchView(List.of(), page, PAGE_SIZE, 0);
		}
		Page<MentorCard> found = this.mentors.search(q, skillIds == null ? Set.of() : skillIds,
				PageRequest.of(page, PAGE_SIZE));
		Map<UUID, Relation> relations = this.invitations.relations(jobId,
				found.getContent().stream().map(MentorCard::accountId).toList());
		List<MentorResultView> items = found.getContent()
			.stream()
			.map(card -> new MentorResultView(card.accountId(), card.githubLogin(), card.displayName(),
					card.yearsOfExperience(), card.skills(), this.teams.sizeOf(card.accountId()),
					relations.getOrDefault(card.accountId(), Relation.NONE)))
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

	/** "Mời ứng tuyển" (FR-14): no Confirmation dialog; the server's answer is the new quota. */
	@PostMapping("/api/v1/jobs/{jobId}/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	QuotaView invite(@PathVariable UUID jobId, @Valid @RequestBody InviteRequest body) {
		return QuotaView.of(this.invitations.invite(customerOf(jobId), jobId, body.mentorId()));
	}

	/** MEN-3: the Mentor's own invitation to this Job, open, answered, or lapsed; nobody else's. */
	@GetMapping("/api/v1/mentor/job-posts/{postId}/invitation")
	MentorInvitationView mine(@PathVariable UUID postId) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		return this.invitations.forMentor(mentor, postId)
			.map(MentorInvitationView::of)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The Job's Customer calling; anyone else finds nothing. */
	private UUID customerOf(UUID jobId) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.CUSTOMER)
				|| !this.posts.customerOf(jobId).map(caller.requireAccountId()::equals).orElse(false)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
