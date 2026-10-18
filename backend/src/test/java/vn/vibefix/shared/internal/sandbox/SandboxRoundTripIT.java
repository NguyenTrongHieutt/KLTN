package vn.vibefix.shared.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.VibefixApplication;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobHandler;
import vn.vibefix.shared.SandboxJobResult;

/**
 * AD-32: the job channel. Starts the sandbox worker exactly as {@code main} does (both sources,
 * profile {@code sandbox}) on a random port, then sends {@code HEALTH} jobs through the
 * application's {@link HttpSandboxJobs} and {@link SandboxHealthIndicator}.
 */
class SandboxRoundTripIT {

	@TempDir
	static Path emptySecrets;

	private static ConfigurableApplicationContext worker;

	private static HttpSandboxJobs jobs;

	@BeforeAll
	static void startWorker() {
		worker = startWorker(emptySecrets);
		jobs = new HttpSandboxJobs(urlOf(worker), Duration.ofSeconds(10));
	}

	@AfterAll
	static void stopWorker() {
		worker.close();
	}

	@Test
	void healthJobEchoesTheNonceAndReportsAnIsolatedSandbox() {
		String nonce = UUID.randomUUID().toString();

		SandboxJobResult result = jobs.run(HealthJobHandler.TYPE, nonce(nonce));

		assertThat(result.type()).isEqualTo(HealthJobHandler.TYPE);
		JsonNode output = result.output();
		assertThat(output.path(HealthJobHandler.NONCE).asString()).isEqualTo(nonce);
		assertThat(output.path(HealthJobHandler.SECRET_FILES).asLong(-1)).isZero();
		assertThat(output.path(HealthJobHandler.DATASOURCE_CONFIGURED).isBoolean()).isTrue();
		assertThat(output.path(HealthJobHandler.DATASOURCE_CONFIGURED).booleanValue()).isFalse();
	}

	@Test
	void healthIndicatorIsUpAfterARoundTrip() {
		assertThat(new SandboxHealthIndicator(jobs).health().getStatus()).isEqualTo(Status.UP);
	}

	@Test
	void workerLoadsOnlyTheJobChannel() {
		assertThat(worker.getBeanNamesForType(DataSource.class)).isEmpty();
		assertThat(worker.getBeanNamesForType(Flyway.class)).isEmpty();
		assertThat(worker.getBeanNamesForType(EntityManagerFactory.class)).isEmpty();
		assertThat(worker.getBeanNamesForType(VibefixApplication.class)).isEmpty();
		assertThat(worker.getBeanNamesForType(HttpSandboxJobs.class)).isEmpty();
		assertThat(worker.containsBean("environmentGuard")).isFalse();
		assertThat(worker.containsBean("environmentController")).isFalse();
		assertThat(worker.getBeansOfType(SandboxJobHandler.class).values())
			.hasOnlyElementsOfType(HealthJobHandler.class)
			.hasSize(1);
	}

	@Test
	void unknownTypeAndInvalidInputAreRefused() {
		assertThatThrownBy(() -> jobs.run("NO_SUCH_JOB", nonce("x"))).isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("NO_SUCH_JOB")
			.hasMessageContaining("404");
		assertThatThrownBy(() -> jobs.run(HealthJobHandler.TYPE, JsonNodeFactory.instance.objectNode()))
			.isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("400");
	}

	@Test
	void unreachableSandboxFailsTheJobAndTheHealthIndicator() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		HttpSandboxJobs unreachable = new HttpSandboxJobs(URI.create("http://localhost:" + closedPort),
				Duration.ofSeconds(2));

		assertThatThrownBy(() -> unreachable.run(HealthJobHandler.TYPE, nonce("x")))
			.isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("could not be reached");
		assertThat(new SandboxHealthIndicator(unreachable).health().getStatus()).isEqualTo(Status.DOWN);
	}

	@Test
	void sandboxThatSeesSecretFilesIsDown(@TempDir Path secrets) throws Exception {
		Files.writeString(secrets.resolve("db-app-password"), "not-isolated");
		try (ConfigurableApplicationContext leaky = startWorker(secrets)) {
			HttpSandboxJobs leakyJobs = new HttpSandboxJobs(urlOf(leaky), Duration.ofSeconds(10));

			assertThat(leakyJobs.run(HealthJobHandler.TYPE, nonce("x")).output().path(HealthJobHandler.SECRET_FILES)
				.asLong()).isOne();
			assertThat(new SandboxHealthIndicator(leakyJobs).health().getStatus()).isEqualTo(Status.DOWN);
		}
	}

	private static ConfigurableApplicationContext startWorker(Path secrets) {
		return new SpringApplicationBuilder(VibefixApplication.class, SandboxWorkerConfiguration.class)
			.profiles(SandboxWorkerConfiguration.PROFILE)
			.run("--server.port=0", "--VIBEFIX_SECRETS_DIR=" + secrets.toAbsolutePath().toString().replace('\\', '/') + "/");
	}

	private static URI urlOf(ConfigurableApplicationContext context) {
		return URI.create("http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port"));
	}

	private static ObjectNode nonce(String nonce) {
		return JsonNodeFactory.instance.objectNode().put(HealthJobHandler.NONCE, nonce);
	}

}
