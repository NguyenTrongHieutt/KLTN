package vn.vibefix.work.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriteriaInForce;
import vn.vibefix.job.AgreementCriteria.CriterionInForce;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.HeldMilestones;
import vn.vibefix.work.internal.Attachments.Attachment;
import vn.vibefix.work.internal.Boards.ChecklistLine;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkReads.Entry;

/**
 * The Board's JSON as JOB-2 shows it (FR-23, FR-76, UX-DR36, UX-DR79): cards with key, type, priority,
 * estimate, and assignee; the detail with its checklist, Attachments, and one history of changes, reviews,
 * notes, and Attachment events. People are named as the viewer may see them:
 * the Mentor by their directory name, a Crew member by their shown name or {@code @login} (FR-51).
 */
@Component
class WorkViews {

	/** @param held a Held Milestone: its Work Items may leave "Cần làm" (FR-24) */
	record BoardMilestoneView(UUID milestoneId, int number, String title, boolean held) {
	}

	/**
	 * The criterion a seeded Story came from.
	 * @param milestoneNumber its Milestone's number
	 * @param number its number within the Milestone
	 */
	record CriterionLinkView(UUID criterionId, int milestoneNumber, int number) {
	}

	record PersonView(UUID accountId, String name) {
	}

	/**
	 * A Crew member in the assignee picker (FR-23, AD-41).
	 * @param available an {@code ACTIVE} row of an unlocked account, whom the Mentor may assign; an
	 * {@code INACTIVE} one is listed disabled
	 */
	record CrewPickView(UUID accountId, String name, boolean available) {
	}

	/**
	 * One active Crew member on the workload line (FR-26, UX-DR37).
	 * @param openItems their Work Items that are not Done
	 * @param estimateHours the sum of those Work Items' estimates
	 */
	record WorkloadView(UUID accountId, String name, int openItems, BigDecimal estimateHours) {
	}

	record ChecklistLineView(String text, boolean done) {
	}

	/**
	 * One Work Item on the Board.
	 * @param key {@code {JOBCODE}-{n}}, for commit messages
	 * @param seeded seeded from a criterion: the criterion link, type, and Milestone are read-only
	 * @param checklistDone how many checklist lines are done
	 * @param returnedReason the Mentor's reason while the latest review entry is a return ("Bị trả lại")
	 */
	record WorkItemCardView(UUID id, String key, String type, String title, String description, String status,
			@Nullable UUID milestoneId, @Nullable CriterionLinkView criterion, boolean seeded, @Nullable PersonView assignee,
			@Nullable String priority, @Nullable BigDecimal estimateHours, int checklistDone, int checklistTotal,
			@Nullable String returnedReason, long version) {
	}

	/** The four Self-check answers of a submission (AD-39). */
	record SelfCheckView(boolean meetsCriteria, boolean tested, boolean docsUpdated, boolean noSecrets) {
	}

	/**
	 * A submission, approval, or return.
	 * @param comment the submission's note or the review's comment
	 * @param selfCheck a submission's answers
	 */
	record ReviewView(String action, String by, @Nullable String comment, @Nullable SelfCheckView selfCheck, Instant at) {
	}

	/**
	 * The Board.
	 * @param title the Job's title
	 * @param mentor whether the viewer is the Job's Mentor
	 * @param readOnly the viewer cannot write: not the Mentor or an active member, or the Job is not Active
	 * @param crew the Crew members that are not released, for the Mentor's assignee picker; empty for anyone else
	 * @param workload with {@code features.largeCrews} on, each active member's open work, for the Mentor only
	 * @param viewerId the viewer's account, for "Của tôi" and the moves the assignee may make
	 */
	record BoardView(UUID boardId, String title, String code, UUID viewerId, boolean mentor, boolean readOnly,
			List<BoardMilestoneView> milestones, List<CrewPickView> crew, List<WorkloadView> workload,
			List<WorkItemCardView> items) {
	}

