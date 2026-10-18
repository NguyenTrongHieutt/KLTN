package vn.vibefix.notification.internal;

import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Notification and to-do parameters as {@code jsonb} text. */
@Component
class NotificationJson {

	private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {
	};

	private final JsonMapper json;

	NotificationJson(JsonMapper json) {
		this.json = json;
	}

	String write(Map<String, String> params) {
		return this.json.writeValueAsString(params);
	}

	Map<String, String> read(String params) {
		return this.json.readValue(params, PARAMS);
	}

}
