package vn.vibefix.identity.internal;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spike S1's probe (Story 1.14, AD-13): under the {@code cli} profile with {@code dev},
 * {@code --vibefix.cli.command=spike-s1 --repo=<owner/name> [--repo=…]} reads each repository from GitHub's REST
 * API with the GitHub token in the process's environment variable {@value #TOKEN_VARIABLE} (read with
 * {@code System.getenv} only, never as a Spring property, so a command-line argument cannot carry it into shell
 * history), and prints the owner type, the HTTP status, and {@code permissions.admin}. vibefix keeps no GitHub token
 * (R31), so the probe never reads one from storage: the person running it supplies a dev OAuth App user token. The
 * token is held in memory only and never printed or logged. S1 closed on 2026-10-19; the probe is kept as history.
 * <p>
 * It refuses to run under {@code prod}: it exists to answer S1 on the {@code vibefix-dev} organization. Exit code
 * 0 when every repository was read, 1 when refused or when GitHub answered an error for one, 2 for a wrong
 * invocation.
 */
@Component
@Profile("cli")
class SpikeS1Command implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "spike-s1";

	/** The environment variable that holds the GitHub token the probe reads with. */
	static final String TOKEN_VARIABLE = "VIBEFIX_SPIKE_S1_TOKEN";

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** One repository as the token sees it. */
	record Probe(String repository, int status, String ownerType, String admin) {

		String line() {
			return "%-50s HTTP %d  owner=%s  permissions.admin=%s".formatted(this.repository, this.status, this.ownerType,
					this.admin);
		}

	}

	private final Environment environment;

	/** The process's environment variables, never Spring's properties, so no command-line argument can set the token. */
	private final UnaryOperator<String> variables;

	private final URI apiUrl;

	private final HttpClient http;

	private final PrintStream out;

	private final PrintStream err;

	private volatile int exitCode;

	@Autowired
	SpikeS1Command(Environment environment, @Value("${vibefix.github.api-url}") URI apiUrl) {
		this(environment, System::getenv, apiUrl, System.out, System.err);
	}

	SpikeS1Command(Environment environment, UnaryOperator<String> variables, URI apiUrl, PrintStream out,
			PrintStream err) {
		this.environment = environment;
		this.variables = variables;
		this.apiUrl = apiUrl;
		this.out = out;
		this.err = err;
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			// The token goes to the configured API host only; a renamed repository shows its 301.
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command"))) {
			// Another command's run; BootstrapCommand answers an unknown one.
			this.exitCode = 0;
			return;
		}
		List<String> repositories = args.getOptionValues("repo");
		if (repositories == null || repositories.isEmpty()
				|| !repositories.stream().allMatch(repository -> REPOSITORY.matcher(repository).matches())) {
			this.err.println("Usage: " + TOKEN_VARIABLE + "=<GitHub token> --spring.profiles.active=dev,cli"
					+ " --vibefix.cli.command=" + COMMAND + " --repo=<owner/name> [--repo=<owner/name> ...]");
			this.exitCode = 2;
			return;
		}
		if (this.environment.acceptsProfiles(Profiles.of("prod"))) {
			this.err.println("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		String token = this.variables.apply(TOKEN_VARIABLE);
		if (token == null || token.isBlank()) {
			this.err.println("Refused: set " + TOKEN_VARIABLE + " to the GitHub token to read with; vibefix keeps none");
			this.exitCode = 1;
			return;
		}
		int failures = 0;
		this.out.println("Spike S1: each repository read with the token in " + TOKEN_VARIABLE);
		for (String repository : repositories) {
			Probe probe = probe(repository, token.strip());
			this.out.println(probe.line());
			if (probe.status() != 200) {
				failures++;
			}
		}
		this.exitCode = failures == 0 ? 0 : 1;
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private Probe probe(String repository, String token) {
		HttpRequest request = HttpRequest.newBuilder(this.apiUrl.resolve("/repos/" + repository))
			.timeout(Duration.ofSeconds(20))
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("Authorization", "Bearer " + token)
			.GET()
			.build();
		try {
			HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return new Probe(repository, response.statusCode(), "-", "-");
			}
			JsonNode body = JSON.readTree(response.body());
			JsonNode admin = body.path("permissions").path("admin");
			return new Probe(repository, 200, body.path("owner").path("type").asString("?"),
					admin.isBoolean() ? Boolean.toString(admin.booleanValue()) : "absent");
		}
		catch (IOException ex) {
			return new Probe(repository, 0, "-", "unreachable (" + ex.getClass().getSimpleName() + ")");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Probe(repository, 0, "-", "interrupted");
		}
	}

}
