package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationType;
import vn.vibefix.notification.TodoType;

/**
 * The registry and its texts agree (AD-27, AD-20, UX-DR15, UX-DR16): every notification type has its
 * in-app title in {@code vi.json} and, when it sends email, a template whose subject is that title;
 * every to-do key has its strip text; and each text uses exactly the declared parameters. Every entry
 * is rendered with canary values for each audience class it allows: each canary shows where its
 * parameter is, and nothing is left unfilled.
 */
class NotificationCatalogTest {

	static final Path VI = Path.of("..", "frontend", "src", "i18n", "vi.json");

	static final Path TEMPLATES = Path.of("src", "main", "resources", "templates", "email");

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)\\}");

	private final NotificationTypes catalog = new NotificationTypes();

	private final JsonNode vi = read();

	@Test
	void everyToDoKeyHasItsStripTextWithExactlyItsParameters() {
		assertThat(this.catalog.todoTypes()).isNotEmpty();
		for (TodoType type : this.catalog.todoTypes()) {
			String text = text("todo." + type.key());
			assertThat(placeholders(text)).as("parameters of todo.%s", type.key()).isEqualTo(type.params());
			for (Audience audience : type.audiences()) {
				assertRendersEveryCanary(type.key() + " for " + audience, text, type.params());
			}
		}
	}

	@Test
	void everyNotificationTypeHasItsTitleAndItsEmailTemplate() throws IOException {
		for (NotificationType type : this.catalog.notificationTypes()) {
			String title = text("notification." + type.key() + ".title");
			JsonNode body = node("notification." + type.key() + ".body");
			Set<String> used = new LinkedHashSet<>(placeholders(title));
			if (body != null) {
				used.addAll(placeholders(body.asString()));
			}
			assertThat(used).as("parameters of notification.%s", type.key()).isEqualTo(type.params());
			if (type.channel().email()) {
				Path file = TEMPLATES.resolve(type.key() + ".txt");
				assertThat(file).as("the email template of %s", type.key()).exists();
				EmailTemplate template = EmailTemplate.parse(type.key(), Files.readString(file, StandardCharsets.UTF_8));
				assertThat(template.placeholders()).as("parameters of the %s email", type.key()).isEqualTo(type.params());
				Map<String, String> canaries = canaries(type.params());
				assertThat(template.render(canaries, "https://vibefix.vn").subject()).as("the subject is the title")
					.isEqualTo(fill(title, canaries));
			}
			for (Audience audience : type.audiences()) {
				assertRendersEveryCanary(type.key() + " for " + audience, title, type.params());
			}
		}
	}

	@Test
	void theRegistryRefusesTwoEntriesWithOneKey() {
		NotificationTypes twice = new NotificationTypes();

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> new NotificationRegistry(java.util.List.of(this.catalog, twice), java.util.List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("twice");
	}

	private static void assertRendersEveryCanary(String what, String text, Set<String> params) {
		Map<String, String> canaries = canaries(params);
		String rendered = fill(text, canaries);
		assertThat(rendered).as(what).doesNotContain("{");
		canaries.values().forEach(canary -> assertThat(rendered).as(what).contains(canary));
	}

	private static Map<String, String> canaries(Set<String> params) {
		Map<String, String> canaries = new HashMap<>();
		params.forEach(param -> canaries.put(param, "canary-" + param + "-7f3a"));
		return canaries;
	}

	private static String fill(String text, Map<String, String> values) {
		Matcher matcher = PLACEHOLDER.matcher(text);
		StringBuilder filled = new StringBuilder();
		while (matcher.find()) {
			String value = values.get(matcher.group(1));
			matcher.appendReplacement(filled, Matcher.quoteReplacement(value == null ? matcher.group() : value));
		}
		matcher.appendTail(filled);
		return filled.toString();
	}

	private static Set<String> placeholders(String text) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = PLACEHOLDER.matcher(text);
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}

	private String text(String key) {
		JsonNode node = node(key);
		assertThat(node).as("vi.json has %s", key).isNotNull();
		assertThat(node.isString()).as("vi.json %s is a text", key).isTrue();
		return node.asString();
	}

	private JsonNode node(String key) {
		JsonNode node = this.vi;
		for (String part : key.split("\\.")) {
			node = node.get(part);
			if (node == null) {
				return null;
			}
		}
		return node;
	}

	private static JsonNode read() {
		try {
			return JsonMapper.builder().build().readTree(Files.readString(VI, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
