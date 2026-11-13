package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import vn.vibefix.support.IntegrationTest;

/**
 * Story 1.14's probe (S1, AD-13): it reads each repository with the GitHub token the person running it puts in the
 * process environment variable {@value SpikeS1Command#TOKEN_VARIABLE} (never a Spring property or argument), since
 * vibefix keeps none (R31), and prints what GitHub says about admin permission, never the token, and only on the dev
 * stack.
 */
class SpikeS1CommandIT extends IntegrationTest {

	private static final String TOKEN = String.join("", "gh", "o_", "x".repeat(8), "spike");

	@Autowired
	private AdministratorBootstrap bootstrap;

	private HttpServer github;

	private final List<String> authorizations = new CopyOnWriteArrayList<>();

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@BeforeEach
	void aStubGitHub() throws IOException {
		this.github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.github.createContext("/repos/", this::answer);
		this.github.start();
	}

	@AfterEach
	void stopGitHub() {
		this.github.stop(0);
	}

	@Test
	void printsTheAdminPermissionOfEachRepositoryAndNeverTheToken() {
		SpikeS1Command command = command(dev());

		command.run(new DefaultApplicationArguments("--repo=lan-owner/shop", "--repo=vibefix-dev/restricted",
				"--repo=vibefix-dev/member-admin"));

		String printed = this.out.toString(StandardCharsets.UTF_8);
		assertThat(printed).contains("lan-owner/shop").containsPattern("HTTP 200  owner=User  permissions.admin=true");
		assertThat(printed).containsPattern("vibefix-dev/restricted\\s+HTTP 200  owner=Organization  permissions.admin=absent");
		assertThat(printed).containsPattern("vibefix-dev/member-admin\\s+HTTP 200  owner=Organization  permissions.admin=false");
		assertThat(command.getExitCode()).isZero();
		assertThat(this.authorizations).hasSize(3).allMatch(("Bearer " + TOKEN)::equals);
		assertThat(printed + this.err.toString(StandardCharsets.UTF_8)).doesNotContain(TOKEN);
	}

	@Test
	void anErrorFromGitHubIsShownAndFailsTheRun() {
		SpikeS1Command command = command(dev());

		command.run(new DefaultApplicationArguments("--repo=vibefix-dev/gone"));

		assertThat(this.out.toString(StandardCharsets.UTF_8)).containsPattern("vibefix-dev/gone\\s+HTTP 404");
		assertThat(command.getExitCode()).isOne();
	}

	@Test
	void itRefusesProdAMissingTokenAndAWrongInvocation() {
		MockEnvironment prod = dev();
		prod.setActiveProfiles("prod", "cli");
		SpikeS1Command onProd = command(prod);
		onProd.run(new DefaultApplicationArguments("--repo=lan-owner/shop"));
		assertThat(onProd.getExitCode()).isOne();

		// Only the process's environment variable counts: a Spring property or argument of that name is no token.
		MockEnvironment asProperty = dev().withProperty(SpikeS1Command.TOKEN_VARIABLE, TOKEN);
		SpikeS1Command withoutToken = command(asProperty, name -> null);
		withoutToken.run(new DefaultApplicationArguments("--repo=lan-owner/shop", "--" + SpikeS1Command.TOKEN_VARIABLE + "=" + TOKEN));
		assertThat(withoutToken.getExitCode()).isOne();
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains(SpikeS1Command.TOKEN_VARIABLE);

		SpikeS1Command wrong = command(dev());
		wrong.run(new DefaultApplicationArguments("--repo=not a repository"));
		assertThat(wrong.getExitCode()).isEqualTo(2);
		assertThat(this.authorizations).isEmpty();
	}

	@Test
	void theBootstrapCommandLeavesTheSpikeAlone() {
		BootstrapCommand bootstrapCommand = new BootstrapCommand(this.bootstrap, dev(), () -> new char[0],
				new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));

		bootstrapCommand.run(new DefaultApplicationArguments("--repo=lan-owner/shop"));

		assertThat(bootstrapCommand.getExitCode()).isZero();
		assertThat(this.err.toString(StandardCharsets.UTF_8)).isEmpty();
	}

	/** The command, with {@value SpikeS1Command#TOKEN_VARIABLE} set in its process environment. */
	private SpikeS1Command command(Environment environment) {
		return command(environment, name -> SpikeS1Command.TOKEN_VARIABLE.equals(name) ? TOKEN : null);
	}

	private SpikeS1Command command(Environment environment, UnaryOperator<String> variables) {
		return new SpikeS1Command(environment, variables, URI.create("http://127.0.0.1:" + this.github.getAddress().getPort()),
				new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));
	}

	private static MockEnvironment dev() {
		MockEnvironment environment = new MockEnvironment().withProperty("vibefix.cli.command", SpikeS1Command.COMMAND);
		environment.setActiveProfiles("dev", "cli");
		return environment;
	}

	/** User-owned with admin; an organization's repository read through a restricted app (no permissions); a member's. */
	private void answer(HttpExchange exchange) throws IOException {
		this.authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
		String path = exchange.getRequestURI().getPath();
		String body = switch (path) {
			case "/repos/lan-owner/shop" -> "{\"owner\":{\"type\":\"User\"},\"permissions\":{\"admin\":true}}";
			case "/repos/vibefix-dev/restricted" -> "{\"owner\":{\"type\":\"Organization\"}}";
			case "/repos/vibefix-dev/member-admin" -> "{\"owner\":{\"type\":\"Organization\"},\"permissions\":{\"admin\":false}}";
			default -> null;
		};
		byte[] bytes = (body == null ? "{\"message\":\"Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
