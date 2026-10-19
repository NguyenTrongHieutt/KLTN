package vn.vibefix.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.AdministratorEnrolments.Outcome;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * ADM-11 (FR-2, SG-10): the Administrators, and creating one after a step-up. A refusal that locked
 * the creator's email also ends the creator's session.
 */
@RestController
@RequestMapping("/api/v1/admin/administrators")
@Access(Gate.ADMINISTRATOR)
class AdministratorsController {

	/**
	 * @param status {@code ACTIVE}, {@code ENROLLING} while the enrolment link is open, {@code EXPIRED}
	 * once it lapsed
	 * @param lockedUntil when a sign-in lock of this email ends, while there is one
	 */
	record AdministratorListItem(UUID id, String email, String displayName, String status, Instant createdAt,
			@Nullable Instant signedInAt, @Nullable Instant lockedUntil, boolean you) {
	}

	record CreateAdministratorRequest(@NotBlank @Size(max = 100) String displayName,
			@NotBlank @Email @Size(max = 254) String email, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	record CreatedView(UUID id) {
	}

	private final AdministratorEnrolments enrolments;

	private final CurrentCaller callers;

	AdministratorsController(AdministratorEnrolments enrolments, CurrentCaller callers) {
		this.enrolments = enrolments;
		this.callers = callers;
	}

	@GetMapping
	List<AdministratorListItem> administrators() {
		UUID me = this.callers.get().requireAccountId();
		return this.enrolments.list()
			.stream()
			.map(row -> new AdministratorListItem(row.id(), row.email(), row.displayName(), row.status(), row.createdAt(),
					row.signedInAt(), row.lockedUntil(), row.id().equals(me)))
			.toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CreatedView create(@Valid @RequestBody CreateAdministratorRequest body, HttpServletRequest request) {
		Outcome outcome = this.enrolments.create(this.callers.get().requireAccountId(), body.email(),
				body.displayName(), body.stepUpCode());
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
		return new CreatedView(((Outcome.Done) outcome).accountId());
	}

}
