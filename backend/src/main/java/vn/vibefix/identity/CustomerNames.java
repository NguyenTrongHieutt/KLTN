package vn.vibefix.identity;

import java.util.UUID;

/**
 * The name a Customer goes by where another person sees them act (FR-14): an invitation to apply
 * names the Customer who sent it. Never their contact details.
 */
public interface CustomerNames {

	/** The CUS-11 display name, else {@code @githubLogin}, else empty for an erased or unknown account. */
	String displayName(UUID customerId);

}
