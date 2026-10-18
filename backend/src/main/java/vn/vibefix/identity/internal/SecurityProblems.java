package vn.vibefix.identity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.shared.ErrorCode;

/**
 * Problem Details for what Spring Security refuses before any controller runs (AD-23): a missing
 * sign-in answers {@code 401} without a code, a refused request (a missing or wrong CSRF token)
 * {@code 403 FORBIDDEN}, and a sign-in flood {@code 429 RATE_LIMITED} with {@code Retry-After}.
 */
final class SecurityProblems {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private SecurityProblems() {
	}

	static void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
			@Nullable ErrorCode code) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "about:blank");
		body.put("title", status.getReasonPhrase());
		body.put("status", status.value());
		body.put("instance", request.getRequestURI());
		if (code != null) {
			body.put("code", code.name());
		}
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(JSON.writeValueAsString(body));
	}

}
