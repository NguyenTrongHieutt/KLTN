package vn.vibefix.work.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import vn.vibefix.scanning.Finding;
import vn.vibefix.scanning.FindingCategory;

/**
 * The Page templates of FR-30, from {@code work/page-templates.yaml}: each a default title and a
 * Markdown skeleton. The file is the one list of templates; the SPA holds only their labels. The
 * Assessment Report's skeleton is completed with the Product's open Findings when it is created (FR-8,
 * FR-10): each one's plain type, file, and masked value, never its commit or value.
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

	static final String OPEN_FINDINGS = "{openFindings}";

	Skeleton skeleton(Template template) {
		return this.skeletons.get(template);
	}

	/** The Assessment Report's body with its "Vấn đề còn mở từ kiểm tra" section listing {@code findings}. */
	String report(List<Finding> findings) {
		String body = skeleton(Template.ASSESSMENT_REPORT).body();
		if (findings.isEmpty()) {
			return body.replace(OPEN_FINDINGS, "Không có vấn đề nào còn mở khi tạo báo cáo.");
		}
		StringBuilder lines = new StringBuilder();
		for (Finding finding : findings) {
			if (!lines.isEmpty()) {
				lines.append('\n');
			}
			lines.append("- **").append(inline(finding.type())).append("** trong `").append(inline(finding.path())).append('`');
			if (finding.category() == FindingCategory.PERSONAL_DATA && finding.matchCount() != null) {
				lines.append(" (").append(finding.matchCount()).append(" dòng)");
			}
			lines.append(": `").append(inline(finding.maskedPreview())).append("`. Cách xử lý đề nghị:");
		}
		return body.replace(OPEN_FINDINGS, lines);
	}

	/** One line of inline code: no backticks or line breaks of its own. */
	private static String inline(String text) {
		return text.replace('`', '\'').replaceAll("[\\r\\n]+", " ").strip();
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
