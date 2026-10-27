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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.Notices;
import vn.vibefix.identity.internal.StudentVerifications.Confirmation;
import vn.vibefix.identity.internal.StudentVerifications.Status;
import vn.vibefix.identity.internal.StudentVerifications.View;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Kind;

/**
 * ACC-2's student email row (FR-5, FR-80, AD-42, UX-DR93), for any person's account: the status, the
 * school email code and its entry after N-26, and "Bỏ xác minh". The school email address appears in no
 * answer: the code step and the badge name the school. An Administrator has no verification and gets
 * {@code NOT_FOUND}.
 */
@RestController
@RequestMapping("/api/v1/verification")
@Access(Gate.SIGNED_IN)
class VerificationController {

	/**
	 * @param status {@code VERIFIED} until the end of {@code verifiedUntil}; {@code EXPIRED} from then on,
	 * with only the method and the last valid day ("Chưa xác minh" and the expired alert); {@code UNVERIFIED}
	 * when never verified or after "Bỏ xác minh"
	 * @param method how the account verified: {@code SCHOOL_EMAIL}, or {@code STUDENT_CARD} for an expired
	 * row from before Story 19.7 retired the card
	 * @param school the allowlisted domain the school email matched
	 * @param name the full name declared at verification, locked while verified ("Khai khi xác minh")
	 * @param major the major declared at verification, locked while verified
	 * @param verifiedUntil the last valid day, a 30 September
	 * @param renewalOpen the verification is within 14 days of its expiry, so a code renews it
	 * @param pendingCode the code waiting for its entry, while the account is not verified or renews
	 */
	record VerificationView(Status status, @Nullable String method, @Nullable String school, @Nullable String name,
			@Nullable String major, @Nullable Instant verifiedAt, @Nullable LocalDate verifiedUntil, boolean renewalOpen,
			@Nullable PendingCodeView pendingCode) {

		static VerificationView of(View view) {
			VerificationRows.Row verification = view.verification();
			VerificationRows.Facts facts = verification == null ? null : verification.facts();
			StudentVerifications.PendingCode pending = view.pendingCode();
			return new VerificationView(view.status(), verification == null ? null : verification.provider().method(),
					facts == null ? null : facts.school(), facts == null ? null : facts.name(),
					facts == null ? null : facts.major(),
					verification == null || view.status() != Status.VERIFIED ? null : verification.verifiedAt(),
					verification == null ? null : verification.validUntil(), view.renewalOpen(),
					pending == null ? null : new PendingCodeView(pending.school(), pending.sentAt(), pending.expiresAt(),
							pending.attemptsLeft(), pending.expired()));
		}

	}

	/**
	 * @param school the school whose address the code went to: "Đã gửi mã đến email của {school}"
	 * @param attemptsLeft entries left before the code needs replacing, 0 to 5
	 */
	record PendingCodeView(String school, Instant sentAt, Instant expiresAt, int attemptsLeft, boolean expired) {
	}

	/**
	 * "Gửi mã" and "Gửi mã mới" after N-26.
	 * @param noticeVersion the version of N-26 the person accepted
	 * @param name the full name to declare: required on a first verification, ignored on a renewal
	 * @param major the major to declare: required on a first verification, ignored on a renewal
	 */
	record SchoolEmailRequest(@NotBlank @Size(max = 20) String noticeVersion,
			@NotBlank @Email @Size(max = 254) String email, @Nullable @Size(max = 100) String name,
			@Nullable @Size(max = 100) String major) {
	}

	record CodeRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
	}

	private final StudentVerifications verifications;

	private final Accounts accounts;

	private final CurrentCaller caller;

	VerificationController(StudentVerifications verifications, Accounts accounts, CurrentCaller caller) {
		this.verifications = verifications;
		this.accounts = accounts;
		this.caller = caller;
	}

	@GetMapping
	VerificationView verification() {
		return view(requireUser().id());
	}

	/**
	 * Records N-26 and emails a 6-digit code to a school address on the allowlist (FR-5, AD-34).
	 * @throws ApiException {@code STALE} for an N-26 version that is not the current one
	 */
	@PostMapping("/school-email")
	@ResponseStatus(HttpStatus.ACCEPTED)
	VerificationView sendCode(@Valid @RequestBody SchoolEmailRequest request) {
		UUID account = requireUser().id();
		if (!Notices.VERIFY_STUDENT_VERSION.equals(request.noticeVersion())) {
			throw new ApiException(ErrorCode.STALE);
		}
		this.verifications.send(account, request.email(), request.name(), request.major(), request.noticeVersion());
		return view(account);
	}

	/** Verifies the account with the emailed code; a refused entry is answered after it is counted. */
	@PostMapping("/school-email/confirm")
	VerificationView confirm(@Valid @RequestBody CodeRequest request) {
		UUID account = requireUser().id();
		Confirmation confirmation = this.verifications.confirm(account, request.code());
		if (confirmation instanceof Confirmation.Refused refused) {
			throw refused.refusal();
		}
		return view(account);
	}

	/** "Bỏ xác minh", after the Confirmation dialog: the public facts go at once; the keyed hash stays (FR-80). */
	@DeleteMapping("/student")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unlinkStudent() {
		this.verifications.unlink(requireUser().id());
	}

	private VerificationView view(UUID account) {
		return VerificationView.of(this.verifications.view(account));
	}

	/** The caller's account, if it is an active person's; an Administrator has no verification. */
	private Account requireUser() {
		return this.accounts.findById(this.caller.get().requireAccountId())
			.filter(found -> found.kind() == Kind.USER && found.isActive())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
