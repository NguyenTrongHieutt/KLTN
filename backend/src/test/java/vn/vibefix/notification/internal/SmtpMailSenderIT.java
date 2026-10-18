package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AD-27: the SMTP adapter against a real SMTP server, Mailpit, the dev stack's (deploy/compose.yaml):
 * the Vietnamese message arrives as text and HTML, a refused recipient is final, and a server that
 * cannot be reached sent nothing.
 */
class SmtpMailSenderIT {

	/** The dev stack's image (deploy/compose.yaml). */
	static final String IMAGE = "axllent/mailpit:v1.31.3";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final GenericContainer<?> mailpit = new GenericContainer<>(DockerImageName.parse(IMAGE))
		.withExposedPorts(1025, 8025)
		// Only school addresses are accepted, so another recipient gets SMTP 550.
		.withEnv("MP_SMTP_ALLOWED_RECIPIENTS", "@truong\\.edu\\.vn$")
		.waitingFor(Wait.forHttp("/readyz").forPort(8025));

	private static final HttpClient http = HttpClient.newHttpClient();

	@BeforeAll
	static void start() {
		mailpit.start();
	}

	@AfterAll
	static void stop() {
		mailpit.stop();
	}

	@Test
	void aVietnameseMessageArrivesAsTextAndEscapedHtml() throws Exception {
		EmailTemplate template = EmailTemplate.parse("test.greeting", """
				subject: Mã xác minh vibefix của bạn: {code}
				link: /ho-so
				---
				Xin chào {name},

				Mã của bạn là {code}. Mã hết hạn sau 10 phút.
				""");

		sender(mailpit.getMappedPort(1025)).send("lan@truong.edu.vn",
				template.render(Map.of("name", "Nguyễn <b>Lan</b>", "code", "482915"), "http://localhost:5173"));

		JsonNode message = latestMessage();
		assertThat(message.path("Subject").asString()).isEqualTo("Mã xác minh vibefix của bạn: 482915");
		assertThat(message.path("To").get(0).path("Address").asString()).isEqualTo("lan@truong.edu.vn");
		assertThat(message.path("From").path("Address").asString()).isEqualTo("no-reply@vibefix.vn");
		assertThat(message.path("Text").asString()).contains("Xin chào Nguyễn <b>Lan</b>,")
			.contains("Mở vibefix: http://localhost:5173/ho-so");
		assertThat(message.path("HTML").asString()).contains("Xin chào Nguyễn &lt;b&gt;Lan&lt;/b&gt;,")
			.doesNotContain("<b>Lan</b>");
	}

	@Test
	void aRefusedRecipientIsRejected() {
		EmailTemplate template = EmailTemplate.parse("test.reminder", "subject: Nhắc việc\n---\nBạn còn một việc.");

		assertThatThrownBy(() -> sender(mailpit.getMappedPort(1025)).send("someone@example.com",
				template.render(Map.of(), "http://localhost:5173")))
			.isInstanceOf(MailSender.Rejected.class);
	}

	@Test
	void aServerThatCannotBeReachedSentNothing() throws IOException {
		int closed;
		try (ServerSocket socket = new ServerSocket(0)) {
			closed = socket.getLocalPort();
		}
		EmailTemplate template = EmailTemplate.parse("test.reminder", "subject: Nhắc việc\n---\nBạn còn một việc.");

		assertThatThrownBy(() -> sender(closed).send("lan@truong.edu.vn", template.render(Map.of(), "x")))
			.isInstanceOf(MailSender.Unavailable.class);
	}

	private static SmtpMailSender sender(int port) {
		return new SmtpMailSender(new EmailProperties.Smtp(mailpit.getHost(), port, false, Duration.ofSeconds(5),
				Duration.ofSeconds(10)), "vibefix <no-reply@vibefix.vn>", null, null);
	}

	private static JsonNode latestMessage() throws Exception {
		String base = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
		JsonNode list = get(base + "/api/v1/messages?limit=1");
		String id = list.path("messages").get(0).path("ID").asString();
		return get(base + "/api/v1/message/" + id);
	}

	private static JsonNode get(String url) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readTree(response.body());
	}

}
