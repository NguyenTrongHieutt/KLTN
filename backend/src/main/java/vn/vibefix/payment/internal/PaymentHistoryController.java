package vn.vibefix.payment.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitState;
import vn.vibefix.job.MilestoneSplits.Status;
import vn.vibefix.payment.internal.PaymentHistory.Balance;
import vn.vibefix.payment.internal.PaymentHistory.Figures;
import vn.vibefix.payment.internal.PaymentHistory.LineRole;
import vn.vibefix.payment.internal.PaymentHistory.Pool;
import vn.vibefix.payment.internal.PaymentHistory.Release;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * CUS-10, MEN-7, and the Mentor's JOB-7 (FR-44, AD-15, AD-20). The Customer's DTOs carry fundings, refunds, and one
 * released total per Milestone, never a party's part, a pool, or a split; the Assignee DTOs carry the parts, and only a
 * split's Mentor reads every party's. MEN-7 holds only the caller's own lines, each with the role held in its split, and
 * the pool of their own splits still pending. The payment history and the Balance are open to any unlocked user and take
 * no ID (Story 20.3); an Administrator finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class PaymentHistoryController {

	record HistoryRowView(Instant at, String kind, int number, long amountVnd, String status, boolean simulated,
			UUID paymentOrderId) {
	}

	record HistoryMilestoneView(UUID milestoneId, int number, String title, String state, long releasedTotalVnd) {
	}

	record HistoryJobView(UUID jobId, String title, String state, List<HistoryRowView> rows, List<HistoryMilestoneView> milestones) {
	}

	/**
	 * CUS-10.
	 * @param heldVnd what vibefix holds for the Customer's Milestones ({@code releasable})
	 * @param releasedVnd what reached the Mentor and the Crew ({@code releasedTotal})
	 * @param refundedVnd confirmed refunds
	 */
	record CustomerHistoryView(long heldVnd, long releasedVnd, long refundedVnd, List<HistoryJobView> jobs) {
	}

	/**
	 * Another party's part, for the split's Mentor.
	 * @param name blank when nothing names the person any more
	 * @param erased an erasure anonymized the person: "Người đã xóa tài khoản"
	 */
	record CrewPartView(String name, boolean erased, long amountVnd, @Nullable Integer percent) {
	}

	/**
	 * One line of MEN-7: the caller's part of a Milestone, or a split of theirs still pending.
	 * @param jobTitle empty once the Job is gone: "Công việc đã xóa"
	 * @param jobLive the Job still has its screens; a pending line's JOB-10 stays readable either way
	 * @param role the role held in the split, or on the Job for a release with no split; absent for such a release on a
	 * Job that is gone
	 * @param status the split's, while one exists
	 * @param finality how the split became final
	 * @param finalAt when the split became final
	 * @param pending the pool still waits for the final split's payout: "Chờ phân chia", never in the Balance
	 * @param baseAmountVnd the Milestone's release, which is the pooled amount while pending
	 * @param yourPartVnd 0 while pending and for a zero part
	 * @param crew the other parties' parts, on a line whose split names the caller its Mentor
	 */
	record BalanceReleaseView(UUID jobId, String jobTitle, boolean jobLive, UUID milestoneId, int number, String milestoneTitle,
			@Nullable LineRole role, @Nullable Status status, @Nullable Finality finality, @Nullable Instant finalAt,
			boolean pending, Instant releasedAt, long baseAmountVnd, long yourPartVnd, @Nullable Integer yourPercent,
			@Nullable Integer splitVersionNo, List<CrewPartView> crew) {
	}

	/**
	 * MEN-7.
	 * @param balanceVnd the sum of the caller's RELEASE entries
	 * @param releases how many there are
	 * @param sentToCrewVnd and {@code heldVnd}: for a caller who is the Mentor on a line or of a Job
	 */
	record BalanceView(long balanceVnd, int releases, @Nullable Long sentToCrewVnd, @Nullable Long heldVnd,
			List<BalanceReleaseView> lines) {
	}

	/**
	 * One party's part of a Milestone's final split.
	 * @param name blank when nothing names the person any more
	 * @param erased an erasure anonymized the person: "Người đã xóa tài khoản"
	 */
	record MentorReleaseView(String name, boolean erased, String role, long amountVnd, @Nullable Integer percent,
			@Nullable Integer splitVersionNo, Instant releasedAt) {
	}

	/**
	 * One Milestone of the Mentor's JOB-7.
	 * @param releasedTotalVnd counts the pool, so the page shows it only once the split paid out
	 * @param splitStatus the Milestone's split, once a release made one
	 * @param finality how it became final
	 * @param finalAt when it became final
	 * @param pooledVnd the Milestone's pool, once a release made one: "Chờ phân chia" until {@code paid}
	 * @param paid the final split reversed the pool into the parts
	 */
	record MentorFundingMilestoneView(UUID milestoneId, int number, String title, long priceVnd, String state, long fundedVnd,
			long topUpVnd, long refundedVnd, long releasedTotalVnd, List<MentorReleaseView> releases, @Nullable Status splitStatus,
			@Nullable Finality finality, @Nullable Instant finalAt, @Nullable Long pooledVnd, boolean paid) {
	}

	record MentorOrderView(int number, long amountVnd, String status, Instant createdAt) {
	}

	/** The Mentor's JOB-7, an Assignee DTO apart from the Customer's (AD-20). */
	record MentorJobFundingView(UUID jobId, String title, String state, List<MentorFundingMilestoneView> milestones,
			List<MentorOrderView> orders) {
	}

	private final PaymentHistory history;

	private final PaymentOrders orders;

	private final Refunds refunds;

	private final MilestoneFunding funding;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	private final JobRoles roles;

	private final MilestoneSplits splits;

	PaymentHistoryController(PaymentHistory history, PaymentOrders orders, Refunds refunds, MilestoneFunding funding,
			ActiveAccount gate, CurrentCaller callers, JobRoles roles, MilestoneSplits splits) {
		this.roles = roles;
		this.splits = splits;
		this.history = history;
		this.orders = orders;
		this.refunds = refunds;
		this.funding = funding;
		this.gate = gate;
		this.callers = callers;
	}

	@GetMapping("/api/v1/payments/history")
	CustomerHistoryView paymentHistory() {
		UUID customer = user();
		long held = 0;
		long released = 0;
		List<HistoryJobView> jobs = new ArrayList<>();
		for (FundingJob job : this.history.customerJobs(customer)) {
			Map<UUID, Integer> numbers = new java.util.HashMap<>();
			List<HistoryMilestoneView> milestones = new ArrayList<>();
			for (FundingMilestone milestone : job.milestones()) {
				numbers.put(milestone.milestoneId(), milestone.number());
				Figures figures = this.history.figures(milestone.milestoneId());
				held += figures.releasableVnd();
				released += figures.releasedTotalVnd();
				milestones.add(new HistoryMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title(),
						milestone.state(), figures.releasedTotalVnd()));
			}
			List<HistoryRowView> rows = new ArrayList<>();
			for (PaymentOrders.Order order : this.orders.ofJob(job.jobId())) {
				int number = numbers.getOrDefault(order.milestoneId(), 0);
				rows.add(new HistoryRowView(order.createdAt(), "FUNDING", number, order.amountVnd(), order.status(), false,
						order.id()));
				this.refunds.ofOrder(order.id())
					.ifPresent(refund -> rows.add(new HistoryRowView(refund.requestedAt(), "REFUND", number, refund.amountVnd(),
							refund.status(), refund.simulated(), order.id())));
			}
			rows.sort(Comparator.comparing(HistoryRowView::at).reversed());
			jobs.add(new HistoryJobView(job.jobId(), job.title(), job.state(), rows, milestones));
		}
		return new CustomerHistoryView(held, released, this.history.refundedTo(customer), jobs);
	}

	@GetMapping("/api/v1/balance")
	BalanceView balance() {
		Balance balance = this.history.balance(user());
		return new BalanceView(balance.balanceVnd(), balance.releases(), balance.sentToCrewVnd(), balance.heldVnd(),
				balance.lines()
					.stream()
					.map(line -> new BalanceReleaseView(line.jobId(), line.jobTitle(), line.jobLive(), line.milestoneId(),
							line.number(), line.milestoneTitle(), line.role(), line.status(), line.finality(), line.finalAt(),
							line.pending(), line.releasedAt(), line.baseAmountVnd(), line.yourPartVnd(), line.yourPercent(),
							line.splitVersionNo(),
							line.crew()
								.stream()
								.map(part -> new CrewPartView(part.name(), part.erased(), part.amountVnd(), part.percent()))
								.toList()))
					.toList());
	}

	/**
	 * The Mentor's JOB-7: each Milestone with its split's status and pool, read after the role check, and each party's part
	 * once the split paid out. An erased party is one the split's party rows no longer name.
	 */
	@GetMapping("/api/v1/mentor/jobs/{jobId}/funding")
	MentorJobFundingView mentorJobFunding(@PathVariable UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isMentor()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		FundingJob job = this.funding.byId(jobId)
			.filter(found -> mentor.equals(found.mentorId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Map<UUID, Integer> numbers = new java.util.HashMap<>();
		Map<UUID, Pool> pools = this.history.pools(job.milestones().stream().map(FundingMilestone::milestoneId).toList());
		List<MentorFundingMilestoneView> milestones = new ArrayList<>();
		for (FundingMilestone milestone : job.milestones()) {
			numbers.put(milestone.milestoneId(), milestone.number());
			Figures figures = this.history.figures(milestone.milestoneId());
			SplitState split = this.splits.splitOf(milestone.milestoneId()).orElse(null);
			Pool pool = pools.get(milestone.milestoneId());
			List<Release> releases = this.history.releaseEntries(milestone.milestoneId());
			Map<UUID, String> names = this.history.names(releases.stream().map(Release::partyId).distinct().toList(),
					mentor);
			Set<UUID> named = split == null || releases.isEmpty() ? Set.of() : this.history.partiesNamed(milestone.milestoneId());
			milestones.add(new MentorFundingMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title(),
					milestone.priceVnd(), milestone.state(), figures.fundedVnd(), figures.topUpVnd(), figures.refundedVnd(),
					figures.releasedTotalVnd(),
					releases.stream()
						.map(release -> new MentorReleaseView(names.getOrDefault(release.partyId(), ""),
								split != null && !named.contains(release.partyId()),
								release.partyId().equals(mentor) ? "MENTOR" : "CREW", release.amountVnd(), release.percent(),
								release.splitVersionNo(), release.releasedAt()))
						.toList(),
					split == null ? null : split.status(), split == null ? null : split.finality(),
					split == null ? null : split.finalSince(), pool == null ? null : pool.amountVnd(),
					pool != null && pool.reversed()));
		}
		List<MentorOrderView> orders = this.orders.ofJob(jobId)
			.stream()
			.map(order -> new MentorOrderView(numbers.getOrDefault(order.milestoneId(), 0), order.amountVnd(), order.status(),
					order.createdAt()))
			.toList();
		return new MentorJobFundingView(job.jobId(), job.title(), job.state(), milestones, orders);
	}

	/**
	 * The user calling the global Balances and payment-history pages, unlocked (AD-19, read on every request); the
	 * pages hold only their own entries. An Administrator finds nothing.
	 */
	private UUID user() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID account = caller.requireAccountId();
		this.gate.requireActiveAccount(account);
		return account;
	}

}
