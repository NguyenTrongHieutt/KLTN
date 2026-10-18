package vn.vibefix.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A browser for MockMvc: it keeps the cookies the application sets (Spring Session's
 * {@code VIBEFIX_SESSION} and the {@code XSRF-TOKEN} CSRF cookie) and sends them back. A command sends
 * the CSRF token in {@code X-XSRF-TOKEN}, as the SPA does. Each browser comes from its own client
 * address, so tests never share a sign-in rate-limit window.
 */
public final class Browser {

	private static final AtomicInteger addresses = new AtomicInteger();

	private final MockMvc mvc;

	private final Map<String, Cookie> cookies = new LinkedHashMap<>();

	private final String remoteAddress;

	public Browser(MockMvc mvc) {
		this.mvc = mvc;
		int n = addresses.incrementAndGet();
		this.remoteAddress = "10.0." + (n / 250) + "." + (n % 250 + 1);
	}

	/** A request as a browser sends a page load or a query: with the cookies, without the CSRF header. */
	public MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
		return send(request, false);
	}

	/** A command (POST, PUT, PATCH, DELETE) with the CSRF header the SPA sends. */
	public MvcResult command(MockHttpServletRequestBuilder request) throws Exception {
		return send(request, true);
	}

	/** A command without the CSRF header, to see it refused. */
	public MvcResult commandWithoutCsrf(MockHttpServletRequestBuilder request) throws Exception {
		return send(request, false);
	}

	public String remoteAddress() {
		return this.remoteAddress;
	}

	private MvcResult send(MockHttpServletRequestBuilder request, boolean csrfHeader) throws Exception {
		request.with(servletRequest -> {
			servletRequest.setRemoteAddr(this.remoteAddress);
			return servletRequest;
		});
		if (!this.cookies.isEmpty()) {
			request.cookie(this.cookies.values().toArray(Cookie[]::new));
		}
		Cookie csrf = this.cookies.get("XSRF-TOKEN");
		if (csrfHeader && csrf != null) {
			request.header("X-XSRF-TOKEN", csrf.getValue());
		}
		MvcResult result = this.mvc.perform(request).andReturn();
		for (Cookie cookie : result.getResponse().getCookies()) {
			keep(cookie.getName(), cookie.getValue(), cookie.getMaxAge() == 0);
		}
		// Spring Session writes its cookie as a raw Set-Cookie header, which MockMvc does not parse.
		for (String header : result.getResponse().getHeaders("Set-Cookie")) {
			String[] parts = header.split(";");
			String[] nameValue = parts[0].split("=", 2);
			boolean expired = header.toLowerCase(java.util.Locale.ROOT).contains("max-age=0");
			keep(nameValue[0].strip(), nameValue.length > 1 ? nameValue[1].strip() : "", expired || nameValue.length < 2
					|| nameValue[1].isBlank());
		}
		return result;
	}

	private void keep(String name, String value, boolean expired) {
		if (expired) {
			this.cookies.remove(name);
		}
		else {
			this.cookies.put(name, new Cookie(name, value));
		}
	}

}
