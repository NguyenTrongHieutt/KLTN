package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

/**
 * Story 19.5's probe (S5, AD-22, AD-42) against a local stub of LinkedIn's token endpoint,
 * {@code identityMe} and {@code verificationReport}: every row of the spec's I/O matrix. It needs no
 * database, because the probe has none. After every test, no output line holds the token, the code,
 * the client secret, or a personal value the stub answered.
 */
class SpikeS5CommandIT {

	private static final String CLIENT_ID = "s5-dev-" + "client";

	// Built at run time, so no secret scanner mistakes a test value for a credential.
	private static final String CLIENT_SECRET = "s5-test-" + "client-" + "material";

	private static final String CODE = "AQ" + "s5-authorization-" + "code";

	private static final String ACCESS_TOKEN = "AQV" + "s5-access-" + "x".repeat(16);

	private static final String REFRESH_TOKEN = "AQX" + "s5-refresh-" + "y".repeat(16);

	private static final String STATE = "s5-state-" + "q1w2e3";

	/** The callback's query as the author pastes it, here without the address before it. */
	private static final String QUERY = "--query=code=" + CODE + "&state=" + STATE;

	private static final String REDIRECT = "http://localhost:5173/link/oauth2/code/linkedin";

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"), ZoneOffset.UTC);

	private static final long PICTURE_EXPIRES_AT = Instant.parse("2026-10-28T03:00:00Z").toEpochMilli();

	private static final String MEMBER_ID = "Ab1Cd2Ef3G";

	private static final String FIRST_NAME = "Hiếu";

	/** Upper case, so only the personal-field rule keeps it masked. */
	private static final String LAST_NAME = "NGUYEN";

	private static final String PROFILE_URL = "https://www.linkedin.com/in/hieu-nguyen-s5";

	private static final String PICTURE_URL = "https://media.licdn.com/dms/image/v2/s5-picture"
			+ "?e=1761620400&t=signature";

	private static final String IDENTITY_ME = """
			{"id":"%s","basicInfo":{"firstName":"%s","lastName":"%s","profileUrl":"%s",
			"profilePicture":{"croppedImage":{"downloadUrl":"%s","downloadUrlExpiresAt":%d}}}}
			""".formatted(MEMBER_ID, FIRST_NAME, LAST_NAME, PROFILE_URL, PICTURE_URL, PICTURE_EXPIRES_AT);

	private static final String VERIFICATION_REPORT = """
			{"verifications":["IDENTITY","WORKPLACE"],"verifiedAt":1761400000000}
			""";

	private static final String TOKEN = """
			{"access_token":"%s","expires_in":5183999,"refresh_token":"%s","refresh_token_expires_in":31535999,
			"scope":"r_profile_basicinfo,r_verify","token_type":"Bearer"}
			""".formatted(ACCESS_TOKEN, REFRESH_TOKEN);

	/** One request the stub received. */
	record Received(String method, String path, String authorization, String version, String contentType, String body) {
	}

	private HttpServer linkedIn;

	private final List<Received> received = new CopyOnWriteArrayList<>();

	private final AtomicInteger tokenStatus = new AtomicInteger(200);

	private final AtomicReference<String> tokenBody = new AtomicReference<>(TOKEN);

	private final AtomicInteger identityStatus = new AtomicInteger(200);

	private final AtomicReference<String> identityBody = new AtomicReference<>(IDENTITY_ME);

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@BeforeEach
	void startStub() throws IOException {
		this.linkedIn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.linkedIn.createContext("/", this::answer);
		this.linkedIn.start();
	}

	@AfterEach
	void noOutputLineLeaksASecretOrAPersonalValue() {
		this.linkedIn.stop(0);
		String printed = output();
		for (String secret : List.of(CLIENT_SECRET, CODE, ACCESS_TOKEN, REFRESH_TOKEN, MEMBER_ID, FIRST_NAME, LAST_NAME,
				PROFILE_URL, "hieu-nguyen", "media.licdn.com", "signature")) {
			assertThat(printed).as("printed: %s", secret).doesNotContain(secret);
		}
	}

