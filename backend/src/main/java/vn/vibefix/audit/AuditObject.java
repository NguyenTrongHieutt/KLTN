package vn.vibefix.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * An object an audited change affected, by type code and internal ID.
 * @param type the owning module and entity, for example {@code job.milestone}
 * @param id the object's internal ID
 */
public record AuditObject(String type, UUID id) {

	public AuditObject {
		AuditEntry.requireCode(type, "object type");
		Objects.requireNonNull(id, "id");
	}

	public static AuditObject of(String type, UUID id) {
		return new AuditObject(type, id);
	}

}
