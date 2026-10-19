package vn.vibefix.identity.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
 * STU-5's verification part (FR-5): the Student's own status, the school email code, and its entry.
 * Only a Student has one; any other role gets {@code NOT_FOUND}.
 */
@RestController
@RequestMapping("/api/v1/verification")
@Access(Gate.SIGNED_IN)
class VerificationController {

	/**
	 * @param status {@code VERIFIED} until the end of {@code verifiedUntil}, then {@code EXPIRED}
	 * @param verifiedUntil the last valid day, a 30 September
	 * @param schoolEmail the address that verified the account
	 * @param pendingCode the code waiting for its entry, while the account is not verified
	 */
	record VerificationView(Status status, @Nullable LocalDate verifiedUntil, @Nullable Instant verifiedAt,
			@Nullable String schoolEmail, @Nullable PendingCodeView pendingCode) {

		static VerificationView of(View view) {
			var verification = view.verification();
			var pending = view.pendingCode();
			return new VerificationView(view.status(), verification == null ? null : verification.validUntil(),
					verification == null ? null : verification.verifiedAt(),
					verification == null ? null : verification.schoolEmail(),
					pending == null ? null : new PendingCodeView(pending.email(), pending.sentAt(), pending.expiresAt(),
							pending.attemptsLeft(), pending.expired()));
		}

	}

	/** @param attemptsLeft entries left before the code needs replacing, 0 to 5 */
	record PendingCodeView(String email, Instant sentAt, Instant expiresAt, int attemptsLeft, boolean expired) {
	}

	record SchoolEmailRequest(@NotBlank @Email @Size(max = 254) String email) {
	}

	record CodeRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
	}

	private final StudentVerifications verifications;

	private final CurrentCaller caller;

	VerificationController(StudentVerifications verifications, CurrentCaller caller) {
		this.verifications = verifications;
		this.caller = caller;
	}

	@GetMapping
	VerificationView verification() {
		return VerificationView.of(this.verifications.view(requireStudent()));
	}

	/** Emails a 6-digit code to a school address on the allowlist (FR-5, AD-34). */
	@PostMapping("/school-email")
	@ResponseStatus(HttpStatus.ACCEPTED)
	VerificationView sendCode(@Valid @RequestBody SchoolEmailRequest request) {
		return VerificationView.of(this.verifications.send(requireStudent(), request.email()));
	}

	/** Verifies the account with the emailed code; a refused entry is answered after it is counted. */
	@PostMapping("/school-email/confirm")
	VerificationView confirm(@Valid @RequestBody CodeRequest request) {
		Confirmation confirmation = this.verifications.confirm(requireStudent(), request.code());
		return switch (confirmation) {
			case Confirmation.Verified verified -> VerificationView.of(verified.view());
			case Confirmation.Refused refused -> throw refused.refusal();
		};
	}

	private UUID requireStudent() {
		Caller current = this.caller.get();
		if (current.role() != Role.STUDENT) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return current.requireAccountId();
	}

}
