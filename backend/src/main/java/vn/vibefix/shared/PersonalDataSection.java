package vn.vibefix.shared;

import java.util.Map;
import java.util.Objects;

/**
 * One module's part of a personal-data export (AD-24).
 * @param module the module the data comes from
 * @param data the exported values, serializable as JSON
 */
public record PersonalDataSection(String module, Map<String, Object> data) {

	public PersonalDataSection {
		Objects.requireNonNull(module, "module");
		data = Map.copyOf(data);
	}

}
