package vn.vibefix.identity.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import vn.vibefix.files.PreparedFile;
import vn.vibefix.identity.internal.StudentCards.CardView;
import vn.vibefix.identity.internal.StudentVerifications.Confirmation;
import vn.vibefix.identity.internal.StudentVerifications.Status;
import vn.vibefix.identity.internal.StudentVerifications.View;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * STU-5's verification part (FR-5): the Student's own status, the school email code and its entry,
 * and the student card upload. Only a Student has one; any other role gets {@code NOT_FOUND}.
 */
@RestController
@RequestMapping("/api/v1/verification")
@Access(Gate.SIGNED_IN)
class VerificationController {

	/**
	 * @param status {@code VERIFIED} until the end of {@code verifiedUntil}, then {@code EXPIRED}
	 * @param method how the account verified: {@code SCHOOL_EMAIL} or {@code STUDENT_CARD}
	 * @param verifiedUntil the last valid day, a 30 September
	 * @param schoolEmail the address that verified the account, for {@code SCHOOL_EMAIL}
	 * @param pendingCode the code waiting for its entry, while the account is not verified or renews
	 * @param renewalOpen the verification is within 14 days of its expiry, so a code or a card renews it
	 * @param studentCard the latest card, while it is pending or rejected since the last verification
	 */
	record VerificationView(Status status, @Nullable String method, @Nullable LocalDate verifiedUntil,
			@Nullable Instant verifiedAt, @Nullable String schoolEmail, @Nullable PendingCodeView pendingCode,
			boolean renewalOpen, @Nullable StudentCardView studentCard) {

		static VerificationView of(View view, Optional<CardView> card) {
			var verification = view.verification();
			var pending = view.pendingCode();
			return new VerificationView(view.status(), verification == null ? null : verification.method(),
					verification == null ? null : verification.validUntil(),
					verification == null ? null : verification.verifiedAt(),
					verification == null ? null : verification.schoolEmail(),
					pending == null ? null : new PendingCodeView(pending.email(), pending.sentAt(), pending.expiresAt(),
							pending.attemptsLeft(), pending.expired()),
					view.renewalOpen(), card.map(StudentCardView::of).orElse(null));
		}

	}

	/** @param attemptsLeft entries left before the code needs replacing, 0 to 5 */
	record PendingCodeView(String email, Instant sentAt, Instant expiresAt, int attemptsLeft, boolean expired) {
	}

	/**
	 * @param status {@code PENDING} or {@code REJECTED}
	 * @param rejectionReason what the Administrator wrote, for a rejected card
	 */
	record StudentCardView(StudentCards.Status status, Instant uploadedAt, String fileName, @Nullable Instant decidedAt,
			@Nullable String rejectionReason) {

		static StudentCardView of(CardView card) {
			return new StudentCardView(card.status(), card.uploadedAt(), card.fileName(), card.decidedAt(),
					card.rejectionReason());
		}

	}

	record SchoolEmailRequest(@NotBlank @Email @Size(max = 254) String email) {
	}

	record CodeRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
	}

	private final StudentVerifications verifications;

	private final StudentCards cards;

	private final CurrentCaller caller;

	VerificationController(StudentVerifications verifications, StudentCards cards, CurrentCaller caller) {
		this.verifications = verifications;
		this.cards = cards;
		this.caller = caller;
	}

	@GetMapping
	VerificationView verification() {
		return view(requireStudent());
	}

	/** Emails a 6-digit code to a school address on the allowlist (FR-5, AD-34). */
	@PostMapping("/school-email")
	@ResponseStatus(HttpStatus.ACCEPTED)
	VerificationView sendCode(@Valid @RequestBody SchoolEmailRequest request) {
		UUID account = requireStudent();
		this.verifications.send(account, request.email());
		return view(account);
	}

	/** Verifies the account with the emailed code; a refused entry is answered after it is counted. */
	@PostMapping("/school-email/confirm")
	VerificationView confirm(@Valid @RequestBody CodeRequest request) {
		UUID account = requireStudent();
		Confirmation confirmation = this.verifications.confirm(account, request.code());
		if (confirmation instanceof Confirmation.Refused refused) {
			throw refused.refusal();
		}
		return view(account);
	}

	/**
	 * Uploads a student card, an image or a PDF of at most 10 MB, for an Administrator to approve
	 * (FR-5, AD-21). Only Administrators see it, and it is deleted 30 days after the decision.
	 */
	@PostMapping(path = "/student-card", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	VerificationView uploadCard(@RequestPart("file") MultipartFile file) {
		UUID account = requireStudent();
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		PreparedFile prepared = this.cards.prepare(account, name, content);
		this.cards.upload(account, prepared);
		return view(account);
	}

	private VerificationView view(UUID account) {
		return VerificationView.of(this.verifications.view(account), this.cards.current(account));
	}

	private UUID requireStudent() {
		Caller current = this.caller.get();
		if (current.role() != Role.STUDENT) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return current.requireAccountId();
	}

}
