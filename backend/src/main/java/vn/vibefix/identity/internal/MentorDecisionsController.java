package vn.vibefix.identity.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.MentorDecisions.Detail;
import vn.vibefix.identity.internal.MentorDecisions.Outcome;
import vn.vibefix.identity.internal.MentorDecisions.QueueRow;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.PageResponse;

/**
 * ADM-13 (FR-74, AD-19): the Mentor applications by status, each with the accounts that may be the
 * same person, and their decisions after a step-up. The document is read through
 * {@code GET /api/v1/files/{fileId}}, where each read is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/mentor-applications")
@Access(Gate.ADMINISTRATOR)
class MentorDecisionsController {

	/** @param matches how many other accounts share a person-link signal with this one */
	record MentorApplicationRow(UUID accountId, @Nullable String displayName, @Nullable String githubLogin,
			@Nullable Integer yearsOfExperience, MentorApprovalStatus status, @Nullable Instant submittedAt,
			@Nullable Instant decidedAt, long matches) {

		static MentorApplicationRow of(QueueRow row) {
			return new MentorApplicationRow(row.accountId(), row.displayName(), row.githubLogin(),
					row.yearsOfExperience(), row.status(), row.submittedAt(), row.decidedAt(), row.matches());
		}

	}

	/**
	 * Another account that may be the same person: what it is, never the shared value (AD-19).
	 * @param role {@code CUSTOMER}, {@code STUDENT}, {@code MENTOR}, or {@code ADMINISTRATOR}
	 * @param erased whether the account was erased; its signals stay for 24 months
	 * @param verifiedUntil a Student's verification end, if they ever verified
	 * @param kind {@code EMAIL} for a shared verified email, {@code LINKEDIN} for a shared profile
	 */
	record MentorApplicationMatch(UUID accountId, String role, @Nullable MentorApprovalStatus mentorStatus,
			boolean locked, boolean erased, @Nullable LocalDate verifiedUntil, String kind, Instant createdAt) {
	}

	record MentorApplicationSkill(UUID id, String name, boolean active) {
	}

	/** @param fileUrl where the document is read, until it is deleted 30 days after the decision */
	record MentorApplicationDocument(String name, String mediaType, long size, String fileUrl) {
	}

	record MentorApplicationDetail(UUID accountId, @Nullable String displayName, @Nullable String githubLogin,
			@Nullable String experience, @Nullable Integer yearsOfExperience, List<MentorApplicationSkill> skills,
			@Nullable String linkedinUrl, @Nullable String otherLink, @Nullable MentorApplicationDocument document,
			@Nullable String privacyNoticeVersion, @Nullable Instant submittedAt, MentorApprovalStatus status,
			@Nullable Instant decidedAt, @Nullable String rejectionReason, List<MentorApplicationMatch> matches) {

		static MentorApplicationDetail of(Detail detail) {
			MentorApplications.Document document = detail.document();
			return new MentorApplicationDetail(detail.accountId(), detail.displayName(), detail.githubLogin(),
					detail.experience(), detail.yearsOfExperience(),
					detail.skills()
						.stream()
						.map(skill -> new MentorApplicationSkill(skill.id(), skill.name(), skill.active()))
						.toList(),
					detail.linkedinUrl(), detail.otherLink(),
					document == null ? null
							: new MentorApplicationDocument(document.name(), document.mediaType(), document.size(),
									"/api/v1/files/" + document.fileId()),
					detail.privacyNoticeVersion(), detail.submittedAt(), detail.status(), detail.decidedAt(),
					detail.rejectionReason(),
					detail.matches()
						.stream()
						.map(match -> new MentorApplicationMatch(match.accountId(), match.role(), match.mentorStatus(),
								match.locked(), match.erased(), match.verifiedUntil(), match.kind(), match.createdAt()))
						.toList());
		}

	}

	record ApproveMentorRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	record RejectMentorRequest(@NotBlank @Size(max = 500) String reason,
			@NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	private final MentorDecisions decisions;

	private final CurrentCaller callers;

	MentorDecisionsController(MentorDecisions decisions, CurrentCaller callers) {
		this.decisions = decisions;
		this.callers = callers;
	}

	/** {@code sort} takes {@code submittedAt} or {@code decidedAt}; pending applications come oldest first. */
	@GetMapping
	PageResponse<MentorApplicationRow> applications(
			@RequestParam(defaultValue = "PENDING") MentorApprovalStatus status,
			@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		return PageResponse.of(this.decisions.queue(status, pageable), MentorApplicationRow::of);
	}

	@GetMapping("/{applicantId}")
	MentorApplicationDetail application(@PathVariable UUID applicantId) {
		return this.decisions.detail(applicantId)
			.map(MentorApplicationDetail::of)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** Approves the application: the Mentor may take every Mentor action (FR-74). */
	@PostMapping("/{applicantId}/approve")
	MentorApplicationRow approve(@PathVariable UUID applicantId, @Valid @RequestBody ApproveMentorRequest body,
			HttpServletRequest request) {
		return done(this.decisions.approve(this.callers.get().requireAccountId(), applicantId, body.stepUpCode()),
				request);
	}

	/** Rejects the application with the reason its Mentor reads; they may submit again after 30 days. */
	@PostMapping("/{applicantId}/reject")
	MentorApplicationRow reject(@PathVariable UUID applicantId, @Valid @RequestBody RejectMentorRequest body,
			HttpServletRequest request) {
		return done(this.decisions.reject(this.callers.get().requireAccountId(), applicantId, body.reason(),
				body.stepUpCode()), request);
	}

	private static MentorApplicationRow done(Outcome outcome, HttpServletRequest request) {
		if (outcome instanceof Outcome.Refused refused) {
			if (refused.endSession()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			throw refused.refusal();
		}
		return MentorApplicationRow.of(((Outcome.Done) outcome).row());
	}

}
