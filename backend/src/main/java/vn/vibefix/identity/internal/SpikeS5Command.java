package vn.vibefix.identity.internal;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spike S5's probe (Story 19.5, AD-22, AD-42): under {@code dev} with {@code cli},
 * {@code --vibefix.cli.command=spike-s5 --step=…} runs one consent of Verified on LinkedIn for the
 * author's own LinkedIn account against the dev LinkedIn app, and prints what LinkedIn answered, so
 * the author can record S5's Outcome:
 * <ul>
 * <li>{@code authorize}: the authorization URL for {@value #SCOPE} with a random {@code state}, to
 * the redirect {@code vibefix.linkedin.redirect-uri}, which nothing serves; it prints the
 * {@code state};</li>
 * <li>{@code exchange --state=<state> --query=<the address the browser lands on, or its query>}: it
 * checks the {@code state}, exchanges the code with the client secret, and in the same process reads
 * {@code identityMe} and {@code verificationReport} with the token and the {@code LinkedIn-Version}
 * header.</li>
 * </ul>
 * It prints each HTTP status and each answer's JSON shape. Every string value is masked (letters
 * {@code x}, digits {@code 9}), except field names, upper-case enum-like tokens outside fields that
 * name a person, and {@code downloadUrlExpiresAt}; it also prints the token's {@code expires_in} and
 * {@code refresh_token_expires_in}. The secret files {@value #CLIENT_ID} and {@value #CLIENT_SECRET}
 * are read here only. The client secret, the code and the tokens stay in memory: they are never
 * printed, logged or written, and every printed line has them cut out as a last guard. The probe
 * neither reads nor writes the database, and creates no account, verification or signal.
 * <p>
 * Exit code 0 when LinkedIn answered every call; 1 off the dev stack, for a {@code state} that does
 * not match, a refusal from LinkedIn, or no answer; 2 for a wrong invocation or a missing setting or
 * secret file.
 */
@Component
@Profile("cli")
class SpikeS5Command implements ApplicationRunner, ExitCodeGenerator {

	static final String COMMAND = "spike-s5";

	/** The dev LinkedIn app's secret files (AD-22); only this probe reads them until Story 19.6. */
	static final String CLIENT_ID = "linkedin-client-id";

	static final String CLIENT_SECRET = "linkedin-client-secret";

	/** Verified on LinkedIn's two scopes, nothing more. */
	static final String SCOPE = "r_profile_basicinfo r_verify";

	static final String SETTINGS = "vibefix.linkedin.";

	private static final List<String> URI_SETTINGS = List.of("authorization-uri", "token-uri", "identity-me-uri",
			"verification-report-uri", "redirect-uri");

	private static final String EXPIRES_AT = "downloadUrlExpiresAt";

	/** An enum-like value, shown as it is: {@code IDENTITY}, {@code ACCESS_DENIED}. */
	private static final Pattern ENUM_LIKE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

	/** An OAuth error code (RFC 6749 §5.2): {@code invalid_grant}, {@code user_cancelled_login}. */
	private static final Pattern OAUTH_ERROR = Pattern.compile("[a-z][a-z0-9_]{0,63}");

	/** Words of a field that names a person, whose values stay masked even when enum-like. */
	private static final List<String> PERSONAL_WORDS = List.of("name", "url", "email", "phone", "headline", "urn",
			"address", "picture", "image", "birth", "location", "title", "company", "school");

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private static final int MAX_DEPTH = 12;

	private static final int MAX_ELEMENTS = 10;

	private static final int MAX_SHAPE = 80;

	/** Shorter values are not cut from the output: they would garble it, and no secret is that short. */
	private static final int MIN_HIDDEN = 6;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final SecureRandom RANDOM = new SecureRandom();

	/** The probe's settings and secrets, read at run time so that other {@code cli} commands start without them. */
	record Settings(URI authorizationUri, URI tokenUri, URI identityMeUri, URI verificationReportUri, String apiVersion,
			String redirectUri, String clientId, String clientSecret) {

		@Override
		public String toString() {
			return "Settings[" + this.tokenUri + "]";
		}

	}

	/** One answer from LinkedIn: its HTTP status (0 when none came), its JSON body, or why there is none. */
	record Answer(int status, @Nullable JsonNode body, String note) {

		boolean answered() {
			return this.status >= 200 && this.status < 300;
		}

		@Override
		public String toString() {
			return "Answer[" + this.status + "]";
		}

	}

	private final Environment environment;

	private final Clock clock;

	private final PrintStream out;

	private final PrintStream err;

	private volatile int exitCode;

	@Autowired
	SpikeS5Command(Environment environment, Clock clock) {
		this(environment, clock, System.out, System.err);
	}

	SpikeS5Command(Environment environment, Clock clock, PrintStream out, PrintStream err) {
		this.environment = environment;
		this.clock = clock;
		this.out = out;
		this.err = err;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command"))) {
			// Another command's run; BootstrapCommand answers an unknown one.
			this.exitCode = 0;
			return;
		}
		if (this.environment.acceptsProfiles(Profiles.of("prod"))
				|| !this.environment.acceptsProfiles(Profiles.of("dev"))) {
			this.err.println("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		String step = single(args, "step");
		if (step == null || !List.of("authorize", "exchange").contains(step)) {
			usage();
			return;
		}
		String missing = missing();
		if (missing != null) {
			this.err.println("Refused: " + missing + " (deploy/README.md \"Spike S5\")");
			usage();
			return;
		}
		Settings settings = settings();
		if ("authorize".equals(step)) {
			authorize(settings);
		}
		else {
			exchange(args, settings);
		}
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private void authorize(Settings settings) {
		byte[] random = new byte[24];
		RANDOM.nextBytes(random);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		Map<String, String> query = new LinkedHashMap<>();
		query.put("response_type", "code");
		query.put("client_id", settings.clientId());
		query.put("redirect_uri", settings.redirectUri());
		query.put("state", state);
		query.put("scope", SCOPE);
		String base = settings.authorizationUri().toString();
		Printer printer = new Printer(settings.clientSecret());
		printer.println("Spike S5 authorize: open this URL in a browser, sign in to LinkedIn with your own account,"
				+ " and allow the consent:");
		printer.println(base + (base.contains("?") ? "&" : "?") + encode(query));
		printer.println("state=" + state);
		printer.println("LinkedIn then sends the browser to " + settings.redirectUri()
				+ "?code=…&state=…, which nothing serves. Copy that whole address from the address bar at once"
				+ " (the code is short-lived and works once), and run:");
		printer.println("  --step=exchange --state=" + state + " --query='<that address>'");
		this.exitCode = 0;
	}

	private void exchange(ApplicationArguments args, Settings settings) {
		String expected = single(args, "state");
		String query = single(args, "query");
		if (expected == null || expected.isBlank() || query == null || query.isBlank()) {
			usage();
			return;
		}
		Map<String, String> params;
		try {
			params = parse(query);
		}
		catch (IllegalArgumentException ex) {
			this.err.println("Refused: --query is not a URL query");
			usage();
			return;
		}
		String state = params.get("state");
		if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8))) {
			this.err.println("Refused: the state in --query does not match --state, so no token was requested;"
					+ " start again with --step=authorize");
			this.exitCode = 1;
			return;
		}
		String error = params.get("error");
		if (error != null) {
			this.out.println("Spike S5 consent: LinkedIn sent no code, error=" + oauthError(error));
			this.exitCode = 1;
			return;
		}
		String code = params.get("code");
		if (code == null || code.isBlank()) {
			this.err.println("Refused: --query holds no code");
			usage();
			return;
		}
		Printer printer = new Printer(settings.clientSecret(), code);
		try (HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			// The code, the secret and the token go to the configured hosts only.
			.followRedirects(HttpClient.Redirect.NEVER)
			.build()) {
			this.exitCode = probe(http, settings, code, printer) ? 0 : 1;
		}
	}

	/** The token request, then both reads with its token; true when LinkedIn answered all three. */
	private boolean probe(HttpClient http, Settings settings, String code, Printer printer) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", settings.redirectUri());
		form.put("client_id", settings.clientId());
		form.put("client_secret", settings.clientSecret());
		Answer token = send(http, HttpRequest.newBuilder(settings.tokenUri())
			.timeout(TIMEOUT)
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(encode(form)))
			.build());
		JsonNode issued = token.body();
		String accessToken = issued == null ? null : text(issued, "access_token");
		printer.hide(accessToken);
		if (issued != null) {
			printer.hide(text(issued, "refresh_token"));
			printer.hide(text(issued, "id_token"));
		}
		printer.println("Spike S5 token: " + status(token));
		List<Map.Entry<String, JsonNode>> expiries = new ArrayList<>();
		shape(printer, token, expiries);
		if (!token.answered() || issued == null || accessToken == null || accessToken.isBlank()) {
			if (token.answered()) {
				printer.println("  LinkedIn issued no access_token");
			}
			return false;
		}
		printer.println("  expires_in=" + seconds(issued.get("expires_in")));
		printer.println("  refresh_token_expires_in=" + seconds(issued.get("refresh_token_expires_in")));
		boolean answered = true;
		Map<String, URI> reads = new LinkedHashMap<>();
		reads.put("identityMe", settings.identityMeUri());
		reads.put("verificationReport", settings.verificationReportUri());
		for (Map.Entry<String, URI> read : reads.entrySet()) {
			Answer answer = send(http, HttpRequest.newBuilder(read.getValue())
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.header("LinkedIn-Version", settings.apiVersion())
				.header("X-Restli-Protocol-Version", "2.0.0")
				.header("Accept", "application/json")
				.GET()
				.build());
			printer.println("Spike S5 " + read.getKey() + ": " + status(answer));
			shape(printer, answer, expiries);
			answered &= answer.answered();
		}
		expiries(printer, expiries);
		return answered;
	}

	private static Answer send(HttpClient http, HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			String body = response.body();
			if (body == null || body.isBlank()) {
				return new Answer(response.statusCode(), null, "empty body");
			}
			try {
				return new Answer(response.statusCode(), JSON.readTree(body), "");
			}
			catch (JacksonException ex) {
				return new Answer(response.statusCode(), null, "not JSON, " + body.length() + " characters");
			}
		}
		catch (IOException ex) {
			// The exception class only: a message could hold the request.
			return new Answer(0, null, ex.getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Answer(0, null, "interrupted");
		}
	}

	private static String status(Answer answer) {
		if (answer.status() == 0) {
			return "no answer (" + answer.note() + ")";
		}
		return "HTTP " + answer.status() + (answer.answered() ? "" : ", refused with " + refusal(answer.body()));
	}

	/**
	 * The error code of a refusal: OAuth's {@code error}, or the REST API's {@code code} and
	 * {@code serviceErrorCode}.
	 */
	static String refusal(@Nullable JsonNode body) {
		if (body == null || !body.isObject()) {
			return "no error code";
		}
		List<String> parts = new ArrayList<>();
		JsonNode error = body.get("error");
		if (error != null && error.isString()) {
			parts.add("error=" + oauthError(error.stringValue()));
		}
		JsonNode code = body.get("code");
		if (code != null && code.isString() && ENUM_LIKE.matcher(code.stringValue()).matches()) {
			parts.add("code=" + code.stringValue());
		}
		JsonNode service = body.get("serviceErrorCode");
		if (service != null && service.isIntegralNumber()) {
			parts.add("serviceErrorCode=" + service);
		}
		return parts.isEmpty() ? "no error code" : String.join(" ", parts);
	}

	private static String oauthError(String error) {
		return OAUTH_ERROR.matcher(error).matches() ? error : "string(" + error.length() + ")";
	}

	/** The answer's JSON shape, one line per value, masked; each {@code downloadUrlExpiresAt} is collected. */
	private static void shape(Printer printer, Answer answer, List<Map.Entry<String, JsonNode>> expiries) {
		if (answer.status() == 0) {
			return;
		}
		JsonNode body = answer.body();
		if (body == null) {
			printer.println("  (" + answer.note() + ")");
			return;
		}
		List<String> lines = new ArrayList<>();
		walk("", "", body, 0, lines, expiries);
		lines.forEach(line -> printer.println("  " + line));
	}

	private static void walk(String path, String field, JsonNode node, int depth, List<String> lines,
			List<Map.Entry<String, JsonNode>> expiries) {
		String label = path.isEmpty() ? "(body)" : path;
		if (node.isContainer() && node.isEmpty()) {
			lines.add(label + (node.isObject() ? ": {}" : ": []"));
			return;
		}
		if (node.isContainer() && depth >= MAX_DEPTH) {
			lines.add(label + ": " + node.size() + (node.isObject() ? " fields" : " elements") + ", not shown");
			return;
		}
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				walk(path.isEmpty() ? property.getKey() : path + "." + property.getKey(), property.getKey(),
						property.getValue(), depth + 1, lines, expiries);
			}
			return;
		}
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				if (index == MAX_ELEMENTS) {
					lines.add(label + "[…]: " + (node.size() - MAX_ELEMENTS) + " more elements");
					break;
				}
				walk(label + "[" + index + "]", field, node.get(index), depth + 1, lines, expiries);
			}
			return;
		}
		if (EXPIRES_AT.equals(field)) {
			expiries.add(Map.entry(label, node));
		}
		lines.add(label + ": " + value(field, node));
	}

	/**
	 * A value as printed: masked unless it is a number, a boolean, an enum-like token, or
	 * {@code downloadUrlExpiresAt}.
	 */
	static String value(String field, JsonNode node) {
		if (node.isNull()) {
			return "null";
		}
		if (secret(field)) {
			return "<redacted>";
		}
		if (EXPIRES_AT.equals(field) || node.isBoolean()) {
			return node.toString();
		}
		if (node.isNumber()) {
			return personal(field) ? "number(" + node.toString().length() + ")" : node.toString();
		}
		if (node.isString()) {
			String text = node.stringValue();
			if (ENUM_LIKE.matcher(text).matches() && !personal(field)) {
				return "\"" + text + "\"";
			}
			return "string(" + text.codePointCount(0, text.length()) + ") \"" + mask(text) + "\"";
		}
		return "<" + node.getNodeType() + ">";
	}

	/** A token, a secret or a password: never shown, not even masked. */
	private static boolean secret(String field) {
		String lower = field.toLowerCase(Locale.ROOT);
		return (lower.contains("token") && !lower.contains("expires")) || lower.contains("secret")
				|| lower.contains("password");
	}

	/** A field that names or identifies a person. */
	private static boolean personal(String field) {
		String lower = field.toLowerCase(Locale.ROOT);
		return lower.equals("id") || lower.equals("sub") || field.endsWith("Id") || field.endsWith("ID")
				|| lower.endsWith("_id") || PERSONAL_WORDS.stream().anyMatch(lower::contains);
	}

	/** The value's form only: each letter {@code x} or {@code X}, each digit {@code 9}, ASCII punctuation kept. */
	static String mask(String text) {
		StringBuilder masked = new StringBuilder();
		text.codePoints().limit(MAX_SHAPE).forEach(point -> {
			if (Character.isLetter(point)) {
				masked.append(Character.isUpperCase(point) ? 'X' : 'x');
			}
			else if (Character.isDigit(point)) {
				masked.append('9');
			}
			else if (Character.isWhitespace(point)) {
				masked.append(' ');
			}
			else {
				masked.append(point < 0x80 && !Character.isISOControl(point) ? (char) point : '?');
			}
		});
		if (text.codePointCount(0, text.length()) > MAX_SHAPE) {
			masked.append('…');
		}
		return masked.toString();
	}

	private static String seconds(@Nullable JsonNode node) {
		if (node == null || node.isNull()) {
			return "absent";
		}
		if (node.isIntegralNumber() && node.canConvertToLong()) {
			long seconds = node.longValue();
			return seconds + " s (" + String.format(Locale.ROOT, "%.1f", seconds / 86_400.0) + " days)";
		}
		return value("expires_in", node);
	}

	/** Each {@code downloadUrlExpiresAt}, read as epoch milliseconds, and how far it lies from now. */
	private void expiries(Printer printer, List<Map.Entry<String, JsonNode>> expiries) {
		if (expiries.isEmpty()) {
			printer.println("Spike S5 downloadUrlExpiresAt: absent");
			return;
		}
		Instant now = this.clock.instant();
		for (Map.Entry<String, JsonNode> expiry : expiries) {
			JsonNode node = expiry.getValue();
			String when = "not epoch milliseconds";
			if (node.isIntegralNumber() && node.canConvertToLong()) {
				Instant at = Instant.ofEpochMilli(node.longValue());
				double hours = Duration.between(now, at).toMinutes() / 60.0;
				when = at + ", " + String.format(Locale.ROOT, hours >= 0 ? "in %.1f h" : "%.1f h ago", Math.abs(hours));
			}
			printer.println("Spike S5 downloadUrlExpiresAt: " + node + " = " + when + " (" + expiry.getKey() + ")");
		}
	}

	private void usage() {
		this.err.println(
				"Usage: --spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND + " --step=authorize");
		this.err.println("   or: --step=exchange --state=<the state authorize printed>"
				+ " --query='<the address the browser lands on, or its query>'");
		this.err.println("Needs the secret files " + CLIENT_ID + " and " + CLIENT_SECRET + ", and the " + SETTINGS
				+ "* settings (deploy/README.md, \"Spike S5\"); dev only.");
		this.exitCode = 2;
	}

	/** The first setting or secret file missing, or null when everything is there. Never a value. */
	private @Nullable String missing() {
		for (String name : URI_SETTINGS) {
			if (absoluteUri(this.environment.getProperty(SETTINGS + name, "").strip()) == null) {
				return SETTINGS + name + " is not set to an absolute http(s) URL";
			}
		}
		if (this.environment.getProperty(SETTINGS + "api-version", "").isBlank()) {
			return SETTINGS + "api-version is not set";
		}
		for (String name : List.of(CLIENT_ID, CLIENT_SECRET)) {
			if (this.environment.getProperty(name, "").isBlank()) {
				return "the secret file " + name + " is missing or empty";
			}
		}
		return null;
	}

	private Settings settings() {
		return new Settings(uri("authorization-uri"), uri("token-uri"), uri("identity-me-uri"),
				uri("verification-report-uri"), this.environment.getProperty(SETTINGS + "api-version", "").strip(),
				uri("redirect-uri").toString(), this.environment.getProperty(CLIENT_ID, "").strip(),
				this.environment.getProperty(CLIENT_SECRET, "").strip());
	}

	private URI uri(String name) {
		URI uri = absoluteUri(this.environment.getProperty(SETTINGS + name, "").strip());
		if (uri == null) {
			throw new IllegalStateException(SETTINGS + name + " is not an absolute URL");
		}
		return uri;
	}

	private static @Nullable URI absoluteUri(String value) {
		try {
			URI uri = new URI(value);
			String scheme = uri.getScheme();
			return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && uri.getHost() != null ? uri
					: null;
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}

	/**
	 * The query of {@code --query} as name and value, decoded; a whole address is cut at its {@code ?}
	 * and {@code #}.
	 */
	static Map<String, String> parse(String query) {
		String raw = query.strip();
		if (raw.contains("#")) {
			raw = raw.substring(0, raw.indexOf('#'));
		}
		if (raw.contains("?")) {
			raw = raw.substring(raw.indexOf('?') + 1);
		}
		Map<String, String> params = new LinkedHashMap<>();
		for (String pair : raw.split("&")) {
			int equals = pair.indexOf('=');
			if (equals > 0) {
				params.putIfAbsent(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
			}
		}
		return params;
	}

	private static String encode(Map<String, String> params) {
		List<String> pairs = new ArrayList<>();
		params.forEach((name, value) -> pairs.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
				+ URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")));
		return String.join("&", pairs);
	}

	private static @Nullable String text(JsonNode body, String field) {
		JsonNode node = body.get(field);
		return node != null && node.isString() ? node.stringValue() : null;
	}

	private static @Nullable String single(ApplicationArguments args, String name) {
		List<String> values = args.getOptionValues(name);
		return values == null || values.size() != 1 ? null : values.getFirst();
	}

	/** Prints lines with every secret value it knows cut out: a last guard behind the masking (AD-22). */
	private final class Printer {

		private final List<String> hidden = new ArrayList<>();

		Printer(String... secrets) {
			for (String secret : secrets) {
				hide(secret);
			}
		}

		void hide(@Nullable String secret) {
			if (secret != null && secret.length() >= MIN_HIDDEN) {
				this.hidden.add(secret);
			}
		}

		void println(String line) {
			String printed = line;
			for (String secret : this.hidden) {
				printed = printed.replace(secret, "<redacted>");
			}
			SpikeS5Command.this.out.println(printed);
		}

	}

}
