package vn.vibefix.operations;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A request for an External Operation (AD-6).
 * @param type a type with a registered {@link OperationHandler}
 * @param causingRecordId the record that causes the effect: a transition audit entry, handover,
 * Change Request, Payment Order, refund request, lock, decision, Deadline, or (for an email) the
 * record of that one message; never a long-lived entity such as an account
 * @param payload what the handler needs, without personal data; immutable once recorded
 * @param personalData what the handler needs about a person, purged when the operation completes
 * @param accountId the account {@code personalData} belongs to, so an erasure can clear it (AD-24)
 * @param priority lower runs first within a lane (AD-34: 0 transactional, 1 one-time code, 2 reminder)
 */
public record OperationRequest(String type, UUID causingRecordId, Map<String, Object> payload,
		@Nullable Map<String, Object> personalData, @Nullable UUID accountId, int priority) {

	public OperationRequest {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(causingRecordId, "causingRecordId");
		payload = Map.copyOf(payload);
		personalData = personalData == null ? null : Map.copyOf(personalData);
		if ((personalData == null) != (accountId == null)) {
			throw new IllegalArgumentException("Personal data always names the account it belongs to, and only then");
		}
		if (priority < 0) {
			throw new IllegalArgumentException("Priorities start at 0");
		}
	}

	/** {@code type:causingRecordId}, unique among all operations. */
	public String key() {
		return this.type + ":" + this.causingRecordId;
	}

}
