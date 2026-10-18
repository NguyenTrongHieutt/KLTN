package vn.vibefix.shared.internal.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobHandler;

/**
 * {@value #TYPE}: proves the job channel works and the sandbox is isolated (AD-32). It echoes the
 * {@code nonce} it was sent, and reports {@code secretFiles}, the number of entries in the secret
 * directory ({@code VIBEFIX_SECRETS_DIR}, default {@code /run/secrets/}), and
 * {@code datasourceConfigured}, whether the worker has a {@link DataSource}. An isolated sandbox
 * reports 0 and {@code false}.
 * <p>
 * Like every handler, a plain class: only the sandbox worker instantiates it.
 */
final class HealthJobHandler implements SandboxJobHandler {

	static final String TYPE = "HEALTH";

	static final String NONCE = "nonce";

	static final String SECRET_FILES = "secretFiles";

	static final String DATASOURCE_CONFIGURED = "datasourceConfigured";

	private static final int MAX_NONCE_LENGTH = 100;

	private final Path secretsDirectory;

	private final ObjectProvider<DataSource> dataSources;

	HealthJobHandler(Environment environment, ObjectProvider<DataSource> dataSources) {
		this.secretsDirectory = Path.of(environment.getProperty("VIBEFIX_SECRETS_DIR", "/run/secrets/"));
		this.dataSources = dataSources;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public JsonNode handle(JsonNode input) {
		JsonNode nonce = input.path(NONCE);
		if (!nonce.isString() || nonce.stringValue().isEmpty() || nonce.stringValue().length() > MAX_NONCE_LENGTH) {
			throw new IllegalArgumentException("nonce must be a string of 1 to " + MAX_NONCE_LENGTH + " characters");
		}
		ObjectNode output = JsonNodeFactory.instance.objectNode();
		output.put(NONCE, nonce.stringValue());
		output.put(SECRET_FILES, countSecretFiles());
		output.put(DATASOURCE_CONFIGURED, this.dataSources.getIfAvailable() != null);
		return output;
	}

	private long countSecretFiles() {
		if (!Files.isDirectory(this.secretsDirectory)) {
			return 0;
		}
		try (Stream<Path> entries = Files.list(this.secretsDirectory)) {
			return entries.count();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
