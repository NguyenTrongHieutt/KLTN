package vn.vibefix.notification.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** The email templates on the classpath, {@code templates/email/{type}.txt}, parsed once each. */
@Component
class EmailTemplates {

	static final String LOCATION = "classpath:templates/email/";

	private static final Pattern TYPE = Pattern.compile("[a-z]+(\\.[a-z0-9_]+)+");

	private final ResourceLoader resources;

	private final Map<String, Optional<EmailTemplate>> cache = new ConcurrentHashMap<>();

	EmailTemplates(ResourceLoader resources) {
		this.resources = resources;
	}

	/** The template of notification type {@code type}; empty when there is none. */
	Optional<EmailTemplate> find(String type) {
		if (type == null || !TYPE.matcher(type).matches()) {
			return Optional.empty();
		}
		return this.cache.computeIfAbsent(type, this::load);
	}

	EmailTemplate require(String type) {
		return find(type).orElseThrow(() -> new IllegalArgumentException("There is no email template for " + type));
	}

	private Optional<EmailTemplate> load(String type) {
		Resource resource = this.resources.getResource(LOCATION + type + ".txt");
		if (!resource.exists()) {
			return Optional.empty();
		}
		try {
			return Optional.of(EmailTemplate.parse(type, resource.getContentAsString(StandardCharsets.UTF_8)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read email template " + type, ex);
		}
	}

}