	@Test
	void authorizePrintsTheConsentUrlWithBothScopesAndItsState() {
		assertThat(run(environment(), "--step=authorize")).isZero();

		String url = this.out.toString(StandardCharsets.UTF_8)
			.lines()
			.filter(line -> line.startsWith(base() + "/oauth/v2/authorization?"))
			.findFirst()
			.orElseThrow();
		Map<String, String> params = SpikeS5Command.parse(url);
		assertThat(params).containsEntry("response_type", "code")
			.containsEntry("client_id", CLIENT_ID)
			.containsEntry("redirect_uri", REDIRECT)
			.containsEntry("scope", "r_profile_basicinfo r_verify");
		assertThat(url).contains("scope=r_profile_basicinfo%20r_verify");
		String state = params.get("state");
		assertThat(state).hasSizeGreaterThanOrEqualTo(32);
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("state=" + state)
			.contains("--step=exchange --state=" + state);
		assertThat(this.received).as("authorize calls nothing").isEmpty();

		this.out.reset();
		assertThat(run(environment(), "--step=authorize")).isZero();
		assertThat(this.out.toString(StandardCharsets.UTF_8)).as("a new state each time").doesNotContain(state);
	}

	@Test
	void noSecretFileOrAMissingSettingShowsTheUsage() {
		MockEnvironment noSecrets = environment();
		noSecrets.setProperty(SpikeS5Command.CLIENT_ID, "");
		noSecrets.setProperty(SpikeS5Command.CLIENT_SECRET, " ");
		assertThat(run(noSecrets, "--step=authorize")).isEqualTo(2);
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("the secret file linkedin-client-id is missing")
			.contains("Usage:");

		this.err.reset();
		MockEnvironment noClientSecret = environment();
		noClientSecret.setProperty(SpikeS5Command.CLIENT_SECRET, "");
		assertThat(exchange(noClientSecret)).isEqualTo(2);
		assertThat(this.err.toString(StandardCharsets.UTF_8))
			.contains("the secret file linkedin-client-secret is missing")
			.contains("Usage:");

		this.err.reset();
		MockEnvironment noTokenUri = environment();
		noTokenUri.setProperty("vibefix.linkedin.token-uri", "");
		assertThat(run(noTokenUri, "--step=authorize")).isEqualTo(2);
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("vibefix.linkedin.token-uri is not set");

		MockEnvironment noVersion = environment();
		noVersion.setProperty("vibefix.linkedin.api-version", "");
		assertThat(run(noVersion, "--step=authorize")).isEqualTo(2);
		assertThat(this.received).isEmpty();
		assertThat(this.out.toString(StandardCharsets.UTF_8)).isEmpty();
	}

	@Test
	void exchangeTradesTheCodeAndReadsBothApisWithTheToken() {
		assertThat(exchange(environment())).isZero();

		assertThat(this.received).extracting(Received::path)
			.containsExactly("/oauth/v2/accessToken", "/rest/identityMe", "/rest/verificationReport");
		Received token = this.received.getFirst();
		assertThat(token.method()).isEqualTo("POST");
		assertThat(token.contentType()).isEqualTo("application/x-www-form-urlencoded");
		assertThat(SpikeS5Command.parse(token.body())).containsEntry("grant_type", "authorization_code")
			.containsEntry("code", CODE)
			.containsEntry("client_id", CLIENT_ID)
			.containsEntry("client_secret", CLIENT_SECRET)
			.containsEntry("redirect_uri", REDIRECT);
		assertThat(token.authorization()).isNull();
		for (Received read : this.received.subList(1, 3)) {
			assertThat(read.method()).isEqualTo("GET");
			assertThat(read.authorization()).isEqualTo("Bearer " + ACCESS_TOKEN);
			assertThat(read.version()).isEqualTo("202510");
		}

		String printed = this.out.toString(StandardCharsets.UTF_8);
		assertThat(printed).contains("Spike S5 token: HTTP 200")
			.contains("access_token: <redacted>")
			.contains("refresh_token: <redacted>")
			.contains("expires_in=5183999 s (60.0 days)")
			.contains("refresh_token_expires_in=31535999 s (365.0 days)")
			.contains("Spike S5 identityMe: HTTP 200")
			.contains("id: string(10) \"Xx9Xx9Xx9X\"")
			.contains("basicInfo.firstName: string(4) \"Xxxx\"")
			.as("an upper-case name is still masked")
			.contains("basicInfo.lastName: string(6) \"XXXXXX\"")
			.contains("basicInfo.profileUrl: string(42) \"xxxxx://xxx.xxxxxxxx.xxx/xx/xxxx-xxxxxx-x9\"")
			.contains("basicInfo.profilePicture.croppedImage.downloadUrl: string(")
			.contains("basicInfo.profilePicture.croppedImage.downloadUrlExpiresAt: " + PICTURE_EXPIRES_AT)
			.contains("Spike S5 verificationReport: HTTP 200")
			.contains("verifications[0]: \"IDENTITY\"")
			.contains("verifications[1]: \"WORKPLACE\"")
			.contains("verifiedAt: 1761400000000")
			.contains("Spike S5 downloadUrlExpiresAt: " + PICTURE_EXPIRES_AT + " = 2026-10-28T03:00:00Z, in 48.0 h");
		assertThat(this.err.toString(StandardCharsets.UTF_8)).isEmpty();
	}

