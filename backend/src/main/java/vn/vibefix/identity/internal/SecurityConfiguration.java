package vn.vibefix.identity.internal;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import vn.vibefix.shared.ErrorCode;

/**
 * Spring Security does authentication, sessions, CSRF, and the GitHub OAuth2 login (AD-19).
 * Authorization is not here: every request passes Spring, and each handler's {@code @Access} gate and
 * its module's policy decide (AD-20), so access stays denied by default in one place.
 * <ul>
 * <li>Sessions are server-side (Spring Session JDBC, {@code platform}), with the cookie of
 * {@link #cookieSerializer()}.</li>
 * <li>CSRF is cookie-to-header for the SPA ({@code XSRF-TOKEN} / {@code X-XSRF-TOKEN}).</li>
 * <li>Spring's header writers are off: {@code SecurityHeadersFilter} is the one source (AD-21).</li>
 * <li>Refusals are Problem Details ({@link SecurityProblems}).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

	static final String SESSION_COOKIE = "VIBEFIX_SESSION";

	/**
	 * The session cookie (AD-19): {@code HttpOnly}, {@code Secure} (browsers accept it on
	 * http://localhost, which they treat as secure), and {@code SameSite=Lax}. Declared here, not as
	 * {@code server.servlet.session.cookie.*}, so it holds in every kind of context.
	 */
	@Bean
	CookieSerializer cookieSerializer() {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName(SESSION_COOKIE);
		serializer.setCookiePath("/");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(true);
		serializer.setSameSite("Lax");
		return serializer;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, GitHubSignIn gitHub, SignIns signIns,
			Accounts accounts, SecurityContextRepository contexts, IdentityProperties properties, Clock clock)
			throws Exception {
		http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
			.securityContext(context -> context.securityContextRepository(contexts))
			// AD-12: GitHub's webhooks carry no session; their HMAC signature proves the sender.
			.csrf(csrf -> csrf.spa().ignoringRequestMatchers("/webhooks"))
			.headers(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, ex) -> SecurityProblems.write(request, response,
						HttpStatus.UNAUTHORIZED, null))
				.accessDeniedHandler((request, response, ex) -> SecurityProblems.write(request, response,
						HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN)))
			.addFilterAfter(new CallerFilter(accounts, clock), SecurityContextHolderFilter.class)
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			.addFilterBefore(new SignInRateLimiter(clock, properties.signInLimitPerMinute()),
					OAuth2AuthorizationRequestRedirectFilter.class)
			.addFilterBefore(new SignInStartFilter(gitHub.registration().isPresent()),
					OAuth2AuthorizationRequestRedirectFilter.class);
		gitHub.registration().ifPresent(registration -> {
			try {
				http.oauth2Login(login -> login.loginPage("/dang-nhap")
					.clientRegistrationRepository(new InMemoryClientRegistrationRepository(registration))
					.authorizedClientService(new NoStoreAuthorizedClients.Service())
					.authorizedClientRepository(new NoStoreAuthorizedClients.Repository())
					.userInfoEndpoint(userInfo -> userInfo.userService(new TokenCapturingUserService(gitHub.userEmailsUri())))
					.successHandler(new SignInSuccessHandler(signIns, contexts))
					.failureHandler((request, response, ex) -> {
						// The OAuth error code only (for example invalid_state_parameter), never a token.
						log.warn("GitHub sign-in failed: {}", ex.getMessage());
						response.sendRedirect("/dang-nhap?loi=that-bai");
					}));
			}
			catch (Exception ex) {
				throw new IllegalStateException("Could not configure the GitHub sign-in", ex);
			}
		});
		return http.build();
	}

}
