package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;

/**
 * {@code POST /github/actions/exchange} (AD-44, AD-20's caller "Actions run"): a GitHub Actions run trades its
 * OIDC ID token for its correlation's token. It carries no session and no CSRF token, so the gate lets anyone
 * in and the ID token proves the caller ({@link ActionsExchange}). The body is {@code {id_token, correlation}},
 * at most {@value #MAX_BODY_BYTES} bytes (413 above). It answers 200 {@code {token, expires_at}} with
 * {@code Cache-Control: no-store}; a refusal is 403 Problem Details with {@code FORBIDDEN} and its reason, and
 * a call over the abuse limits 429 {@code RATE_LIMITED} with {@code Retry-After}. Neither the body nor the token
 * is ever logged.
 */
@RestController
@Access(Gate.PUBLIC)
class ActionsExchangeController {

	static final String PATH = "/github/actions/exchange";

	static final int MAX_BODY_BYTES = 16 * 1024;

	private final ActionsExchange exchange;

	ActionsExchangeController(ActionsExchange exchange) {
		this.exchange = exchange;
	}

	@PostMapping(PATH)
	ResponseEntity<Map<String, String>> exchange(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(MAX_BODY_BYTES + 1);
		}
		if (body.length > MAX_BODY_BYTES) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
		}
		InstallationToken token = this.exchange.exchange(body, request.getRemoteAddr());
		Map<String, String> issued = new LinkedHashMap<>();
		issued.put("token", token.value());
		issued.put("expires_at", token.expiresAt().toString());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(issued);
	}

}
