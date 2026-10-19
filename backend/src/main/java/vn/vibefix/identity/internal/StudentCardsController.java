package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.StudentCards.QueueRow;
import vn.vibefix.identity.internal.StudentCards.Status;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.PageResponse;

/**
 * ADM-3 (FR-5, UX-DR46): the student cards by status, a page at a time, and their decisions. The
 * card itself is read through {@code GET /api/v1/files/{fileId}}, where each read is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/student-cards")
@Access(Gate.ADMINISTRATOR)
class StudentCardsController {

	/**
	 * @param githubLogin the Student's GitHub username
	 * @param realName the name on the Student's profile, if they gave one
	 * @param profileSchool the school on the Student's profile, which "Duyệt" offers to record
	 * @param fileUrl where the card is read, until its file is deleted 30 days after the decision
	 * @param studentNumber what the approving Administrator recorded from the card
	 */
	record StudentCardRow(UUID id, @Nullable String githubLogin, @Nullable String realName,
			@Nullable String profileSchool, Status status, Instant uploadedAt, @Nullable Instant decidedAt,
			String fileName, String mediaType, @Nullable String fileUrl, @Nullable String studentNumber,
			@Nullable String school, @Nullable String rejectionReason) {

		static StudentCardRow of(QueueRow row) {
			return new StudentCardRow(row.id(), row.githubLogin(), row.realName(), row.profileSchool(), row.status(),
					row.uploadedAt(), row.decidedAt(), row.fileName(), row.mediaType(),
					row.fileDeleted() ? null : "/api/v1/files/" + row.fileId(), row.studentNumber(), row.school(),
					row.rejectionReason());
		}

	}

	record ApproveRequest(@NotBlank @Size(max = 30) String studentNumber, @NotBlank @Size(max = 200) String school) {
	}

	record RejectRequest(@NotBlank @Size(max = 300) String reason) {
	}

	private final StudentCards cards;

	private final CurrentCaller callers;

	StudentCardsController(StudentCards cards, CurrentCaller callers) {
		this.cards = cards;
		this.callers = callers;
	}

	/** {@code sort} takes {@code uploadedAt} or {@code decidedAt}; pending cards come oldest first. */
	@GetMapping
	PageResponse<StudentCardRow> cards(@RequestParam(defaultValue = "PENDING") Status status,
			@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		return PageResponse.of(this.cards.queue(status, pageable), StudentCardRow::of);
	}

	/** Approves the card, recording its student number and school, and verifies its Student. */
	@PostMapping("/{cardId}/approve")
	StudentCardRow approve(@PathVariable UUID cardId, @Valid @RequestBody ApproveRequest request) {
		return StudentCardRow.of(this.cards.approve(this.callers.get().requireAccountId(), cardId,
				request.studentNumber(), request.school()));
	}

	/** Rejects the card with the reason its Student reads. */
	@PostMapping("/{cardId}/reject")
	StudentCardRow reject(@PathVariable UUID cardId, @Valid @RequestBody RejectRequest request) {
		return StudentCardRow.of(this.cards.reject(this.callers.get().requireAccountId(), cardId, request.reason()));
	}

}
