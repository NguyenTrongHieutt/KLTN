package vn.vibefix.payment.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorGate;
import vn.vibefix.job.Crews;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.payment.internal.PaymentHistory.Balance;
import vn.vibefix.payment.internal.PaymentHistory.Figures;
import vn.vibefix.payment.internal.PaymentHistory.Release;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * CUS-10, MEN-7 and STU-7, and the Mentor's JOB-7 (FR-44, AD-15, AD-20). The Customer's DTOs carry
 * fundings, refunds, and one released total per Milestone, never a party's part; the Assignee DTOs
 * carry the parts, and only the Job's Mentor reads every party's.
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

	record CrewPartView(String name, long amountVnd, @Nullable Integer percent) {
	}

	record BalanceReleaseView(UUID jobId, String jobTitle, int number, String milestoneTitle, Instant releasedAt,
			long baseAmountVnd, long yourPartVnd, @Nullable Integer yourPercent, @Nullable Integer crewTermsVersionNo,
			List<CrewPartView> crew) {
	}

	/** One of the reader's own parts held for an open share Team Issue: "Đang giữ" (AD-40). */
	record HeldPartView(UUID jobId, String jobTitle, int number, String milestoneTitle, long amountVnd, Instant heldAt) {
	}

	/**
	 * MEN-7 or STU-7.
	 * @param sentToCrewVnd and {@code heldVnd}: the Mentor's only
	 * @param heldParts the reader's own held parts, which the Balance never counts
	 */
	record BalanceView(long balanceVnd, int releases, @Nullable Long sentToCrewVnd, @Nullable Long heldVnd,
			List<BalanceReleaseView> lines, List<HeldPartView> heldParts) {
	}

	record MentorReleaseView(String name, String role, long amountVnd, @Nullable Integer percent,
			@Nullable Integer crewTermsVersionNo, Instant releasedAt) {
	}

	record MentorFundingMilestoneView(UUID milestoneId, int number, String title, long priceVnd, String state, long fundedVnd,
			long topUpVnd, long refundedVnd, long releasedTotalVnd, @Nullable Integer crewTermsVersionNo,
			List<MentorReleaseView> releases) {
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

	private final Crews crews;

	private final MentorGate gate;

	private final CurrentCaller callers;

	PaymentHistoryController(PaymentHistory history, PaymentOrders orders, Refunds refunds, MilestoneFunding funding,
			Crews crews, MentorGate gate, CurrentCaller callers) {
		this.history = history;
		this.orders = orders;
		this.refunds = refunds;
		this.funding = funding;
		this.crews = crews;
		this.gate = gate;
		this.callers = callers;
	}

	@GetMapping("/api/v1/payments/history")
	CustomerHistoryView paymentHistory() {
		UUID customer = role(Role.CUSTOMER);
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
		Caller caller = this.callers.get();
		boolean mentor = caller.hasRole(Role.MENTOR);
		if (!mentor && !caller.hasRole(Role.STUDENT)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Balance balance = this.history.balance(caller.requireAccountId(), mentor);
		return new BalanceView(balance.balanceVnd(), balance.releases(), balance.sentToCrewVnd(), balance.heldVnd(),
				balance.lines()
					.stream()
					.map(line -> new BalanceReleaseView(line.jobId(), line.jobTitle(), line.number(), line.milestoneTitle(),
							line.releasedAt(), line.baseAmountVnd(), line.yourPartVnd(), line.yourPercent(), line.crewTermsVersionNo(),
							line.crew().stream().map(part -> new CrewPartView(part.name(), part.amountVnd(), part.percent())).toList()))
					.toList(),
				balance.heldParts()
					.stream()
					.map(part -> new HeldPartView(part.jobId(), part.jobTitle(), part.number(), part.milestoneTitle(),
							part.amountVnd(), part.heldAt()))
					.toList());
	}

	@GetMapping("/api/v1/mentor/jobs/{jobId}/funding")
	MentorJobFundingView mentorJobFunding(@PathVariable UUID jobId) {
		UUID mentor = role(Role.MENTOR);
		this.gate.requireActiveAccount(mentor);
		FundingJob job = this.funding.byId(jobId)
			.filter(found -> mentor.equals(found.mentorId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Map<UUID, Integer> numbers = new java.util.HashMap<>();
		List<MentorFundingMilestoneView> milestones = new ArrayList<>();
		for (FundingMilestone milestone : job.milestones()) {
			numbers.put(milestone.milestoneId(), milestone.number());
			Figures figures = this.history.figures(milestone.milestoneId());
			List<Release> releases = this.history.releaseEntries(milestone.milestoneId());
			Map<UUID, String> names = this.history.names(releases.stream().map(Release::partyId).distinct().toList());
			milestones.add(new MentorFundingMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title(),
					milestone.priceVnd(), milestone.state(), figures.fundedVnd(), figures.topUpVnd(), figures.refundedVnd(),
					figures.releasedTotalVnd(),
					this.crews.releaseTermsOf(milestone.milestoneId()).map(Crews.ReleaseTerms::versionNo).orElse(null),
					releases.stream()
						.map(release -> new MentorReleaseView(names.getOrDefault(release.partyId(), ""),
								release.partyId().equals(mentor) ? "MENTOR" : "CREW", release.amountVnd(), release.percent(),
								release.crewTermsVersionNo(), release.releasedAt()))
						.toList()));
		}
		List<MentorOrderView> orders = this.orders.ofJob(jobId)
			.stream()
			.map(order -> new MentorOrderView(numbers.getOrDefault(order.milestoneId(), 0), order.amountVnd(), order.status(),
					order.createdAt()))
			.toList();
		return new MentorJobFundingView(job.jobId(), job.title(), job.state(), milestones, orders);
	}

	/** The caller in {@code role}; anyone else finds nothing. */
	private UUID role(Role role) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(role)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
