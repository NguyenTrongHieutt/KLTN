package vn.vibefix.identity.internal;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spike S1's probe (Story 1.14, AD-13): under the {@code cli} profile with {@code dev},
 * {@code --vibefix.cli.command=spike-s1 --github-login=<Customer> --repo=<owner/name> [--repo=…]}
 * reads each repository from GitHub's REST API with that Customer's kept GitHub token, as the
 * Product intake's admin check will, and prints the owner type, the HTTP status, and
 * {@code permissions.admin}. The token is opened in memory only and never printed or logged.
 * <p>
 * It refuses to run under {@code prod}: it exists to answer S1 on the dev OAuth App and the
 * {@code vibefix-dev} organization. Exit code 0 when every repository was read, 1 when refused or
 * when GitHub answered an error for one, 2 for a wrong invocation.
 */
@Component
@Profile("cli")
class SpikeS1Command implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "spike-s1";

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** One repository as the Customer's token sees it. */
	record Probe(String repository, int status, String ownerType, String admin) {

		String line() {
			return "%-50s HTTP %d  owner=%s  permissions.admin=%s".formatted(this.repository, this.status, this.ownerType,
					this.admin);
		}

	}

	private final JdbcClient jdbc;

	private final Accounts accounts;

	private final TokenCipher cipher;

	private final Environment environment;

	private final URI apiUrl;

	private final HttpClient http;

	private final PrintStream out;

	private final PrintStream err;

	private volatile int exitCode;

	@Autowired
	SpikeS1Command(JdbcClient jdbc, Accounts accounts, TokenCipher cipher, Environment environment,
			@Value("${vibefix.github.api-url}") URI apiUrl) {
		this(jdbc, accounts, cipher, environment, apiUrl, System.out, System.err);
	}

	SpikeS1Command(JdbcClient jdbc, Accounts accounts, TokenCipher cipher, Environment environment, URI apiUrl,
			PrintStream out, PrintStream err) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.cipher = cipher;
		this.environment = environment;
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
		List<String> logins = args.getOptionValues("github-login");
		List<String> repositories = args.getOptionValues("repo");
		if (logins == null || logins.size() != 1 || repositories == null || repositories.isEmpty()
				|| !repositories.stream().allMatch(repository -> REPOSITORY.matcher(repository).matches())) {
			this.err.println("Usage: --spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND
					+ " --github-login=<Customer's GitHub username> --repo=<owner/name> [--repo=<owner/name> ...]");
			this.exitCode = 2;
			return;
		}
		if (this.environment.acceptsProfiles(Profiles.of("prod"))) {
			this.err.println("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		Optional<UUID> customer = customer(logins.getFirst());
		Optional<TokenCipher.Sealed> sealed = customer.flatMap(this.accounts::customerToken);
		if (sealed.isEmpty()) {
			this.err.println("Refused: no Customer " + logins.getFirst()
					+ " with a stored GitHub token; link GitHub from CUS-3 step 2 on the dev stack as that Customer first");
			this.exitCode = 1;
			return;
		}
		String token = this.cipher.open(sealed.get(), customer.get().toString());
		int failures = 0;
		this.out.println("Spike S1: each repository read with " + logins.getFirst() + "'s kept GitHub token");
		for (String repository : repositories) {
			Probe probe = probe(repository, token);
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

	private Optional<UUID> customer(String login) {
		return this.jdbc.sql("""
				SELECT id FROM identity.account
				WHERE lower(github_login) = lower(:login) AND role = 'CUSTOMER' AND erased_at IS NULL
				""").param("login", login).query(UUID.class).optional();
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
