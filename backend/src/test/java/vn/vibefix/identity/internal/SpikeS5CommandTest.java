package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * {@code identityMe} and {@code verificationReport}: every row of the spec's I/O matrix, and the
 * masking. It needs no database, because the probe has none. After every test, no output line holds
 * the token, the code, the client secret, or a personal value the stub answered.
 */
class SpikeS5CommandTest {

	private static final String CLIENT_ID = "s5-dev-" + "client";

	// Built at run time, so no secret scanner mistakes a test value for a credential.
	private static final String CLIENT_SECRET = "s5-test-" + "client-" + "material";

	/** A client secret an OAuth error code could look like. */
	private static final String SNAKE_CLIENT_SECRET = "s5_test_" + "client_" + "material";

	private static final String CODE = "AQ" + "s5-authorization-" + "code";

	/** A code an enum-like value could look like. */
	private static final String ENUM_CODE = "AQS5" + "ENUM" + "CODE9";

	private static final String ACCESS_TOKEN = "AQV" + "s5-access-" + "x".repeat(16);

	private static final String REFRESH_TOKEN = "AQX" + "s5-refresh-" + "y".repeat(16);

	private static final String ID_TOKEN = "eyJ" + "s5-id-" + "z".repeat(16);

	/** A token an enum-like value could look like. */
	private static final String ENUM_TOKEN = "AQV" + "S5ACCESS" + "TOKEN9";

	private static final String STATE = "s5-state-" + "q1w2e3";

	/** The callback's query as the author pastes it, here without the address before it. */
	private static final String QUERY = "--query=code=" + CODE + "&state=" + STATE;

	private static final String REDIRECT = "http://localhost:5173/spike-s5/linkedin";

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"), ZoneOffset.UTC);

	private static final long PICTURE_EXPIRES_AT = Instant.parse("2026-10-28T03:00:00Z").toEpochMilli();

	private static final String MEMBER_ID = "Ab1Cd2Ef3G";

	private static final long MEMBER_NUMBER = 918_273_645L;

	/** Upper case, so only the personal-field rule keeps it masked. */
	private static final String SUB = "ABCDEF123";

	/** A key that names a member. */
	private static final String URN = "urn:li:person:" + MEMBER_ID;

	private static final String FIRST_NAME = "Hiếu";

	/** Upper case, below a personal field: only the inherited rule keeps it masked. */
	private static final String LAST_NAME = "NGUYEN";

	private static final String PROFILE_URL = "https://www.linkedin.com/in/hieu-nguyen-s5";

	private static final String PICTURE_URL = "https://media.licdn.com/dms/image/v2/s5-picture"
			+ "?e=1761620400&v=beta&t=signature";

	private static final String IDENTITY_ME = """
			{"id":"%s","memberId":%d,"sub":"%s","locale":"VI_VN",
			"basicInfo":{"firstName":"%s","lastName":{"localized":{"en_US":"%s"}},"profileUrl":"%s",
			"profilePicture":{"croppedImage":{"downloadUrl":"%s","downloadUrlExpiresAt":%d}}},
			"memberships":{"%s":"MEMBER"}}
			""".formatted(MEMBER_ID, MEMBER_NUMBER, SUB, FIRST_NAME, LAST_NAME, PROFILE_URL, PICTURE_URL,
			PICTURE_EXPIRES_AT, URN);

	private static final String VERIFICATION_REPORT = """
			{"verifications":["IDENTITY","WORKPLACE"],"verifiedAt":1761400000000,
			"details":[{"verificationType":"IDENTITY","provider":"PERSONA"}]}
			""";

	private static final String TOKEN = """
			{"access_token":"%s","expires_in":5183999,"refresh_token":"%s","refresh_token_expires_in":31535999,
			"id_token":"%s","scope":"r_profile_basicinfo,r_verify","token_type":"Bearer"}
			""".formatted(ACCESS_TOKEN, REFRESH_TOKEN, ID_TOKEN);

	/** One request the stub received. */
	record Received(String method, String path, String authorization, String version, String contentType, String body) {
	}

	private HttpServer linkedIn;

	private final ExecutorService handlers = Executors.newCachedThreadPool();

	private final List<Received> received = new CopyOnWriteArrayList<>();

	private final AtomicInteger tokenStatus = new AtomicInteger(200);

