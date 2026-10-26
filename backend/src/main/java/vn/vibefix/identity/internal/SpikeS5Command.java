package vn.vibefix.identity.internal;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * the redirect {@code vibefix.linkedin.redirect-uri}, a path the Vite proxy does not forward, so
 * the browser lands on the SPA's not-found page; it prints the {@code state};</li>
 * <li>{@code exchange --state=<state> --query=<the address the browser lands on, or its query>}: it
 * checks the {@code state}, exchanges the code with the client secret, and in the same process reads
 * {@code identityMe} and {@code verificationReport} with the token and the {@code LinkedIn-Version}
 * header.</li>
 * </ul>
 * It prints each HTTP status and each answer's JSON shape, masked:
 * <ul>
 * <li>a key that is not a plain field name (a member ID, a URN) prints as {@code key(N)};</li>
 * <li>a string prints as its form (letters {@code x}, digits {@code 9}), a URL as the form of its
 * scheme, host and path with the names of its query parameters;</li>
 * <li>below a field that names a person, every value stays masked, numbers included;</li>
 * <li>upper-case values print as they are only in {@code status}, {@code code}, {@code error},
 * {@code type}, the category fields, and inside {@code verifications}; a lower-case {@code scope}
 * and {@code downloadUrlExpiresAt} print as they are too.</li>
 * </ul>
 * It also prints the token's {@code expires_in} and {@code refresh_token_expires_in}, and, for a
 * refusal, LinkedIn's error code, {@code message} and {@code error_description}. The secret files
 * {@value #CLIENT_ID} and {@value #CLIENT_SECRET} are read here only. The client secret, the code and
 * the tokens stay in memory: they are never printed, logged or written. Every line goes through one
 * printer that redacts any value holding one of them and cuts them from the line. The probe neither
 * reads nor writes the database, and creates no account, verification or signal.
 * <p>
 * Exit code 0 when LinkedIn answered every call; 1 off the dev stack, for a {@code state} that does
 * not match, a refusal from LinkedIn, no answer within the timeout, or a value no HTTP header allows;
 * 2 for a wrong invocation or a missing setting or secret file.
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

	/** How long each call may take, its body included. */
	static final Duration TIMEOUT = Duration.ofSeconds(20);

	private static final List<String> URI_SETTINGS = List.of("authorization-uri", "token-uri", "identity-me-uri",
			"verification-report-uri", "redirect-uri");

	private static final String EXPIRES_AT = "downloadUrlExpiresAt";

	/** An enum-like value: {@code IDENTITY}, {@code ACCESS_DENIED}. */
	private static final Pattern ENUM_LIKE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

	/** The fields whose enum-like values print as they are; so do the values inside {@value #VERIFICATIONS}. */
	private static final Set<String> ENUM_FIELDS = Set.of("status", "code", "serviceErrorCode", "error", "type",
			"verificationType", "category", "categories");

	private static final String VERIFICATIONS = "verifications";

	/** An OAuth error code (RFC 6749 §5.2): {@code invalid_grant}, {@code user_cancelled_login}. */
	private static final Pattern OAUTH_ERROR = Pattern.compile("[a-z][a-z0-9_]{0,63}");

	/** A key printed as it is; any other key (a member ID, a URN) prints as {@code key(N)}. */
	private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");

	/** A granted scope list, printed as it is: {@code r_profile_basicinfo,r_verify}. */
	private static final Pattern SCOPE_VALUE = Pattern.compile("[a-z_, ]{1,200}");

	private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://\\S+");

	private static final Pattern SECONDS = Pattern.compile("\\d{1,18}");

	/** Words of a field that names a person: every value below it stays masked. */
	private static final List<String> PERSONAL_WORDS = List.of("name", "url", "email", "phone", "headline", "urn",
			"address", "picture", "image", "birth", "location", "title", "company", "school");

	private static final int MAX_DEPTH = 12;

	/** The most elements of an array, or fields of an object, printed. */
	static final int MAX_ELEMENTS = 25;

	private static final int MAX_SHAPE = 80;

	private static final int MAX_MESSAGE = 300;

	/** Shorter values are not cut from the output: they would garble it, and no secret is that short. */
	private static final int MIN_HIDDEN = 6;

	private static final String REDACTED = "<redacted>";

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

	private final Duration timeout;

	private volatile int exitCode;

	@Autowired
	SpikeS5Command(Environment environment, Clock clock) {
		this(environment, clock, System.out, System.err, TIMEOUT);
	}

	SpikeS5Command(Environment environment, Clock clock, PrintStream out, PrintStream err, Duration timeout) {
		this.environment = environment;
		this.clock = clock;
		this.out = out;
		this.err = err;
		this.timeout = timeout;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!COMMAND.equals(this.environment.getProperty("vibefix.cli.command"))) {
			// Another command's run; BootstrapCommand answers an unknown one.
			this.exitCode = 0;
			return;
		}
		Printer printer = new Printer();
		if (this.environment.acceptsProfiles(Profiles.of("prod"))
				|| !this.environment.acceptsProfiles(Profiles.of("dev"))) {
			printer.err("Refused: " + COMMAND + " runs on the dev stack only");
			this.exitCode = 1;
			return;
		}
		String step = single(args, "step");
		if (step == null || !List.of("authorize", "exchange").contains(step)) {
			this.exitCode = usage(printer);
			return;
		}
		String missing = missing();
		if (missing != null) {
			printer.err("Refused: " + missing + " (deploy/README.md \"Spike S5\")");
			this.exitCode = usage(printer);
			return;
		}
		Settings settings = settings();
		printer.hide(settings.clientSecret());
		this.exitCode = "authorize".equals(step) ? authorize(settings, printer) : exchange(args, settings, printer);
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private int authorize(Settings settings, Printer printer) {
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
		printer.out("Spike S5 authorize: open this URL in a browser, sign in to LinkedIn with your own account,"
				+ " and allow the consent:");
		printer.out(base + (base.contains("?") ? "&" : "?") + encode(query));
		printer.out("state=" + state);
		printer.out("LinkedIn then sends the browser to " + settings.redirectUri()
				+ "?code=…&state=…, which the Vite proxy does not forward, so the page says it was not found."
				+ " Copy that whole address from the address bar at once (the code is short-lived and works once),"
				+ " and run:");
		printer.out("  --step=exchange --state=" + state + " --query='<that address>'");
		return 0;
	}

	private int exchange(ApplicationArguments args, Settings settings, Printer printer) {
		String expected = single(args, "state");
		String query = single(args, "query");
		if (expected == null || expected.isBlank() || query == null || query.isBlank()) {
			return usage(printer);
		}
		Map<String, String> params;
		try {
			params = parse(query);
		}
		catch (IllegalArgumentException ex) {
			printer.err("Refused: --query is not a URL query");
			return usage(printer);
		}
		String code = params.get("code");
		printer.hide(code);
		String state = params.get("state");
		if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8))) {
			printer.err("Refused: the state in --query does not match --state, so no token was requested;"
					+ " start again with --step=authorize");
			return 1;
		}
		String error = params.get("error");
		if (error != null) {
			printer.out("Spike S5 consent: LinkedIn sent no code, error=" + printer.oauthError(error));
			return 1;
		}
		if (code == null || code.isBlank()) {
			printer.err("Refused: --query holds no code");
			return usage(printer);
		}
		if (!headerValue(settings.apiVersion())) {
			printer.err("Refused: " + SETTINGS
					+ "api-version holds a character no HTTP header allows; nothing was sent");
			return 1;
		}
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(this.timeout.compareTo(Duration.ofSeconds(10)) < 0 ? this.timeout : Duration.ofSeconds(10))
			// The code, the secret and the token go to the configured hosts only.
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		try {
			return probe(http, settings, code, printer) ? 0 : 1;
		}
		catch (IllegalArgumentException ex) {
			// Never the exception's message: it can quote a header, and so the token.
			printer.err("Refused: a request to LinkedIn could not be built");
			return 1;
		}
		finally {
			// Also drops a call still waiting after its timeout.
			http.shutdownNow();
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
			.timeout(this.timeout)
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(encode(form)))
			.build());
		JsonNode issued = token.body();
		String accessToken = issued == null ? null : text(issued, "access_token");
		if (issued != null) {
			for (String name : List.of("access_token", "refresh_token", "id_token")) {
				printer.hide(text(issued, name));
			}
		}
		printer.out("Spike S5 token: " + status(token, printer));
		messages(printer, token);
		List<Map.Entry<String, JsonNode>> expiries = new ArrayList<>();
		shape(printer, token, expiries);
		if (!token.answered() || issued == null || accessToken == null || accessToken.isBlank()) {
			if (token.answered()) {
				printer.out("  LinkedIn issued no access_token");
			}
			return false;
		}
		printer.out("  expires_in=" + seconds(printer, issued.get("expires_in")));
		printer.out("  refresh_token_expires_in=" + seconds(printer, issued.get("refresh_token_expires_in")));
		if (!headerValue(accessToken)) {
			printer.err("Refused: LinkedIn's access_token holds a character no HTTP header allows;"
					+ " neither API was called");
			return false;
		}
		boolean answered = true;
		Map<String, URI> reads = new LinkedHashMap<>();
		reads.put("identityMe", settings.identityMeUri());
		reads.put("verificationReport", settings.verificationReportUri());
		for (Map.Entry<String, URI> read : reads.entrySet()) {
			Answer answer = send(http, HttpRequest.newBuilder(read.getValue())
				.timeout(this.timeout)
				.header("Authorization", "Bearer " + accessToken)
				.header("LinkedIn-Version", settings.apiVersion())
				.header("X-Restli-Protocol-Version", "2.0.0")
				.header("Accept", "application/json")
				.GET()
				.build());
			printer.out("Spike S5 " + read.getKey() + ": " + status(answer, printer));
			messages(printer, answer);
			shape(printer, answer, expiries);
			answered &= answer.answered();
		}
		expiries(printer, expiries);
		return answered;
	}

	/** One call, its body included, within the timeout; a server that stalls is no answer. */
	private Answer send(HttpClient http, HttpRequest request) {
		CompletableFuture<HttpResponse<String>> future = http.sendAsync(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		HttpResponse<String> response;
		try {
			response = future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			return new Answer(0, null, "TimeoutException");
		}
		catch (ExecutionException ex) {
			// The exception class only: a message could hold the request.
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			return new Answer(0, null, cause.getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			return new Answer(0, null, "interrupted");
		}
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

	private static String status(Answer answer, Printer printer) {
		if (answer.status() == 0) {
			return "no answer (" + answer.note() + ")";
		}
		return "HTTP " + answer.status()
				+ (answer.answered() ? "" : ", refused with " + refusal(answer.body(), printer));
	}

	/**
	 * The error code of a refusal: OAuth's {@code error}, or the REST API's {@code code} and
	 * {@code serviceErrorCode}.
	 */
	private static String refusal(@Nullable JsonNode body, Printer printer) {
		if (body == null || !body.isObject()) {
			return "no error code";
		}
		List<String> parts = new ArrayList<>();
		JsonNode error = body.get("error");
		if (error != null && error.isString()) {
			parts.add("error=" + printer.oauthError(error.stringValue()));
		}
		JsonNode code = body.get("code");
		if (code != null && code.isString() && ENUM_LIKE.matcher(code.stringValue()).matches()) {
			parts.add("code=" + printer.text(code.stringValue()));
		}
		JsonNode service = body.get("serviceErrorCode");
		if (service != null && service.isIntegralNumber()) {
			parts.add("serviceErrorCode=" + service);
		}
		return parts.isEmpty() ? "no error code" : String.join(" ", parts);
	}

	/** A refusal's own words ({@code message}, {@code error_description}), on one line each. */
	private static void messages(Printer printer, Answer answer) {
		JsonNode body = answer.body();
		if (answer.status() < 400 || body == null || !body.isObject()) {
			return;
		}
		for (String field : List.of("message", "error_description")) {
			JsonNode node = body.get(field);
			if (node != null && node.isString()) {
				printer.out("  " + field + "=" + printer.text(plain(node.stringValue())));
			}
		}
	}

	/** The text on one line, at most {@value #MAX_MESSAGE} characters. */
	private static String plain(String text) {
		StringBuilder plain = new StringBuilder();
		text.codePoints()
			.limit(MAX_MESSAGE)
			.forEach(point -> plain.appendCodePoint(Character.isISOControl(point) ? ' ' : point));
		return text.codePointCount(0, text.length()) > MAX_MESSAGE ? plain + "…" : plain.toString();
	}

	/** The answer's JSON shape, one line per value, masked; each {@code downloadUrlExpiresAt} is collected. */
	private static void shape(Printer printer, Answer answer, List<Map.Entry<String, JsonNode>> expiries) {
		if (answer.status() == 0) {
			return;
		}
		JsonNode body = answer.body();
		if (body == null) {
			printer.out("  (" + answer.note() + ")");
			return;
		}
		List<String> lines = new ArrayList<>();
		walk(printer, "", "", false, false, body, 0, lines, expiries);
		lines.forEach(line -> printer.out("  " + line));
	}

	/**
	 * @param field the name of the field holding {@code node}, or of the array holding it; empty when
	 * that name is not shown
	 * @param personal whether {@code node} or one of the fields above it names a person
	 * @param verification whether {@code node} lies inside {@value #VERIFICATIONS}
	 */
	private static void walk(Printer printer, String path, String field, boolean personal, boolean verification,
			JsonNode node, int depth, List<String> lines, List<Map.Entry<String, JsonNode>> expiries) {
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
			int index = 0;
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				if (index++ == MAX_ELEMENTS) {
					lines.add(label + ": " + (node.size() - MAX_ELEMENTS) + " more fields");
					break;
				}
				String key = property.getKey();
				boolean safe = SAFE_KEY.matcher(key).matches();
				String shown = safe ? key : "key(" + key.codePointCount(0, key.length()) + ")";
				walk(printer, path.isEmpty() ? shown : path + "." + shown, safe ? key : "",
						personal || !safe || personal(key), verification || VERIFICATIONS.equals(key),
						property.getValue(), depth + 1, lines, expiries);
			}
			return;
		}
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				if (index == MAX_ELEMENTS) {
					lines.add(label + ": " + (node.size() - MAX_ELEMENTS) + " more elements");
					break;
				}
				walk(printer, label + "[" + index + "]", field, personal, verification, node.get(index), depth + 1,
						lines, expiries);
			}
			return;
		}
		if (EXPIRES_AT.equals(field)) {
			expiries.add(Map.entry(label, node));
		}
		lines.add(label + ": " + value(printer, field, personal, verification, node));
	}

	/** A value as printed; see the class description for what prints as it is. */
	private static String value(Printer printer, String field, boolean personal, boolean verification, JsonNode node) {
		if (node.isNull()) {
			return "null";
		}
		if (secret(field) || (node.isString() && printer.holds(node.stringValue()))) {
			return REDACTED;
		}
		if (EXPIRES_AT.equals(field) || node.isBoolean()) {
			return node.toString();
		}
		if (node.isNumber()) {
			return personal ? "number(" + node.toString().length() + ")" : node.toString();
		}
		if (!node.isString()) {
			return "<" + node.getNodeType() + ">";
		}
		String text = node.stringValue();
		if (!personal && "scope".equals(field) && SCOPE_VALUE.matcher(text).matches()) {
			return "\"" + text + "\"";
		}
		if (!personal && (verification || ENUM_FIELDS.contains(field)) && ENUM_LIKE.matcher(text).matches()) {
			return "\"" + text + "\"";
		}
		String url = url(text);
		return url != null ? url : "string(" + text.codePointCount(0, text.length()) + ") \"" + mask(text) + "\"";
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

	/** A URL's form: its scheme, host and path masked, and the names of its query parameters only. */
	private static @Nullable String url(String text) {
		if (!URL.matcher(text).matches()) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(text);
		}
		catch (URISyntaxException ex) {
			return null;
		}
		if (uri.getScheme() == null || uri.getRawAuthority() == null) {
			return null;
		}
		StringBuilder shown = new StringBuilder("url(").append(text.codePointCount(0, text.length()))
			.append(") \"")
			.append(mask(uri.getScheme()))
			.append("://")
			.append(mask(uri.getRawAuthority()))
			.append('"');
		String path = uri.getRawPath();
		if (path != null && !path.isEmpty()) {
			shown.append(" path \"").append(mask(path)).append('"');
		}
		String query = uri.getRawQuery();
		if (query != null) {
			List<String> names = new ArrayList<>();
			for (String pair : query.split("&")) {
				String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
				if (!name.isEmpty() && names.size() < MAX_ELEMENTS) {
					names.add(SAFE_KEY.matcher(name).matches() ? name : "key(" + name.length() + ")");
				}
			}
			shown.append(" query [").append(String.join(", ", names)).append(']');
		}
		if (uri.getRawFragment() != null) {
			shown.append(" fragment");
		}
		return shown.toString();
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

	/** A number of seconds, sent as a JSON number or a string of digits. */
	private static String seconds(Printer printer, @Nullable JsonNode node) {
		if (node == null || node.isNull()) {
			return "absent";
		}
		Long seconds = null;
		if (node.isIntegralNumber() && node.canConvertToLong()) {
			seconds = node.longValue();
		}
		else if (node.isString() && SECONDS.matcher(node.stringValue()).matches()) {
			seconds = Long.parseLong(node.stringValue());
		}
		if (seconds == null) {
			return value(printer, "expires_in", false, false, node);
		}
		return seconds + " s (" + String.format(Locale.ROOT, "%.1f", seconds / 86_400.0) + " days)";
	}

	/** Each {@code downloadUrlExpiresAt}, read as epoch milliseconds, and how far it lies from now. */
	private void expiries(Printer printer, List<Map.Entry<String, JsonNode>> expiries) {
		if (expiries.isEmpty()) {
			printer.out("Spike S5 downloadUrlExpiresAt: absent");
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
			printer.out("Spike S5 downloadUrlExpiresAt: " + value(printer, EXPIRES_AT, false, false, node) + " = "
					+ when + " (" + expiry.getKey() + ")");
		}
	}

	private int usage(Printer printer) {
		printer.err("Usage: --spring.profiles.active=dev,cli --vibefix.cli.command=" + COMMAND + " --step=authorize");
		printer.err("   or: --step=exchange --state=<the state authorize printed>"
				+ " --query='<the address the browser lands on, or its query>'");
		printer.err("Needs the secret files " + CLIENT_ID + " and " + CLIENT_SECRET + ", and the " + SETTINGS
				+ "* settings (deploy/README.md, \"Spike S5\"); dev only.");
		return 2;
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

	/** Whether every character is visible ASCII, as an HTTP header value of this probe must be. */
	private static boolean headerValue(String value) {
		return !value.isEmpty() && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
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

	/**
	 * Every line the probe prints, on either stream, goes through here (AD-22). A value that holds a
	 * known secret (the client secret, the code, a token) is redacted whole, and every line has those
	 * secrets cut out as a last guard.
	 */
	private final class Printer {

		private final List<String> hidden = new ArrayList<>();

		void hide(@Nullable String secret) {
			if (secret != null && secret.length() >= MIN_HIDDEN) {
				this.hidden.add(secret);
			}
		}

		boolean holds(String value) {
			return this.hidden.stream().anyMatch(value::contains);
		}

		/** The value as it is, unless it holds a secret. */
		String text(String value) {
			return holds(value) ? REDACTED : value;
		}

		String oauthError(String error) {
			if (holds(error)) {
				return REDACTED;
			}
			return OAUTH_ERROR.matcher(error).matches() ? error : "string(" + error.length() + ")";
		}

		void out(String line) {
			SpikeS5Command.this.out.println(cut(line));
		}

		void err(String line) {
			SpikeS5Command.this.err.println(cut(line));
		}

		private String cut(String line) {
			String printed = line;
			for (String secret : this.hidden) {
				printed = printed.replace(secret, REDACTED);
			}
			return printed;
		}

	}

}
