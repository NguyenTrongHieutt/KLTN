package vn.vibefix.dispute.internal;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.chat.ChatEvidence;
import vn.vibefix.delivery.DeliveryEvidence;
import vn.vibefix.dispute.internal.SplitEvidencePackage.Tab;
import vn.vibefix.dispute.internal.SplitResolutions.Resolution;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.CaseParty;
import vn.vibefix.job.MilestoneSplits.SplitCase;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.work.WorkEvidence;

/**
 * The Split Evidence Package (FR-84, AD-8, AD-19, AD-20, AD-40, UX-DR107): the tabs {@code dispute} assembles on demand from the
 * owning modules' queries, each view audited, for the case's assignee only. Never the Job Chat, and never an HMAC.
 */
@RestController
@RequestMapping("/api/v1/admin/split-resolutions/{resolutionId}/evidence")
@Access(Gate.ADMINISTRATOR)
class AdminSplitEvidenceController {

	/**
	 * A party with their active interval.
	 * @param name absent once an erasure anonymized them
	 * @param activeFrom when they became active; absent on a split older than Story 22.3 whose row had gone
	 */
	record SplitEvidencePartyView(UUID partyId, @Nullable String name, boolean mentor, boolean wholeInterval,
			boolean activeAtRelease, @Nullable Instant activeFrom, @Nullable Instant activeTo) {
	}

	/** One party's line of a version, with when they confirmed it. */
	record SplitEvidenceLineView(UUID partyId, int percent, @Nullable Instant confirmedAt) {
	}

	/**
	 * One version.
	 * @param kind {@code DRAFT}, {@code DEFAULT}, {@code DECISION} or {@code MENTOR_ONLY}
	 * @param authorName who saved it; absent for the System and once an erasure anonymized them
	 * @param capPercent the cap it was checked against
	 */
	record SplitEvidenceVersionView(int versionNo, MilestoneSplits.VersionKind kind, @Nullable String authorName,
			@Nullable Integer capPercent, Instant createdAt, List<SplitEvidenceLineView> lines) {
	}

	/** "Các bản phân chia": every version oldest first, and each party's active interval. */
	record SplitEvidenceVersionsView(List<SplitEvidencePartyView> parties, List<SplitEvidenceVersionView> versions) {
	}

	/** One entry of a Work Item's history. */
	record SplitEvidenceHistoryView(String action, @Nullable String actorName, @Nullable String comment, Instant at) {
	}

	/** One Work Item with its history. */
	record SplitEvidenceWorkItemView(UUID workItemId, String key, String title, String status, @Nullable String assigneeName,
			List<SplitEvidenceHistoryView> history) {
	}

	/**
	 * One commit.
	 * @param authorGithubId its author's GitHub numeric ID; absent when GitHub resolved no account
	 * @param partyId the party with that GitHub ID, if one has it
	 * @param partyName that party's name
	 */
	record SplitEvidenceCommitView(UUID handoverId, int round, String sha, String message, @Nullable Long authorGithubId,
			@Nullable UUID partyId, @Nullable String partyName) {
	}

	/** One Crew Chat message. */
	record SplitEvidenceMessageView(UUID messageId, @Nullable String author, String body, @Nullable String attachmentName,
			Instant sentAt) {
	}

	/**
	 * "Trao đổi nhóm": the Crew Chat of the Milestone's interval, which N-31 promises.
	 * @param from the Milestone's funding; absent on a split older than Story 22.3 whose Milestone had gone
	 * @param to the Milestone's release
	 */
	record SplitEvidenceChatView(@Nullable Instant from, Instant to, List<SplitEvidenceMessageView> messages) {
	}

	/**
	 * Two accounts that may be one person: never the signal.
	 * @param kind {@code EMAIL} or {@code LINKEDIN}
	 * @param firstCustomer the first is the Job's Customer
	 * @param secondCustomer the second is the Job's Customer
	 */
	record SplitEvidencePairView(@Nullable String firstName, @Nullable String secondName, String kind, boolean firstCustomer,
			boolean secondCustomer) {
	}

