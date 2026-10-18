package vn.vibefix.operations.internal;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Payloads, personal data and results as {@code jsonb} text. */
@Component
class OperationJson {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JsonMapper json;

	OperationJson(JsonMapper json) {
		this.json = json;
	}

	@Nullable
	String write(@Nullable Map<String, Object> value) {
		return value == null ? null : this.json.writeValueAsString(value);
	}

	@Nullable
	Map<String, Object> read(@Nullable String value) {
		return value == null ? null : this.json.readValue(value, MAP);
	}

}
