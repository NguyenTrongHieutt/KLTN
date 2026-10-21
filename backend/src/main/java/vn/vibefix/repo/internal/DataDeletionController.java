package vn.vibefix.repo.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.repo.internal.DataDeletionConfirmations.Duty;
import vn.vibefix.repo.internal.DataDeletionConfirmations.Item;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * The data-deletion confirmation in JOB-1's closure section (FR-66, AD-20, UX-DR51, UX-DR55), under
 * {@code /api/v1/boards/{boardId}} like the Job's other Active-phase calls. The Customer and the Mentor
 * read every party's status, and a Crew member who was ever active reads their own; the Mentor and those
 * Crew members tick their items once the Job has ended. Everyone else finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class DataDeletionController {

	/** One item of the viewer's Checklist; {@code confirmedAt} once ticked. */
	record DataDeletionItemView(String item, @Nullable Instant confirmedAt) {
	}

	/** One party's status: {@code MENTOR} or {@code CREW}. */
	record DataDeletionPartyView(String party, String name, boolean confirmed) {
	}

	/**
	 * @param ended the Job is Closing, Closed, or Cancelled, so the items may be ticked
	 * @param mine the viewer's own Checklist, when the duty is theirs
	 * @param removed a Crew change removed the viewer from the running Job, so they may tick theirs now
	 */
	record DataDeletionView(UUID jobId, boolean ended, @Nullable List<DataDeletionItemView> mine,
			List<DataDeletionPartyView> parties, boolean removed) {
	}

	private final DataDeletionConfirmations confirmations;

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	private final CurrentCaller callers;

	DataDeletionController(DataDeletionConfirmations confirmations, MentorDirectory mentors, StudentDirectory students,
			CurrentCaller callers) {
		this.confirmations = confirmations;
		this.mentors = mentors;
		this.students = students;
		this.callers = callers;
	}

	@GetMapping("/api/v1/boards/{boardId}/data-deletion")
	DataDeletionView dataDeletion(@PathVariable("boardId") UUID jobId) {
		Caller caller = this.callers.get();
		JobPostState job = reader(jobId, caller);
		return view(job, caller.requireAccountId());
	}

	/** One tick of "Đã xóa dữ liệu" or "Đã xóa bản xem thử", confirmed in a dialog first (UX-DR51). */
	@PostMapping("/api/v1/boards/{boardId}/data-deletion/{item}")
	DataDeletionView confirm(@PathVariable("boardId") UUID jobId, @PathVariable Item item) {
		Caller caller = this.callers.get();
		JobPostState job = reader(jobId, caller);
		this.confirmations.confirm(job, caller.requireAccountId(), item);
		return view(job, caller.requireAccountId());
	}

	/** The Customer, the Mentor, or a Crew member who was ever active; a Job before Active has nothing. */
	private JobPostState reader(UUID jobId, Caller caller) {
		JobPostState job = this.confirmations.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		boolean board = List.of("ACTIVE", "CLOSING", "CLOSED", "CANCELLED").contains(job.state());
		if (account == null || !board) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		boolean customer = caller.hasRole(Role.CUSTOMER) && account.equals(job.customerId());
		boolean duty = (caller.hasRole(Role.MENTOR) || caller.hasRole(Role.STUDENT))
				&& this.confirmations.duty(job, account).isPresent();
		if (!customer && !duty) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return job;
	}

	private DataDeletionView view(JobPostState job, UUID viewer) {
		List<Duty> duties = this.confirmations.duties(job);
		boolean everyone = viewer.equals(job.customerId()) || viewer.equals(job.mentorId());
		List<UUID> crew = duties.stream().filter(duty -> !duty.mentor()).map(Duty::accountId).toList();
		Map<UUID, String> names = crew.isEmpty() ? Map.of()
				: this.students.cards(crew)
					.stream()
					.collect(Collectors.toMap(StudentCard::accountId,
							card -> card.name() != null ? card.name() : "@" + card.githubLogin(), (a, b) -> a));
		String mentorName = job.mentorId() == null ? ""
				: this.mentors.cards(List.of(job.mentorId())).stream().findFirst().map(MentorCard::displayName).orElse("");
		List<DataDeletionPartyView> parties = new ArrayList<>();
		List<DataDeletionItemView> mine = null;
		for (Duty duty : duties) {
			if (duty.accountId().equals(viewer)) {
				mine = new ArrayList<>();
				for (Item item : Item.values()) {
					mine.add(new DataDeletionItemView(item.name(), duty.confirmed().get(item)));
				}
			}
			if (everyone || duty.accountId().equals(viewer)) {
				parties.add(new DataDeletionPartyView(duty.mentor() ? "MENTOR" : "CREW",
						duty.mentor() ? mentorName : names.getOrDefault(duty.accountId(), ""), duty.complete()));
			}
		}
		return new DataDeletionView(job.jobId(), DataDeletionConfirmations.ENDED.contains(job.state()), mine, parties,
				mine != null && this.confirmations.removed(job, viewer));
	}

}
