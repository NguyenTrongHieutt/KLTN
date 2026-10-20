package vn.vibefix.work.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriteriaInForce;
import vn.vibefix.job.AgreementCriteria.CriterionInForce;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;

/**
 * JOB-2 "Bảng công việc" (FR-22, FR-23, AD-33, UX-DR36): the Board of an Active Job, keyed by the Job,
 * for its Mentor and its active Crew. Each Work Item carries its key; a seeded Story names the
 * criterion it came from.
 */
@RestController
@Access(Gate.SIGNED_IN)
class BoardController {

	record BoardMilestoneView(UUID milestoneId, int number, String title) {
	}

	/**
	 * The criterion a seeded Story came from.
	 * @param milestoneNumber its Milestone's number
	 * @param number its number within the Milestone
	 */
	record CriterionLinkView(UUID criterionId, int milestoneNumber, int number) {
	}

	/**
	 * One Work Item on the Board.
	 * @param key {@code {JOBCODE}-{n}}, for commit messages
	 * @param seeded seeded from a criterion: the criterion link, type, and Milestone are read-only
	 */
	record WorkItemCardView(UUID id, String key, String type, String title, String description, String status,
			@Nullable UUID milestoneId, @Nullable CriterionLinkView criterion, boolean seeded, @Nullable UUID assigneeId,
			long version) {
	}

	/**
	 * The Board: its Job code, the Milestones in force, and the Work Items in key order.
	 * @param title the Job's title
	 * @param mentor whether the viewer is the Job's Mentor
	 */
	record BoardView(UUID boardId, String title, String code, boolean mentor, List<BoardMilestoneView> milestones,
			List<WorkItemCardView> items) {
	}

	private final Boards boards;

	private final WorkAccess access;

	private final AgreementCriteria criteria;

	private final CurrentCaller callers;

	BoardController(Boards boards, WorkAccess access, AgreementCriteria criteria, CurrentCaller callers) {
		this.boards = boards;
		this.access = access;
		this.criteria = criteria;
		this.callers = callers;
	}

	/** The Board of the Job {@code boardId} (a Board's ID is its Job's). */
	@GetMapping("/api/v1/boards/{boardId}/work-items")
	BoardView workItems(@PathVariable UUID boardId) {
		BoardViewer viewer = this.access.reader(boardId, this.callers.get());
		CriteriaInForce inForce = this.criteria.inForce(viewer.board().jobId()).orElse(null);
		Map<UUID, CriterionInForce> criteria = new HashMap<>();
		List<BoardMilestoneView> milestones = List.of();
		if (inForce != null) {
			inForce.criteria().forEach(criterion -> criteria.put(criterion.criterionId(), criterion));
			milestones = inForce.milestones()
				.stream()
				.map(milestone -> new BoardMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title()))
				.toList();
		}
		List<WorkItemCardView> items = this.boards.items(viewer.board()).stream().map(item -> view(item, criteria)).toList();
		return new BoardView(viewer.board().jobId(), viewer.title(), viewer.board().code(), viewer.mentor(), milestones, items);
	}

	private static WorkItemCardView view(Item item, Map<UUID, CriterionInForce> criteria) {
		CriterionInForce criterion = item.criterionId() == null ? null : criteria.get(item.criterionId());
		return new WorkItemCardView(item.id(), item.key().toString(), item.type().name(), item.title(), item.description(),
				item.status().name(), item.milestoneId(),
				criterion == null ? null
						: new CriterionLinkView(criterion.criterionId(), criterion.milestoneNumber(), criterion.number()),
				item.seeded(), item.assigneeId(), item.version());
	}

}
