package vn.vibefix.shared.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.VibefixApplication;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobHandler;
import vn.vibefix.shared.SandboxJobResult;
import vn.vibefix.shared.internal.CorrelationIdFilter;

/**
 * AD-32: the job channel. Starts the sandbox worker exactly as {@code main} does (both sources,
 * profile {@code sandbox}) on a random port, then sends {@code HEALTH} jobs through the
 * application's {@link HttpSandboxJobs} and {@link SandboxHealthIndicator}.
 */
@ExtendWith(OutputCaptureExtension.class)
class SandboxRoundTripIT {

	private static final JsonMapper json = JsonMapper.builder().build();

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
		if (worker != null) {
			worker.close();
		}
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
		// HEALTH, files' image re-encoding (AD-21), scanning's history scan (AD-18), github's copy into the
		// Intermediary Repository (AD-11) and its final ZIP (FR-21), and delivery's preview check (AD-35); a test
		// handler on the classpath would show here.
		assertThat(worker.getBeansOfType(SandboxJobHandler.class).values()).extracting(SandboxJobHandler::type)
			.containsExactlyInAnyOrder(HealthJobHandler.TYPE, "IMAGE_REENCODE", "SECRET_SCAN", "REPOSITORY_COPY",
					"REPOSITORY_ZIP", "PREVIEW_CHECK");
	}

	@Test
	void workerReencodesAnImage() throws Exception {
		BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(image, "png", png);

		JsonNode output = jobs
			.run("IMAGE_REENCODE",
					JsonNodeFactory.instance.objectNode()
						.put("content", Base64.getEncoder().encodeToString(png.toByteArray()))
						.put("format", "png"))
			.output();

		assertThat(output.path("mediaType").asString()).isEqualTo("image/png");
		byte[] reencoded = Base64.getDecoder().decode(output.path("content").asString());
		BufferedImage read = ImageIO.read(new ByteArrayInputStream(reencoded));
		assertThat(read.getWidth()).isEqualTo(4);
		assertThat(read.getHeight()).isEqualTo(3);
	}

	@Test
	void unknownTypeAndInvalidInputAreRefused() {
		assertThatThrownBy(() -> jobs.run("NO_SUCH_JOB", nonce("x"))).isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("NO_SUCH_JOB")
			.hasMessageContaining("404");
		assertThatThrownBy(() -> jobs.run(HealthJobHandler.TYPE, JsonNodeFactory.instance.objectNode()))
			.isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("400");
		assertThatThrownBy(() -> jobs.run(" ", nonce("x"))).isInstanceOf(SandboxJobException.class)
			.hasMessageContaining("400");
	}

	@Test
	void workerLogsUnderTheCorrelationIdOfTheApplicationRequest(CapturedOutput output) {
		String correlationId = UUID.randomUUID().toString();
		MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
		try {
			// An empty input makes the worker log a warning before it answers 400.
			assertThatThrownBy(() -> jobs.run(HealthJobHandler.TYPE, JsonNodeFactory.instance.objectNode()))
				.isInstanceOf(SandboxJobException.class);
		}
		finally {
			MDC.remove(CorrelationIdFilter.MDC_KEY);
		}

		assertThat(refusalLines(output.getOut())).singleElement()
			.satisfies(line -> assertThat(json.readTree(line).path(CorrelationIdFilter.MDC_KEY).asString())
				.isEqualTo(correlationId));
	}

	@Test
	void workerReplacesACorrelationIdThatIsNotAUuid(CapturedOutput output) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(urlOf(worker).resolve(SandboxJobController.PATH))
			.header("Content-Type", "application/json")
			.header(CorrelationIdFilter.HEADER, "not-a-uuid")
			.POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"HEALTH\",\"input\":{}}"))
			.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
		}

		assertThat(refusalLines(output.getOut())).singleElement().satisfies(line -> {
			String logged = json.readTree(line).path(CorrelationIdFilter.MDC_KEY).asString();
			assertThat(UUID.fromString(logged).toString()).isEqualTo(logged).isNotEqualTo("not-a-uuid");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "dev", "prod" })
	void workerRefusesToRunBesideAnEnvironmentProfile(String environment) {
		assertThatThrownBy(() -> new SpringApplicationBuilder(VibefixApplication.class, SandboxWorkerConfiguration.class)
			.profiles(SandboxWorkerConfiguration.PROFILE, environment)
			.run("--server.port=0")).rootCause().hasMessageContaining("cannot be combined with dev or prod");
	}

	@Test
	void workerWithAJobTypeListServesTheListedTypes() {
		try (ConfigurableApplicationContext checker = startWorker(emptySecrets,
				"--" + SandboxJobController.JOB_TYPES_PROPERTY + "=" + HealthJobHandler.TYPE)) {
			HttpSandboxJobs checkerJobs = new HttpSandboxJobs(urlOf(checker), Duration.ofSeconds(10));

			assertThat(checkerJobs.run(HealthJobHandler.TYPE, nonce("listed")).output().path(HealthJobHandler.NONCE)
				.asString()).isEqualTo("listed");
			assertThatThrownBy(() -> checkerJobs.run("NO_SUCH_JOB", nonce("x"))).isInstanceOf(SandboxJobException.class)
				.hasMessageContaining("404");
		}
	}

	@Test
	void workerRefusesToStartWhenItListsAJobTypeWithoutAHandler() {
		assertThatThrownBy(() -> startWorker(emptySecrets,
				"--" + SandboxJobController.JOB_TYPES_PROPERTY + "=" + HealthJobHandler.TYPE + ",URL_CHECK"))
			.rootCause()
			.hasMessageContaining("URL_CHECK");
	}

	@Test
	void workerRefusesToStartWhenItsJobTypeListIsSetButEmpty() {
		assertThatThrownBy(() -> startWorker(emptySecrets, "--" + SandboxJobController.JOB_TYPES_PROPERTY + "="))
			.rootCause()
			.hasMessageContaining("lists no job type");
	}

	@Test
	void slowSandboxFailsTheJobAfterTheTimeout() throws Exception {
		// A socket that accepts connections (through its backlog) but never answers.
		try (ServerSocket silent = new ServerSocket(0)) {
			HttpSandboxJobs slow = new HttpSandboxJobs(URI.create("http://localhost:" + silent.getLocalPort()),
					Duration.ofMillis(500));

			assertTimeoutPreemptively(Duration.ofSeconds(5),
					() -> assertThatThrownBy(() -> slow.run(HealthJobHandler.TYPE, nonce("x")))
						.isInstanceOf(SandboxJobException.class)
						.hasMessageContaining("could not be reached in time"));
		}
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

	private static ConfigurableApplicationContext startWorker(Path secrets, String... args) {
		List<String> allArgs = new ArrayList<>(List.of("--server.port=0",
				"--VIBEFIX_SECRETS_DIR=" + secrets.toAbsolutePath().toString().replace('\\', '/') + "/"));
		allArgs.addAll(List.of(args));
		return new SpringApplicationBuilder(VibefixApplication.class, SandboxWorkerConfiguration.class)
			.profiles(SandboxWorkerConfiguration.PROFILE)
			.run(allArgs.toArray(String[]::new));
	}

	private static List<String> refusalLines(String output) {
		return output.lines().filter(line -> line.contains("refused its input")).toList();
	}

	private static URI urlOf(ConfigurableApplicationContext context) {
		return URI.create("http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port"));
	}

	private static ObjectNode nonce(String nonce) {
		return JsonNodeFactory.instance.objectNode().put(HealthJobHandler.NONCE, nonce);
	}

}
