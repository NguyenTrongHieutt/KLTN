package vn.vibefix.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.delivery.internal.PreviewCheckJobHandler.Answer;

/**
 * AD-32, AD-35: the preview check accepts only {@code https} to public addresses, connects to the
 * address it resolved, re-checks every redirect, and reads a status, never a body. The resolver and the
 * probe are fakes, so no test reaches the network.
 */
class PreviewCheckJobHandlerTest {

	private final Map<String, String> dns = new HashMap<>();

	private final Map<String, Answer> answers = new HashMap<>();

	private final List<String> probed = new ArrayList<>();

	private final PreviewCheckJobHandler handler = new PreviewCheckJobHandler(this::resolve, (address, host, target) -> {
		this.probed.add(address.getHostAddress() + " " + host + " " + target);
		Answer answer = this.answers.get(host + target);
		if (answer == null) {
			throw new IOException("timed out");
		}
		return answer;
	});

	@Test
	void aPublicHttpsPreviewThatAnswersResponds() {
		this.dns.put("so-dat-ban.vercel.app", "76.76.21.21");
		this.answers.put("so-dat-ban.vercel.app/gd1?x=1", new Answer(200, null));

		JsonNode result = check("https://so-dat-ban.vercel.app/gd1?x=1");

		assertThat(result.path("reachable").asBoolean()).isTrue();
		assertThat(result.path("status").asInt()).isEqualTo(200);
		assertThat(this.probed).containsExactly("76.76.21.21 so-dat-ban.vercel.app /gd1?x=1");
	}

	@Test
	void aProtectedPreviewRespondsAndAMissingPageDoesNot() {
		this.dns.put("a.example.com", "93.184.216.34");
		this.answers.put("a.example.com/", new Answer(401, null));
		this.dns.put("b.example.com", "93.184.216.35");
		this.answers.put("b.example.com/", new Answer(404, null));

		assertThat(check("https://a.example.com").path("reachable").asBoolean()).isTrue();
		assertThat(check("https://b.example.com").path("reason").asString()).isEqualTo(PreviewCheckJobHandler.ERROR_STATUS);
	}

	@Test
	void onlyHttpsOnPort443IsChecked() {
		assertThat(check("http://so-dat-ban.vercel.app").path("reason").asString()).isEqualTo(PreviewCheckJobHandler.NOT_HTTPS);
		assertThat(check("https://so-dat-ban.vercel.app:8443").path("reason").asString())
			.isEqualTo(PreviewCheckJobHandler.NOT_HTTPS);
		assertThat(check("https://user:pw@so-dat-ban.vercel.app").path("reason").asString())
			.isEqualTo(PreviewCheckJobHandler.NOT_HTTPS);
		assertThat(this.probed).isEmpty();
	}

	@Test
	void aHostWithAnyNonPublicAddressIsRefusedWithoutAConnection() {
		for (String address : List.of("10.0.0.5", "127.0.0.1", "169.254.169.254", "172.20.1.1", "192.168.1.10", "100.64.0.1",
				"0.0.0.0", "::1", "fd00::1", "fe80::1")) {
			this.dns.put("inside.example.com", address);
			assertThat(check("https://inside.example.com").path("reason").asString()).as(address)
				.isEqualTo(PreviewCheckJobHandler.PRIVATE_ADDRESS);
		}
		assertThat(check("https://unknown.example.com").path("reason").asString()).isEqualTo(PreviewCheckJobHandler.UNKNOWN_HOST);
		assertThat(this.probed).isEmpty();
	}

	@Test
	void redirectsAreFollowedOnlyToPublicHttpsTargets() {
		this.dns.put("start.example.com", "93.184.216.34");
		this.dns.put("next.example.com", "93.184.216.35");
		this.dns.put("metadata.example.com", "169.254.169.254");
		this.answers.put("start.example.com/", new Answer(302, "https://next.example.com/app"));
		this.answers.put("next.example.com/app", new Answer(200, null));
		assertThat(check("https://start.example.com").path("reachable").asBoolean()).isTrue();

		this.answers.put("start.example.com/", new Answer(301, "http://next.example.com/app"));
		assertThat(check("https://start.example.com").path("reason").asString()).isEqualTo(PreviewCheckJobHandler.REDIRECT_DISALLOWED);

		this.answers.put("start.example.com/", new Answer(307, "https://metadata.example.com/latest"));
		assertThat(check("https://start.example.com").path("reason").asString()).isEqualTo(PreviewCheckJobHandler.REDIRECT_DISALLOWED);

		this.answers.put("start.example.com/", new Answer(302, "/"));
		assertThat(check("https://start.example.com").path("reason").asString()).isEqualTo(PreviewCheckJobHandler.TOO_MANY_REDIRECTS);
	}

	@Test
	void aPreviewThatDoesNotAnswerDoesNotRespond() {
		this.dns.put("slow.example.com", "93.184.216.34");

		assertThat(check("https://slow.example.com").path("reason").asString()).isEqualTo(PreviewCheckJobHandler.NO_RESPONSE);
	}

	private JsonNode check(String url) {
		return this.handler.handle(JsonNodeFactory.instance.objectNode().put("url", url));
	}

	private InetAddress[] resolve(String host) throws UnknownHostException {
		String address = this.dns.get(host);
		if (address == null) {
			throw new UnknownHostException(host);
		}
		return new InetAddress[] { InetAddress.getByName(address) };
	}

}
