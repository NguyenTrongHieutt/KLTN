package vn.vibefix.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The name a Customer goes by where another person sees them act (FR-14): an invitation to apply
 * names the Customer who sent it. Never their contact details.
 */
public interface CustomerNames {

	/** The CUS-11 display name, else {@code @githubLogin}, else empty for an erased or unknown account. */
	String displayName(UUID customerId);

	/**
	 * What a Mentor may know of a Job Post's Customer before selection (FR-12, author decision 2026-10-21):
	 * their kind and when they joined, never their name or contact details.
	 * @param kind {@code INDIVIDUAL} or {@code BUSINESS}; {@code null} before the CUS-11 profile is saved
	 */
	record CustomerSince(@Nullable String kind, Instant joinedAt) {
	}

	/** The Customer's kind and joining time; empty for an erased or unknown account. */
	Optional<CustomerSince> since(UUID customerId);

	/**
	 * {@link #since} for a page of Customers in one read, keyed by account (FR-12, SG-43): the "Tìm việc" rows show
	 * each one's kind and month joined. Erased and unknown accounts are left out.
	 */
	Map<UUID, CustomerSince> sinces(Collection<UUID> customerIds);

}