	private final SplitEvidencePackage evidence;

	private final MilestoneSplits splits;

	private final WorkEvidence work;

	private final DeliveryEvidence deliveries;

	private final ChatEvidence chat;

	private final PersonSignals signals;

	private final SplitResolutionPeople people;

	private final CustomerNames customers;

	private final AccountAdministration accounts;

	private final CurrentCaller callers;

	AdminSplitEvidenceController(SplitEvidencePackage evidence, MilestoneSplits splits, WorkEvidence work,
			DeliveryEvidence deliveries, ChatEvidence chat, PersonSignals signals, SplitResolutionPeople people,
			CustomerNames customers, AccountAdministration accounts, CurrentCaller callers) {
		this.accounts = accounts;
		this.evidence = evidence;
		this.splits = splits;
		this.work = work;
		this.deliveries = deliveries;
		this.chat = chat;
		this.signals = signals;
		this.people = people;
		this.customers = customers;
		this.callers = callers;
	}

	/** "Các bản phân chia". */
	@GetMapping("/versions")
	SplitEvidenceVersionsView versions(@PathVariable UUID resolutionId) {
		UUID me = this.callers.get().requireAccountId();
		SplitCase split = view(resolutionId, me, Tab.VERSIONS);
		List<MilestoneSplits.EvidenceVersion> versions = this.splits.evidenceUnderGrant(me, split.splitId());
		List<UUID> authors = versions.stream().map(MilestoneSplits.EvidenceVersion::authorId).filter(Objects::nonNull).toList();
		Map<UUID, String> names = names(split, authors);
		return new SplitEvidenceVersionsView(split.parties()
			.stream()
			.map(party -> new SplitEvidencePartyView(party.partyId(), nameOf(party.accountId(), names), party.mentor(),
					party.wholeInterval(), party.activeAtRelease(), party.activeFrom(), party.activeTo()))
			.toList(),
				versions.stream()
					.map(version -> new SplitEvidenceVersionView(version.versionNo(), version.kind(),
							nameOf(version.authorId(), names), version.capPercent(), version.createdAt(), version.lines()
								.stream()
								.map(line -> new SplitEvidenceLineView(line.partyId(), line.percent(), line.confirmedAt()))
								.toList()))
					.toList());
	}

	/** "Hạng mục": the Milestone's Work Items the grant covers, each party's, with their histories. */
	@GetMapping("/work-items")
	List<SplitEvidenceWorkItemView> workItems(@PathVariable UUID resolutionId) {
		UUID me = this.callers.get().requireAccountId();
		SplitCase split = view(resolutionId, me, Tab.WORK_ITEMS);
		List<WorkEvidence.PartyItem> items = this.work.milestoneItemsUnderGrant(me, split.jobId(), split.milestoneId());
		List<UUID> mentioned = new ArrayList<>();
		items.forEach(item -> {
			if (item.assigneeId() != null) {
				mentioned.add(item.assigneeId());
			}
			item.history().stream().map(WorkEvidence.HistoryLine::actorId).filter(Objects::nonNull).forEach(mentioned::add);
		});
		Map<UUID, String> names = names(split, mentioned);
		return items.stream()
			.map(item -> new SplitEvidenceWorkItemView(item.workItemId(), item.key(), item.title(), item.status(),
					nameOf(item.assigneeId(), names), item.history()
						.stream()
						.map(line -> new SplitEvidenceHistoryView(line.action(), nameOf(line.actorId(), names), line.comment(), line.at()))
						.toList()))
			.toList();
	}

