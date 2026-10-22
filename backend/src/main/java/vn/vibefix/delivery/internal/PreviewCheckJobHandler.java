package vn.vibefix.delivery.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobHandler;

/**
 * {@value #TYPE} (FR-32, AD-32, AD-35): whether a preview link responds, checked from the {@code checker}
 * service (in process until Epic 18). Only {@code https} on port 443; every address the host resolves to
 * must be public, and the check connects to the resolved address with SNI and the host's certificate
 * checked. It sends one {@code GET}, reads only the status line and the headers, never the body, and
 * follows at most {@value #MAX_REDIRECTS} redirects, each checked the same way, with
 * {@value #TIMEOUT_MILLIS} ms per step. A final status below 400, or 401 or 403 for a protected preview,
 * responds. Like every handler, a plain class that only the sandbox worker (or the in-process stand-in)
 * creates.
 * <p>
 * Input: {@code url}. Output: {@code reachable}, {@code status} when one came back, and {@code reason}
 * when it did not respond: {@value #NOT_HTTPS}, {@value #PRIVATE_ADDRESS}, {@value #UNKNOWN_HOST},
 * {@value #REDIRECT_DISALLOWED}, {@value #TOO_MANY_REDIRECTS}, {@value #NO_RESPONSE}, or
 * {@value #ERROR_STATUS}.
 */
final class PreviewCheckJobHandler implements SandboxJobHandler {

	static final String TYPE = "PREVIEW_CHECK";

	static final int TIMEOUT_MILLIS = 5_000;

	static final int MAX_REDIRECTS = 3;

	static final String NOT_HTTPS = "NOT_HTTPS";

	static final String PRIVATE_ADDRESS = "PRIVATE_ADDRESS";

	static final String UNKNOWN_HOST = "UNKNOWN_HOST";

	static final String REDIRECT_DISALLOWED = "REDIRECT_DISALLOWED";

	static final String TOO_MANY_REDIRECTS = "TOO_MANY_REDIRECTS";

	static final String NO_RESPONSE = "NO_RESPONSE";

	static final String ERROR_STATUS = "ERROR_STATUS";

	/** Resolves a host to every address it has. */
	interface Resolver {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	/** One request: the status line and the {@code Location} header, if any. */
	interface Probe {

		Answer request(InetAddress address, String host, String target) throws IOException;

	}

	record Answer(int status, @Nullable String location) {
	}

	private final Resolver resolver;

	private final Probe probe;

	PreviewCheckJobHandler() {
		this(InetAddress::getAllByName, PreviewCheckJobHandler::probe);
	}

	PreviewCheckJobHandler(Resolver resolver, Probe probe) {
		this.resolver = resolver;
		this.probe = probe;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public JsonNode handle(JsonNode input) {
		String url = input.path("url").asString("");
		if (url.isBlank() || url.length() > 2000) {
			throw new IllegalArgumentException("url is required");
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			return result(false, null, NOT_HTTPS);
		}
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			if (!allowed(uri)) {
				return result(false, null, hop == 0 ? NOT_HTTPS : REDIRECT_DISALLOWED);
			}
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			InetAddress[] addresses;
			try {
				addresses = this.resolver.resolve(host);
			}
			catch (UnknownHostException ex) {
				return result(false, null, UNKNOWN_HOST);
			}
			if (addresses.length == 0 || !List.of(addresses).stream().allMatch(PreviewCheckJobHandler::isPublic)) {
				return result(false, null, hop == 0 ? PRIVATE_ADDRESS : REDIRECT_DISALLOWED);
			}
			Answer answer;
			try {
				answer = this.probe.request(addresses[0], host, target(uri));
			}
			catch (IOException ex) {
				return result(false, null, NO_RESPONSE);
			}
			if (answer.status() >= 300 && answer.status() < 400 && answer.location() != null) {
				try {
					uri = uri.resolve(new URI(answer.location().strip()));
				}
				catch (URISyntaxException | IllegalArgumentException ex) {
					return result(false, answer.status(), REDIRECT_DISALLOWED);
				}
				continue;
			}
			boolean responds = answer.status() < 400 || answer.status() == 401 || answer.status() == 403;
			return result(responds, answer.status(), responds ? null : ERROR_STATUS);
		}
		return result(false, null, TOO_MANY_REDIRECTS);
	}

	/** {@code https} on port 443, a host name, no user information. */
	static boolean allowed(URI uri) {
		return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank()
				&& uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
	}

	/** A public unicast address: not loopback, private, link-local, shared, documentation, or multicast. */
	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = bytes[0] & 0xff;
			int second = bytes[1] & 0xff;
			int third = bytes[2] & 0xff;
			return !(first == 0 || first == 10 || first == 127 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
					|| (first == 169 && second == 254) || (first == 172 && second >= 16 && second <= 31)
					|| (first == 192 && second == 168) || (first == 192 && second == 0 && (third == 0 || third == 2))
					|| (first == 198 && (second == 18 || second == 19)) || (first == 198 && second == 51 && third == 100)
					|| (first == 203 && second == 0 && third == 113));
		}
		boolean documentation = (bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01 && (bytes[2] & 0xff) == 0x0d
				&& (bytes[3] & 0xff) == 0xb8;
		// Only global unicast (2000::/3); unique local (fc00::/7) and the rest are refused.
		return (bytes[0] & 0xe0) == 0x20 && !documentation;
	}

	private static String target(URI uri) {
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
	}

	/** One {@code GET} over TLS to the resolved address, with SNI and hostname verification. */
	private static Answer probe(InetAddress address, String host, String target) throws IOException {
		SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
		try (Socket raw = new Socket()) {
			raw.connect(new InetSocketAddress(address, 443), TIMEOUT_MILLIS);
			raw.setSoTimeout(TIMEOUT_MILLIS);
			try (SSLSocket socket = (SSLSocket) factory.createSocket(raw, host, 443, true)) {
				SSLParameters parameters = socket.getSSLParameters();
				parameters.setEndpointIdentificationAlgorithm("HTTPS");
				parameters.setServerNames(List.of(new SNIHostName(host)));
				socket.setSSLParameters(parameters);
				socket.startHandshake();
				OutputStream out = socket.getOutputStream();
				out.write(("GET " + target + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: vibefix-preview-check\r\n"
						+ "Accept: text/html\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				out.flush();
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
				String status = in.readLine();
				if (status == null || !status.startsWith("HTTP/") || status.length() < 12) {
					throw new IOException("No HTTP status line");
				}
				int code = Integer.parseInt(status.substring(9, 12));
				String location = null;
				// Headers only, at most 100 lines; the body is never read.
				for (int line = 0; line < 100; line++) {
					String header = in.readLine();
					if (header == null || header.isEmpty()) {
						break;
					}
					if (header.regionMatches(true, 0, "Location:", 0, 9)) {
						location = header.substring(9).strip();
					}
				}
				return new Answer(code, location);
			}
		}
		catch (NumberFormatException ex) {
			throw new IOException("No HTTP status code", ex);
		}
	}

	private static JsonNode result(boolean reachable, @Nullable Integer status, @Nullable String reason) {
		ObjectNode output = JsonNodeFactory.instance.objectNode();
		output.put("reachable", reachable);
		if (status != null) {
			output.put("status", status);
		}
		if (reason != null) {
			output.put("reason", reason);
		}
		return output;
	}

}
