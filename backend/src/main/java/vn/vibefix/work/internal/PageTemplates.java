package vn.vibefix.work.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The Page templates of FR-30, from {@code work/page-templates.yaml}: each a default title and a
 * Markdown skeleton. The file is the one list of templates; the SPA holds only their labels.
 */
@Component
class PageTemplates {

	enum Template {

		ASSESSMENT_REPORT, SCOPE, TECHNICAL_NOTES, MEETING_NOTES, HANDOVER, USER_GUIDE;

		/** The Assessment Report: offered only on an Assessment Job, once, and always customer-visible. */
		boolean report() {
			return this == ASSESSMENT_REPORT;
		}

	}

	record Skeleton(String title, String body) {
	}

	private final Map<Template, Skeleton> skeletons = load();

	Skeleton skeleton(Template template) {
		return this.skeletons.get(template);
	}

	@SuppressWarnings("unchecked")
	private static Map<Template, Skeleton> load() {
		try (InputStream in = PageTemplates.class.getClassLoader().getResourceAsStream("work/page-templates.yaml")) {
			if (in == null) {
				throw new IllegalStateException("work/page-templates.yaml is missing");
			}
			Map<String, Object> document = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
			Map<String, Object> templates = (Map<String, Object>) document.get("templates");
			Map<Template, Skeleton> skeletons = new EnumMap<>(Template.class);
			for (Template template : Template.values()) {
				Map<String, Object> entry = (Map<String, Object>) templates.get(template.name());
				if (entry == null) {
					throw new IllegalStateException("work/page-templates.yaml has no " + template);
				}
				skeletons.put(template, new Skeleton(String.valueOf(entry.get("title")), String.valueOf(entry.get("body"))));
			}
			return skeletons;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