	/** "Commit": the Handover Commits' authors by GitHub numeric ID, matched to the parties (AD-33). */
	@GetMapping("/commits")
	List<SplitEvidenceCommitView> commits(@PathVariable UUID resolutionId) {
		SplitCase split = view(resolutionId, this.callers.get().requireAccountId(), Tab.COMMITS);
		List<UUID> accounts = SplitResolutionDecisions.partyAccounts(split);
		Map<Long, UUID> byGithub = this.people.byGithubId(accounts);
		Map<UUID, String> names = this.people.names(accounts, split.mentorId());
		return this.deliveries.commitsOf(split.milestoneId()).stream().map(commit -> {
			UUID account = commit.authorGithubId() == null ? null : byGithub.get(commit.authorGithubId());
			CaseParty party = account == null ? null
					: split.parties().stream().filter(candidate -> account.equals(candidate.accountId())).findFirst().orElse(null);
			return new SplitEvidenceCommitView(commit.handoverId(), commit.round(), commit.sha(), commit.message(),
					commit.authorGithubId(), party == null ? null : party.partyId(), nameOf(account, names));
		}).toList();
	}

	/** "Trao đổi nhóm": the grant covers the Crew Chat, and the tab shows the Milestone's interval, so N-31 stays true. */
	@GetMapping("/crew-chat")
	SplitEvidenceChatView crewChat(@PathVariable UUID resolutionId) {
		UUID me = this.callers.get().requireAccountId();
		SplitCase split = view(resolutionId, me, Tab.CREW_CHAT);
		List<ChatEvidence.CrewMessage> messages = this.chat.crewChatUnderGrant(me, split.jobId(), split.fundedAt(), split.releasedAt());
		Map<UUID, String> names = names(split, messages.stream().map(ChatEvidence.CrewMessage::authorId).toList());
		return new SplitEvidenceChatView(split.fundedAt(), split.releasedAt(), messages.stream()
			.map(message -> new SplitEvidenceMessageView(message.messageId(), nameOf(message.authorId(), names), message.body(),
					message.attachmentName(), message.sentAt()))
			.toList());
	}

	/** "Tài khoản có thể cùng một người": pairs by kind among the parties and with the Customer, never an HMAC (AD-19). */
	@GetMapping("/person-links")
	List<SplitEvidencePairView> personLinks(@PathVariable UUID resolutionId) {
		SplitCase split = view(resolutionId, this.callers.get().requireAccountId(), Tab.PERSON_LINKS);
		List<UUID> accounts = SplitResolutionDecisions.partyAccounts(split);
		Map<UUID, String> names = this.people.names(accounts, split.mentorId());
		UUID customer = split.customerId();
		return this.signals.matchesAmong(SplitEvidenceSnapshots.involved(split, accounts))
			.stream()
			.map(match -> new SplitEvidencePairView(personName(match.accountId(), customer, names),
					personName(match.otherAccountId(), customer, names), match.kind(), match.accountId().equals(customer),
					match.otherAccountId().equals(customer)))
			.toList();
	}

	private @Nullable String personName(UUID account, @Nullable UUID customer, Map<UUID, String> names) {
		return account.equals(customer) ? this.customers.displayName(account) : names.get(account);
	}

	private SplitCase view(UUID resolutionId, UUID me, Tab tab) {
		Resolution resolution = this.evidence.view(resolutionId, me, tab);
		return this.splits.caseOf(resolution.splitId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The names of the people a tab mentions, the parties always among them, and the Administrator of a decision. */
	private Map<UUID, String> names(SplitCase split, List<UUID> mentioned) {
		Set<UUID> accounts = new LinkedHashSet<>(SplitResolutionDecisions.partyAccounts(split));
		accounts.addAll(mentioned);
		Map<UUID, String> names = new HashMap<>(this.accounts.names(accounts));
		names.putAll(this.people.names(accounts, split.mentorId()));
		return names;
	}

	private static @Nullable String nameOf(@Nullable UUID account, Map<UUID, String> names) {
		return account == null ? null : names.get(account);
	}

}
