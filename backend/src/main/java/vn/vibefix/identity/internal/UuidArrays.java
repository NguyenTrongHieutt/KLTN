package vn.vibefix.identity.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/** The profile's {@code skill_ids} as the directories read and query them. */
final class UuidArrays {

	private UuidArrays() {
	}

	static List<UUID> uuids(@Nullable Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		Object[] values = (Object[]) array.getArray();
		List<UUID> ids = new ArrayList<>(values.length);
		for (Object value : values) {
			ids.add(value instanceof UUID id ? id : UUID.fromString(value.toString()));
		}
		return ids;
	}

	/** A PostgreSQL array literal of UUIDs, cast in the query. */
	static String uuidArray(Collection<UUID> ids) {
		return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
	}

}