	@Test
	void theWholeAddressTheBrowserLandsOnIsAccepted() {
		String address = REDIRECT + "?code=" + CODE + "&state=" + STATE;

		assertThat(run(environment(), "--step=exchange", "--state=" + STATE, "--query=" + address)).isZero();
		assertThat(SpikeS5Command.parse(this.received.getFirst().body())).containsEntry("code", CODE);
	}

	@Test
	void aStateThatDiffersMakesNoTokenRequest() {
		assertThat(run(environment(), "--step=exchange", "--state=" + STATE,
				"--query=" + REDIRECT + "?code=" + CODE + "&state=another-state")).isOne();
		assertThat(run(environment(), "--step=exchange", "--state=" + STATE, "--query=code=" + CODE)).isOne();

		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("does not match --state");
		assertThat(this.received).isEmpty();
	}

	@Test
	void aRefusedTokenPrintsTheStatusAndTheErrorCode() {
		this.tokenStatus.set(400);
		this.tokenBody.set("""
				{"error":"invalid_grant",
				"error_description":"The provided authorization grant is invalid, expired or revoked"}
				""");

		assertThat(exchange(environment())).isOne();

		assertThat(this.out.toString(StandardCharsets.UTF_8))
			.contains("Spike S5 token: HTTP 400, refused with error=invalid_grant")
			.doesNotContain("authorization grant");
		assertThat(this.received).as("no API call without a token").hasSize(1);
	}

	@Test
	void aRefusedReadPrintsItsCodeAndTheOtherReadStillRuns() {
		this.identityStatus.set(403);
		this.identityBody.set("""
				{"status":403,"serviceErrorCode":100,"code":"ACCESS_DENIED",
				"message":"Not enough permissions to access: identityMe.GET"}
				""");

		assertThat(exchange(environment())).isOne();

		assertThat(this.out.toString(StandardCharsets.UTF_8))
			.contains("Spike S5 identityMe: HTTP 403, refused with code=ACCESS_DENIED serviceErrorCode=100")
			.contains("Spike S5 verificationReport: HTTP 200")
			.contains("Spike S5 downloadUrlExpiresAt: absent")
			.doesNotContain("Not enough permissions");
		assertThat(this.received).hasSize(3);
	}

	@Test
	void aConsentTheMemberCancelledMakesNoTokenRequest() {
		String cancelled = REDIRECT + "?error=user_cancelled_authorize"
				+ "&error_description=The+user+cancelled+the+authorization&state=" + STATE;

		assertThat(run(environment(), "--step=exchange", "--state=" + STATE, "--query=" + cancelled)).isOne();

		assertThat(this.out.toString(StandardCharsets.UTF_8))
			.contains("Spike S5 consent: LinkedIn sent no code, error=user_cancelled_authorize")
			.doesNotContain("cancelled the authorization");
		assertThat(this.received).isEmpty();
	}

	@Test
	void noAnswerFailsTheRun() {
		MockEnvironment environment = environment();
		this.linkedIn.stop(0);

		assertThat(exchange(environment)).isOne();
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("Spike S5 token: no answer (");
	}

	@Test
	void noOutputLineHoldsTheTokenTheCodeOrTheClientSecretWhateverTheStep() {
		run(environment(), "--step=authorize");
		exchange(environment());
		// A token that looks like an enum, echoed back in a field the masking would show as it is.
		String enumLikeToken = "AQV" + "S5ACCESS" + "TOKEN9";
		this.tokenBody.set("{\"access_token\":\"" + enumLikeToken + "\",\"expires_in\":60}");
		this.identityBody.set("{\"status\":\"" + enumLikeToken + "\",\"note\":\"" + CLIENT_SECRET + " " + CODE + "\"}");
		exchange(environment());
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("status: \"<redacted>\"");
		this.tokenStatus.set(401);
		this.tokenBody.set("{\"error\":\"invalid_client\",\"error_description\":\"" + CLIENT_SECRET + "\"}");
		exchange(environment());

		assertThat(output()).contains("Spike S5 token: HTTP 401, refused with error=invalid_client");
		for (String line : output().lines().toList()) {
			assertThat(line).doesNotContain(ACCESS_TOKEN, REFRESH_TOKEN, enumLikeToken, CODE, CLIENT_SECRET);
		}
	}