	/** The earlier note a correction quotes ("Ghi chú sửa lại"). */
	record QuotedNoteView(UUID id, String actor, String text, Instant at) {
	}

	/**
	 * One history entry.
	 * @param actor who saved it; empty for vibefix (a seeded Story)
	 * @param mine whether the viewer wrote it: "Sửa lại" is offered on one's own notes
	 * @param fields the fields the save changed
	 * @param assignedTo the person it assigned, when it did
	 * @param comment a submission's note, a review's comment, or a note's text
	 * @param attachment the name of the Attachment an {@code ATTACHED} or {@code DETACHED} entry names
	 * @param quoted the earlier note a correction quotes
	 */
	record HistoryEntryView(UUID id, String actor, boolean mine, String action, List<String> fields,
			@Nullable String assignedTo, @Nullable String comment, @Nullable String attachment,
			@Nullable QuotedNoteView quoted, Instant at) {
	}

	/**
	 * One Attachment (FR-76, UX-DR79).
	 * @param kind {@code FILE} or {@code LINK}
	 * @param name a file's name, a link's label, or else its domain
	 * @param domain a link's domain, shown as an External link
	 * @param url a link's {@code https} address
	 * @param downloadUrl a file's download, which {@code files} serves only when {@code work} allows it
	 * @param size a file's size in bytes
	 * @param removable the viewer is its author and may still remove it: the Work Item is not Done
	 */
	record WorkAttachmentView(UUID id, String kind, String name, @Nullable String domain, @Nullable String url,
			@Nullable String downloadUrl, @Nullable String mediaType, @Nullable Long size, String author,
			boolean removable, Instant addedAt) {
	}

	/**
	 * One Work Item with its checklist, Attachments, and history, oldest first.
	 * @param review the latest submission, approval, or return, for the Review dialog and the badge
	 */
	record WorkItemDetailView(WorkItemCardView item, List<ChecklistLineView> checklist, @Nullable String createdBy,
			Instant createdAt, boolean readOnly, @Nullable ReviewView review, List<WorkAttachmentView> attachments,
			List<HistoryEntryView> history) {
	}

	private final AgreementCriteria criteria;

	private final Crews crews;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final HeldMilestones held;

	private final boolean largeCrews;

	WorkViews(AgreementCriteria criteria, Crews crews, MentorDirectory mentors, StudentDirectory students,
			HeldMilestones held, @Value("${vibefix.features.large-crews:false}") boolean largeCrews) {
		this.held = held;
		this.largeCrews = largeCrews;
		this.criteria = criteria;
		this.crews = crews;
		this.mentors = mentors;
		this.students = students;
	}

