package vn.vibefix.identity.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.Profiles.ProfileKind;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * A Customer's contact details (FR-3, AD-20): the Customer, Administrators, and whoever holds an open
 * contact grant for one of the Customer's Jobs (the Job's Mentor, from Agreement until Closed or
 * Cancelled) read them, whatever role they hold; for anyone else, a Crew member included, they do not
 * exist.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/contact")
@Access(Gate.SIGNED_IN)
class ContactController {

	/** The profile's contact details; the contact email is absent until the Customer saves one. */
	record ContactView(ProfileKind kind, @Nullable String displayName, @Nullable String contactEmail,
			@Nullable String phone) {
	}

	private final Profiles profiles;

	private final CurrentCaller caller;

	ContactController(Profiles profiles, CurrentCaller caller) {
		this.profiles = profiles;
		this.caller = caller;
	}

	@GetMapping
	ContactView contact(@PathVariable UUID customerId) {
		Caller current = this.caller.get();
		UUID id = current.requireAccountId();
		boolean allowed = id.equals(customerId) || current.isAdministrator()
				|| this.profiles.hasContactGrant(customerId, id);
		if (!allowed) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.profiles.contact(customerId)
			.map(found -> new ContactView(found.kind(), found.displayName(), found.contactEmail(), found.phone()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