	@Test
	void itRefusesProdAndAWrongInvocationAndLeavesOtherCommandsAlone() {
		MockEnvironment prod = environment();
		prod.setActiveProfiles("prod", "cli");
		assertThat(run(prod, "--step=authorize")).isOne();
		MockEnvironment cliOnly = environment();
		cliOnly.setActiveProfiles("cli");
		assertThat(run(cliOnly, "--step=authorize")).isOne();
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("runs on the dev stack only");

		assertThat(run(environment())).isEqualTo(2);
		assertThat(run(environment(), "--step=refresh")).isEqualTo(2);
		assertThat(run(environment(), "--step=exchange", QUERY)).isEqualTo(2);
		assertThat(run(environment(), "--step=exchange", "--state=" + STATE)).isEqualTo(2);
		assertThat(run(environment(), "--step=exchange", "--state=" + STATE, "--query=state=" + STATE)).isEqualTo(2);

		this.err.reset();
		MockEnvironment another = environment();
		another.setProperty("vibefix.cli.command", "create-first-administrator");
		assertThat(run(another, "--step=authorize")).isZero();
		assertThat(output()).isEmpty();
		assertThat(this.received).isEmpty();
	}

	@Test
	void theBootstrapCommandLeavesTheProbeAlone() {
		// The bootstrap returns before it would use AdministratorBootstrap, so none is needed here.
		BootstrapCommand bootstrap = new BootstrapCommand(null, environment(), () -> new char[0], printing(this.out),
				printing(this.err));

		bootstrap.run(new DefaultApplicationArguments("--step=authorize"));

		assertThat(bootstrap.getExitCode()).isZero();
		assertThat(output()).isEmpty();
	}

	private int run(MockEnvironment environment, String... args) {
		SpikeS5Command command = new SpikeS5Command(environment, CLOCK, printing(this.out), printing(this.err));
		command.run(new DefaultApplicationArguments(args));
		return command.getExitCode();
	}

	/** The exchange step with the state that matches the callback's query. */
	private int exchange(MockEnvironment environment) {
		return run(environment, "--step=exchange", "--state=" + STATE, QUERY);
	}

	private static PrintStream printing(ByteArrayOutputStream stream) {
		return new PrintStream(stream, true, StandardCharsets.UTF_8);
	}

	/** The dev stack's cli run with both secret files and every setting pointed at the stub. */
	private MockEnvironment environment() {
		MockEnvironment environment = new MockEnvironment().withProperty("vibefix.cli.command", SpikeS5Command.COMMAND)
			.withProperty("vibefix.linkedin.authorization-uri", base() + "/oauth/v2/authorization")
			.withProperty("vibefix.linkedin.token-uri", base() + "/oauth/v2/accessToken")
			.withProperty("vibefix.linkedin.identity-me-uri", base() + "/rest/identityMe")
			.withProperty("vibefix.linkedin.verification-report-uri", base() + "/rest/verificationReport")
			.withProperty("vibefix.linkedin.api-version", "202510")
			.withProperty("vibefix.linkedin.redirect-uri", REDIRECT)
			.withProperty(SpikeS5Command.CLIENT_ID, CLIENT_ID)
			.withProperty(SpikeS5Command.CLIENT_SECRET, CLIENT_SECRET + "\n");
		environment.setActiveProfiles("dev", "cli");
		return environment;
	}

	private String base() {
		return "http://127.0.0.1:" + this.linkedIn.getAddress().getPort();
	}

	private String output() {
		return this.out.toString(StandardCharsets.UTF_8) + this.err.toString(StandardCharsets.UTF_8);
	}

	private void answer(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String path = exchange.getRequestURI().getPath();
		Headers headers = exchange.getRequestHeaders();
		this.received.add(new Received(exchange.getRequestMethod(), path, headers.getFirst("Authorization"),
				headers.getFirst("LinkedIn-Version"), headers.getFirst("Content-Type"), body));
		int status;
		String answer;
		switch (path) {
			case "/oauth/v2/accessToken" -> {
				status = this.tokenStatus.get();
				answer = this.tokenBody.get();
			}
			case "/rest/identityMe" -> {
				status = this.identityStatus.get();
				answer = this.identityBody.get();
			}
			case "/rest/verificationReport" -> {
				status = 200;
				answer = VERIFICATION_REPORT;
			}
			default -> {
				status = 404;
				answer = "{\"status\":404,\"code\":\"NOT_FOUND\"}";
			}
		}
		byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream stream = exchange.getResponseBody()) {
			stream.write(bytes);
		}
	}

}