	BoardView board(BoardViewer viewer, List<Item> items, Map<UUID, Entry> latestReviews) {
		CriteriaInForce inForce = this.criteria.inForce(viewer.board().jobId()).orElse(null);
		Map<UUID, CriterionInForce> criteria = criteria(inForce);
		Set<UUID> heldIds = this.held.heldOf(viewer.board().jobId());
		List<BoardMilestoneView> milestones = inForce == null ? List.of()
				: inForce.milestones()
					.stream()
					.map(milestone -> new BoardMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title(),
							heldIds.contains(milestone.milestoneId())))
					.toList();
		// AD-41: an INACTIVE member stays in the picker, disabled, so the Mentor sees why they cannot be chosen.
		List<CrewMember> members = viewer.mentor() ? this.crews.crewOf(viewer.board().jobId(), Selection.UNRELEASED)
			.stream()
			.filter(member -> "ACTIVE".equals(member.status()) || "INACTIVE".equals(member.status()))
			.toList() : List.of();
		Set<UUID> people = new HashSet<>();
		members.forEach(member -> people.add(member.studentId()));
		items.stream().map(Item::assigneeId).filter(id -> id != null).forEach(people::add);
		Map<UUID, String> names = names(viewer, people);
		Set<UUID> unlocked = new HashSet<>();
		if (!members.isEmpty()) {
			this.students.cards(members.stream().map(CrewMember::studentId).toList())
				.stream()
				.filter(StudentCard::available)
				.forEach(card -> unlocked.add(card.accountId()));
		}
		List<CrewPickView> crew = members.stream()
			.map(member -> new CrewPickView(member.studentId(), names.getOrDefault(member.studentId(), ""),
					"ACTIVE".equals(member.status()) && unlocked.contains(member.studentId())))
			.toList();
		List<WorkloadView> workload = viewer.mentor() && this.largeCrews ? crew.stream()
			.filter(CrewPickView::available)
			.map(member -> workload(member, items))
			.toList() : List.of();
		return new BoardView(viewer.board().jobId(), viewer.title(), viewer.board().code(), viewer.accountId(),
				viewer.mentor(),
				!viewer.writes(), milestones, crew, workload,
				items.stream().map(item -> card(item, criteria, names, latestReviews.get(item.id()))).toList());
	}

	private static WorkloadView workload(CrewPickView member, List<Item> items) {
		List<Item> open = items.stream()
			.filter(item -> member.accountId().equals(item.assigneeId()) && item.status() != Boards.Status.DONE)
			.toList();
		BigDecimal hours = open.stream()
			.map(Item::estimateHours)
			.filter(estimate -> estimate != null)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new WorkloadView(member.accountId(), member.name(), open.size(), hours);
	}

	/**
	 * The detail.
	 * @param attachments the Attachments the viewer sees
	 * @param all every Attachment of the Work Item, which history entries are named after
	 */
	WorkItemDetailView detail(BoardViewer viewer, Item item, List<Entry> history, List<Attachment> attachments,
			List<Attachment> all) {
		Map<UUID, CriterionInForce> criteria = criteria(this.criteria.inForce(viewer.board().jobId()).orElse(null));
		Set<UUID> people = new HashSet<>();
		if (item.assigneeId() != null) {
			people.add(item.assigneeId());
		}
		if (item.createdBy() != null) {
			people.add(item.createdBy());
		}
		for (Entry entry : history) {
			if (entry.actor() != null) {
				people.add(entry.actor());
			}
			if (entry.assignedTo() != null) {
				people.add(entry.assignedTo());
			}
		}
		attachments.stream().map(Attachment::authorId).filter(id -> id != null).forEach(people::add);
		Map<UUID, String> names = names(viewer, people);
		Map<UUID, Attachment> byId = new HashMap<>();
		all.forEach(attachment -> byId.put(attachment.id(), attachment));
		Map<UUID, Entry> notes = new HashMap<>();
		history.stream().filter(entry -> "NOTE".equals(entry.action())).forEach(entry -> notes.put(entry.id(), entry));
		List<HistoryEntryView> entries = new ArrayList<>();
		Entry latestReview = null;
		for (Entry entry : history) {
			List<String> fields = new ArrayList<>();
			entry.changes().properties().forEach(change -> fields.add(change.getKey()));
			Attachment named = entry.attachmentId() == null ? null : byId.get(entry.attachmentId());
			Entry quoted = entry.quotes() == null ? null : notes.get(entry.quotes());
			entries.add(new HistoryEntryView(entry.id(), name(names, entry.actor()),
					entry.actor() != null && entry.actor().equals(viewer.accountId()), entry.action(), fields,
					entry.assignedTo() == null ? null : names.getOrDefault(entry.assignedTo(), ""), entry.comment(),
					named == null ? null : named.name(),
					quoted == null ? null
							: new QuotedNoteView(quoted.id(), name(names, quoted.actor()),
									quoted.comment() == null ? "" : quoted.comment(), quoted.at()),
					entry.at()));
			if (WorkReads.REVIEW_ACTIONS.contains(entry.action())) {
				latestReview = entry;
			}
		}
		boolean removable = viewer.writes() && item.status() != Boards.Status.DONE;
		List<WorkAttachmentView> listed = attachments.stream()
			.map(attachment -> attachment(attachment, names,
					removable && viewer.accountId().equals(attachment.authorId())))
			.toList();
		ReviewView review = latestReview == null ? null
				: new ReviewView(latestReview.action(),
						latestReview.actor() == null ? "" : names.getOrDefault(latestReview.actor(), ""), latestReview.comment(),
						selfCheck(latestReview.selfCheck()), latestReview.at());
		return new WorkItemDetailView(card(item, criteria, names, latestReview),
				item.checklist().stream().map(line -> new ChecklistLineView(line.text(), line.done())).toList(),
				item.createdBy() == null ? null : names.getOrDefault(item.createdBy(), ""), item.createdAt(), !viewer.writes(),
				review, listed, entries);
	}

	private static String name(Map<UUID, String> names, @Nullable UUID person) {
		return person == null ? "" : names.getOrDefault(person, "");
	}

	private static WorkAttachmentView attachment(Attachment attachment, Map<UUID, String> names, boolean removable) {
		boolean file = attachment.kind() == Attachments.Kind.FILE;
		return new WorkAttachmentView(attachment.id(), attachment.kind().name(), attachment.name(),
				file ? null : Attachments.domain(attachment.url()), file ? null : attachment.url(),
				file ? "/api/v1/files/" + attachment.fileId() : null, attachment.mediaType(), attachment.size(),
				name(names, attachment.authorId()), removable, attachment.addedAt());
	}

	private static Map<UUID, CriterionInForce> criteria(@Nullable CriteriaInForce inForce) {
		Map<UUID, CriterionInForce> criteria = new HashMap<>();
		if (inForce != null) {
			inForce.criteria().forEach(criterion -> criteria.put(criterion.criterionId(), criterion));
		}
		return criteria;
	}

	private Map<UUID, String> names(BoardViewer viewer, Collection<UUID> people) {
		Map<UUID, String> names = new HashMap<>();
		this.mentors.cards(List.of(viewer.mentorId())).stream().findFirst().map(MentorCard::displayName)
			.ifPresent(name -> names.put(viewer.mentorId(), name));
		List<UUID> students = people.stream().filter(id -> !id.equals(viewer.mentorId())).toList();
		if (!students.isEmpty()) {
			for (StudentCard card : this.students.cards(students)) {
				names.put(card.accountId(), card.name() != null ? card.name() : "@" + card.githubLogin());
			}
		}
		return names;
	}

	private static @Nullable SelfCheckView selfCheck(@Nullable JsonNode answers) {
		return answers == null ? null
				: new SelfCheckView(answers.path("meetsCriteria").asBoolean(), answers.path("tested").asBoolean(),
						answers.path("docsUpdated").asBoolean(), answers.path("noSecrets").asBoolean());
	}

	private static WorkItemCardView card(Item item, Map<UUID, CriterionInForce> criteria, Map<UUID, String> names,
			@Nullable Entry latestReview) {
		String returned = latestReview != null && "RETURNED".equals(latestReview.action()) ? latestReview.comment() : null;
		CriterionInForce criterion = item.criterionId() == null ? null : criteria.get(item.criterionId());
		int done = (int) item.checklist().stream().filter(ChecklistLine::done).count();
		return new WorkItemCardView(item.id(), item.key().toString(), item.type().name(), item.title(), item.description(),
				item.status().name(), item.milestoneId(),
				criterion == null ? null
						: new CriterionLinkView(criterion.criterionId(), criterion.milestoneNumber(), criterion.number()),
				item.seeded(),
				item.assigneeId() == null ? null : new PersonView(item.assigneeId(), names.getOrDefault(item.assigneeId(), "")),
				item.priority() == null ? null : item.priority().name(), item.estimateHours(), done, item.checklist().size(),
				returned, item.version());
	}

}
