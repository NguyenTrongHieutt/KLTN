package vn.vibefix.work.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.MilestoneStates;
import vn.vibefix.job.MilestoneStates.MilestoneDetail;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.work.CriteriaDone;
import vn.vibefix.work.CriteriaDone.Progress;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkAccess.Kind;

/**
 * JOB-1's progress (FR-28, AD-33, AD-39, UX-DR68): per Milestone of the Agreement in force its live
 * state, price, due date, and {@code {done}/{total}} from {@code work.criteriaDone} only; the
 * customer-visible Pages; for the Mentor the Crew with each member's open Work Items, and for a Crew
 * member their own; for the Mentor and the Crew of an Assessment Job, the public Source Repository they
 * read (FR-10). The Job's Customer, its Mentor, and its active Crew read it; the Customer's answer holds
 * no Work Item and no repository link. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class ProgressController {

	/**
	 * One Milestone's progress.
	 * @param state the live Milestone state name
	 * @param done criteria of the Agreement in force whose seeded Story the Mentor approved
	 * @param inReview Work Items of the Milestone in "Chờ duyệt", for the Mentor; 0 for anyone else
	 * @param roundsLeft the Revision Rounds left (FR-34)
	 * @param revisionCriteria how many criteria the latest revision request named
	 * @param autoAccepted accepted by Auto-acceptance (FR-35)
	 */
	record MilestoneProgressView(UUID milestoneId, int number, String title, String state, @Nullable Long priceVnd,
			@Nullable LocalDate dueOn, int done, int total, int inReview, int roundsLeft, @Nullable Integer revisionCriteria,
			boolean autoAccepted) {
	}

	record OpenItemView(UUID id, String key, String title, String status) {
	}

	record CrewProgressView(UUID accountId, String name, List<OpenItemView> openItems) {
	}

	record PageLinkView(UUID id, String title) {
	}

	/**
	 * The progress of a Job with a Board.
	 * @param audience {@code CUSTOMER}, {@code MENTOR}, or {@code CREW}
	 * @param crew the active Crew with their open Work Items, for the Mentor only
	 * @param mine the viewer's own open Work Items, for a Crew member only
	 * @param assessment an Assessment Job (FR-10), for the Mentor and the Crew; {@code false} for the Customer
	 * @param sourceRepositoryUrl the public Source Repository of an Assessment Job, for the Mentor and the Crew
	 */
	record JobProgressView(UUID boardId, String title, String state, String productName, String mentorName,
			String audience, List<MilestoneProgressView> milestones, List<PageLinkView> pages,
			List<CrewProgressView> crew, List<OpenItemView> mine, boolean assessment, @Nullable String sourceRepositoryUrl) {
	}

	private final WorkAccess access;

	private final Boards boards;

	private final AgreementCriteria criteria;

	private final MilestoneStates states;

	private final CriteriaDone criteriaDone;

	private final Crews crews;

	private final ProductDirectory products;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	ProgressController(WorkAccess access, Boards boards, AgreementCriteria criteria, MilestoneStates states,
			CriteriaDone criteriaDone, Crews crews, ProductDirectory products, MentorDirectory mentors,
			StudentDirectory students, JdbcClient jdbc, CurrentCaller callers) {
		this.access = access;
		this.boards = boards;
		this.criteria = criteria;
		this.states = states;
		this.criteriaDone = criteriaDone;
		this.crews = crews;
		this.products = products;
		this.mentors = mentors;
		this.students = students;
		this.jdbc = jdbc;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/progress")
	JobProgressView progress(@PathVariable UUID boardId) {
		BoardViewer viewer = this.access.pageReader(boardId, this.callers.get());
		if (viewer.kind() != Kind.CUSTOMER && viewer.kind() != Kind.MENTOR && viewer.kind() != Kind.CREW) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID jobId = viewer.board().jobId();
		Map<UUID, MilestoneDetail> live = this.states.detailsOf(jobId);
		List<MilestoneProgressView> milestones = new ArrayList<>();
		for (MilestoneInForce milestone : this.criteria.inForce(jobId).map(found -> found.milestones()).orElse(List.of())) {
			Progress progress = this.criteriaDone.criteriaDone(jobId, milestone.milestoneId());
			int inReview = viewer.mentor() ? inReview(jobId, milestone.milestoneId()) : 0;
			MilestoneDetail detail = live.get(milestone.milestoneId());
			milestones.add(new MilestoneProgressView(milestone.milestoneId(), milestone.number(), milestone.title(),
					detail == null ? "PLANNED" : detail.state(), milestone.priceVnd(), milestone.dueOn(), progress.done(),
					progress.total(), inReview, detail == null ? 0 : detail.roundsLeft(),
					detail == null ? null : detail.revisionCriteria(), detail != null && detail.autoAccepted()));
		}
		List<PageLinkView> pages = this.jdbc.sql("""
				SELECT id, title FROM work.page WHERE job_id = :job AND visibility = 'CUSTOMER' ORDER BY seq
				""").param("job", jobId)
			.query((rs, n) -> new PageLinkView(rs.getObject("id", UUID.class), rs.getString("title")))
			.list();
		List<CrewProgressView> crew = new ArrayList<>();
		if (viewer.mentor()) {
			List<UUID> members = this.crews.crewOf(jobId, Selection.ACTIVE).stream().map(CrewMember::studentId).toList();
			if (!members.isEmpty()) {
				for (StudentCard card : this.students.cards(members)) {
					crew.add(new CrewProgressView(card.accountId(),
							card.name() != null ? card.name() : "@" + card.githubLogin(), openItems(viewer, card.accountId())));
				}
			}
		}
		List<OpenItemView> mine = viewer.kind() == Kind.CREW ? openItems(viewer, viewer.accountId()) : List.of();
		ProductInfo info = this.products.find(viewer.board().productId()).orElse(null);
		String mentor = this.mentors.cards(List.of(viewer.mentorId())).stream().findFirst().map(MentorCard::displayName)
			.orElse("");
		boolean assessment = viewer.kind() != Kind.CUSTOMER && viewer.assessment();
		String repository = assessment && info != null ? "https://github.com/" + info.repositoryFullName() : null;
		return new JobProgressView(boardId, viewer.title(), viewer.jobState(), info == null ? "" : info.name(), mentor,
				viewer.kind().name(), milestones, pages, crew, mine, assessment, repository);
	}

	private int inReview(UUID jobId, UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM work.work_item WHERE job_id = :job AND milestone_id = :milestone AND status = 'IN_REVIEW'
				""").param("job", jobId).param("milestone", milestoneId).query(Long.class).single().intValue();
	}

	private List<OpenItemView> openItems(BoardViewer viewer, UUID assignee) {
		return this.boards.items(viewer.board())
			.stream()
			.filter(item -> assignee.equals(item.assigneeId()) && item.status() != Boards.Status.DONE)
			.map(item -> new OpenItemView(item.id(), item.key().toString(), item.title(), item.status().name()))
			.toList();
	}

}
