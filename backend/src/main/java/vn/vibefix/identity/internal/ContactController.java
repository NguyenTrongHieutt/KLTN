package vn.vibefix.identity.internal;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.Profiles.CustomerProfile;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * A Customer's contact details (FR-3, AD-20): the Customer, Administrators, and a Mentor holding an
 * open grant for one of the Customer's Jobs read them; for anyone else they do not exist.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/contact")
@Access(Gate.SIGNED_IN)
class ContactController {

	private final Profiles profiles;

	private final CurrentCaller caller;

	ContactController(Profiles profiles, CurrentCaller caller) {
		this.profiles = profiles;
		this.caller = caller;
	}

	@GetMapping
	CustomerProfile contact(@PathVariable UUID customerId) {
		Caller current = this.caller.get();
		UUID id = current.requireAccountId();
		boolean allowed = id.equals(customerId) || current.role() == Role.ADMINISTRATOR
				|| (current.role() == Role.MENTOR && this.profiles.hasContactGrant(customerId, id));
		if (!allowed) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.profiles.customer(customerId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
