package vn.vibefix.audit;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * One audited change (AD-8). Every field is an internal ID or a code, never free text, so an entry
 * cannot carry personal data however it is filled.
 * @param actor who caused the change
 * @param action what happened, as {@code module.past_tense_code} (for example
 * {@code payment.release_written})
 * @param objects the affected objects, at least one, in the order given
 * @param jobId the Job the change belongs to, when there is one
 */
public record AuditEntry(AuditActor actor, String action, List<AuditObject> objects, @Nullable UUID jobId) {

	private static final Pattern CODE = Pattern.compile("[a-z]+(\\.[a-z0-9_]+)+");

	public AuditEntry {
		Objects.requireNonNull(actor, "actor");
		requireCode(action, "action");
		objects = List.copyOf(objects);
		if (objects.isEmpty()) {
			throw new IllegalArgumentException("An audit entry names at least one affected object");
		}
	}

	public static AuditEntry of(AuditActor actor, String action, AuditObject... objects) {
		return new AuditEntry(actor, action, List.of(objects), null);
	}

	/** The same entry, belonging to {@code jobId}. */
	public AuditEntry forJob(UUID jobId) {
		return new AuditEntry(this.actor, this.action, this.objects, Objects.requireNonNull(jobId, "jobId"));
	}

	static void requireCode(@Nullable String value, String what) {
		if (value == null || !CODE.matcher(value).matches()) {
			throw new IllegalArgumentException(what + " must look like module.code, not '" + value + "'");
		}
	}

}