	private final AtomicReference<String> tokenBody = new AtomicReference<>(TOKEN);

	/** When set, the token endpoint sends its headers and the start of its body, then stalls. */
	private final AtomicBoolean tokenStalls = new AtomicBoolean();

	private final CountDownLatch release = new CountDownLatch(1);

	private final AtomicInteger identityStatus = new AtomicInteger(200);

	private final AtomicReference<String> identityBody = new AtomicReference<>(IDENTITY_ME);

	private final AtomicReference<String> reportBody = new AtomicReference<>(VERIFICATION_REPORT);

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@BeforeEach
	void startStub() throws IOException {
		this.linkedIn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.linkedIn.createContext("/", this::answer);
		this.linkedIn.setExecutor(this.handlers);
		this.linkedIn.start();
	}

	@AfterEach
	void noOutputLineLeaksASecretOrAPersonalValue() {
		this.release.countDown();
		this.linkedIn.stop(0);
		this.handlers.shutdownNow();
		String printed = output();
		for (String secret : List.of(CLIENT_SECRET, SNAKE_CLIENT_SECRET, CODE, ENUM_CODE, ACCESS_TOKEN, REFRESH_TOKEN,
				ID_TOKEN, ENUM_TOKEN, MEMBER_ID, String.valueOf(MEMBER_NUMBER), SUB, URN, FIRST_NAME, LAST_NAME,
				PROFILE_URL, "hieu-nguyen", "media.licdn.com", "1761620400", "beta", "signature", "VI_VN", "PERSONA")) {
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

		this.err.reset();
		MockEnvironment noVersion = environment();
		noVersion.setProperty("vibefix.linkedin.api-version", "");
		assertThat(run(noVersion, "--step=authorize")).isEqualTo(2);
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("vibefix.linkedin.api-version is not set")
			.contains("Usage:");
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
			assertThat(read.version()).isEqualTo("202609");
		}

		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("Spike S5 token: HTTP 200")
			.contains("access_token: <redacted>")
			.contains("refresh_token: <redacted>")
			.contains("id_token: <redacted>")
			.contains("scope: \"r_profile_basicinfo,r_verify\"")
			.contains("expires_in=5183999 s (60.0 days)")
			.contains("refresh_token_expires_in=31535999 s (365.0 days)")
			.contains("Spike S5 identityMe: HTTP 200")
			.contains("  id: string(10) \"Xx9Xx9Xx9X\"")
			.contains("memberId: number(9)")
			.contains("sub: string(9) \"XXXXXX999\"")
			.as("an upper-case value outside the listed fields is masked")
			.contains("locale: string(5) \"XX_XX\"")
			.contains("basicInfo.firstName: string(4) \"Xxxx\"")
			.as("a value below a personal field is masked, even upper-case")
			.contains("basicInfo.lastName.localized.en_US: string(6) \"XXXXXX\"")
			.contains("basicInfo.profileUrl: url(42) \"xxxxx://xxx.xxxxxxxx.xxx\" path \"/xx/xxxx-xxxxxx-x9\"")
			.contains("basicInfo.profilePicture.croppedImage.downloadUrl: url(" + PICTURE_URL.length()
					+ ") \"xxxxx://xxxxx.xxxxx.xxx\" path \"/xxx/xxxxx/x9/x9-xxxxxxx\" query [e, v, t]")
			.contains("basicInfo.profilePicture.croppedImage.downloadUrlExpiresAt: " + PICTURE_EXPIRES_AT)
			.as("a key that is a URN is masked, and so is its value")
			.contains("memberships.key(" + URN.length() + "): string(6) \"XXXXXX\"")
			.contains("Spike S5 verificationReport: HTTP 200")
			.contains("verifications[0]: \"IDENTITY\"")
			.contains("verifications[1]: \"WORKPLACE\"")
			.contains("verifiedAt: 1761400000000")
			.contains("details[0].verificationType: \"IDENTITY\"")
			.contains("details[0].provider: string(7) \"XXXXXXX\"")
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
	void aMalformedQueryShowsTheUsage() {
		assertThat(run(environment(), "--step=exchange", "--state=" + STATE, "--query=code=%zz&state=" + STATE))
			.isEqualTo(2);

		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("--query is not a URL query").contains("Usage:");
		assertThat(this.received).isEmpty();
	}

	@Test
	void aRefusedTokenPrintsTheStatusTheErrorCodeAndItsDescription() {
		this.tokenStatus.set(400);
		this.tokenBody.set("""
				{"error":"invalid_grant",
				"error_description":"The provided authorization grant is invalid, expired or revoked"}
				""");

		assertThat(exchange(environment())).isOne();

		assertThat(this.out.toString(StandardCharsets.UTF_8))
			.contains("Spike S5 token: HTTP 400, refused with error=invalid_grant")
			.contains("  error_description=The provided authorization grant is invalid, expired or revoked");
		assertThat(this.received).as("no API call without a token").hasSize(1);
	}

	@Test
	void aRefusedReadPrintsItsCodeAndMessageAndTheOtherReadStillRuns() {
		this.identityStatus.set(403);
		this.identityBody.set("""
				{"status":403,"serviceErrorCode":100,"code":"ACCESS_DENIED",
				"message":"Not enough permissions to access: identityMe.GET"}
				""");

		assertThat(exchange(environment())).isOne();

		assertThat(this.out.toString(StandardCharsets.UTF_8))
			.contains("Spike S5 identityMe: HTTP 403, refused with code=ACCESS_DENIED serviceErrorCode=100")
			.contains("  message=Not enough permissions to access: identityMe.GET")
			.contains("Spike S5 verificationReport: HTTP 200")
			.contains("Spike S5 downloadUrlExpiresAt: absent");
		assertThat(this.received).hasSize(3);
	}

	@Test
	void aVersionLinkedInNoLongerServesIsRefusedWith426() {
		this.identityStatus.set(426);
		this.identityBody.set("""
				{"status":426,"code":"NONEXISTENT_VERSION","message":"Requested version 20240101 is not active"}
				""");

		assertThat(exchange(environment())).isOne();

		assertThat(this.out.toString(StandardCharsets.UTF_8))
			.contains("Spike S5 identityMe: HTTP 426, refused with code=NONEXISTENT_VERSION")
			.contains("  message=Requested version 20240101 is not active");
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
	void aRedirectFromTheTokenEndpointIsNotFollowed() {
		this.tokenStatus.set(307);
		this.tokenBody.set("{}");

		assertThat(exchange(environment())).isOne();

		assertThat(this.received).extracting(Received::path).containsExactly("/oauth/v2/accessToken");
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("Spike S5 token: HTTP 307, refused with");
	}

	@Test
	void noAnswerFailsTheRun() {
		MockEnvironment environment = environment();
		this.linkedIn.stop(0);

		assertThat(exchange(environment)).isOne();
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("Spike S5 token: no answer (");
	}

	@Test
	void aBodyThatStallsIsNoAnswerOnceTheTimeoutPasses() {
		this.tokenStalls.set(true);
		long started = System.nanoTime();

		assertThat(exchange(environment())).isOne();

		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(TIMEOUT.multipliedBy(4));
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("Spike S5 token: no answer (TimeoutException)");
		assertThat(this.received).hasSize(1);
	}

	@Test
	void anEmptyOrANonJsonBodyIsNamed() {
		this.identityBody.set("");
		this.reportBody.set("<html>not json</html>");

		assertThat(exchange(environment())).isZero();

		String next = System.lineSeparator();
		assertThat(this.out.toString(StandardCharsets.UTF_8))
			.contains("Spike S5 identityMe: HTTP 200" + next + "  (empty body)")
			.contains("Spike S5 verificationReport: HTTP 200" + next + "  (not JSON, 21 characters)");
	}

	@Test
	void deepOrLargeAnswersAreCut() {
		String deep = "{\"a\":".repeat(14) + "1" + "}".repeat(14);
		String list = IntStream.range(0, 30).mapToObj(Integer::toString).collect(Collectors.joining(",", "[", "]"));
		String wide = IntStream.range(0, 30)
			.mapToObj(index -> "\"f" + index + "\":" + index)
			.collect(Collectors.joining(",", "{", "}"));
		this.identityBody.set("{\"deep\":" + deep + ",\"list\":" + list + ",\"wide\":" + wide + "}");

		assertThat(exchange(environment())).isZero();

		int more = 30 - SpikeS5Command.MAX_ELEMENTS;
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains(": 1 fields, not shown")
			.contains("list: " + more + " more elements")
			.contains("list[24]: 24")
			.doesNotContain("list[25]")
			.contains("wide: " + more + " more fields")
			.contains("wide.f24: 24")
			.doesNotContain("wide.f25");
	}

	@Test
	void expiresInSentAsAStringIsReadAsSeconds() {
		this.tokenBody.set("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":\"5183999\"}");

		assertThat(exchange(environment())).isZero();

		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("expires_in=5183999 s (60.0 days)")
			.contains("refresh_token_expires_in=absent");
	}

	@Test
	void aValueNoHeaderAllowsIsRefusedBeforeItIsSent() {
		MockEnvironment badVersion = environment();
		badVersion.setProperty("vibefix.linkedin.api-version", "2026 09");
		assertThat(exchange(badVersion)).isOne();
		assertThat(this.err.toString(StandardCharsets.UTF_8))
			.contains("vibefix.linkedin.api-version holds a character no HTTP header allows");
		assertThat(this.received).as("not even the token request").isEmpty();

		this.tokenBody.set("{\"access_token\":\"AQV s5 token " + "with spaces\",\"expires_in\":60}");
		assertThat(exchange(environment())).isOne();
		assertThat(this.err.toString(StandardCharsets.UTF_8))
			.contains("access_token holds a character no HTTP header allows; neither API was called")
			.doesNotContain("with spaces");
		assertThat(this.received).extracting(Received::path).containsExactly("/oauth/v2/accessToken");
	}

	@Test
	void noOutputLineHoldsTheTokenTheCodeOrTheClientSecretWhateverTheStep() {
		run(environment(), "--step=authorize");
		exchange(environment());
		// An enum-like code and token, echoed back in fields whose enum-like values print as they are.
		this.tokenBody.set("{\"access_token\":\"" + ENUM_TOKEN + "\",\"expires_in\":60}");
		this.identityBody.set("{\"status\":\"" + ENUM_CODE + "\",\"code\":\"" + ENUM_TOKEN + "\",\"note\":\""
				+ CLIENT_SECRET + " " + CODE + "\"}");
		run(environment(), "--step=exchange", "--state=" + STATE, "--query=code=" + ENUM_CODE + "&state=" + STATE);
		assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("  status: <redacted>")
			.contains("  code: <redacted>")
			.contains("  note: <redacted>");
		// A client secret that looks like an OAuth error code, echoed back as the error.
		this.tokenStatus.set(401);
		this.tokenBody.set("{\"error\":\"" + SNAKE_CLIENT_SECRET + "\",\"error_description\":\"bad "
				+ SNAKE_CLIENT_SECRET + "\"}");
		MockEnvironment snake = environment();
		snake.setProperty(SpikeS5Command.CLIENT_SECRET, SNAKE_CLIENT_SECRET);
		run(snake, "--step=exchange", "--state=" + STATE, QUERY);

		assertThat(output()).contains("Spike S5 token: HTTP 401, refused with error=<redacted>")
			.contains("  error_description=<redacted>")
			.contains("  error: <redacted>");
		for (String line : output().lines().toList()) {
			assertThat(line).doesNotContain(ACCESS_TOKEN, REFRESH_TOKEN, ID_TOKEN, ENUM_TOKEN, CODE, ENUM_CODE,
					CLIENT_SECRET, SNAKE_CLIENT_SECRET);
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
		SpikeS5Command command = new SpikeS5Command(environment, CLOCK, printing(this.out), printing(this.err),
				TIMEOUT);
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
			.withProperty("vibefix.linkedin.api-version", "202609")
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
		if ("/oauth/v2/accessToken".equals(path) && this.tokenStalls.get()) {
			stall(exchange);
			return;
		}
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
				answer = this.reportBody.get();
			}
			default -> {
				status = 404;
				answer = "{\"status\":404,\"code\":\"NOT_FOUND\"}";
			}
		}
		if (status == 307) {
			exchange.getResponseHeaders().set("Location", base() + "/elsewhere");
		}
		byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream stream = exchange.getResponseBody()) {
			stream.write(bytes);
		}
	}

	/** Headers and the first byte of a 1000-byte body, then nothing until the test ends. */
	private void stall(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(200, 1000);
		OutputStream stream = exchange.getResponseBody();
		stream.write('{');
		stream.flush();
		try {
			this.release.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		exchange.close();
	}

}
