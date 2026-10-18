package vn.vibefix.shared.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import vn.vibefix.shared.CurrentCaller;

/**
 * Puts the {@link AccessGateInterceptor} in front of every API and webhook handler (AD-20), and
 * provides the {@link CurrentCaller} it and the modules' policies read.
 */
@Configuration(proxyBeanMethods = false)
class AccessWebConfiguration implements WebMvcConfigurer {

	/** The paths whose handlers must declare a gate; {@code MatrixCoverageIT} checks the same prefixes. */
	static final String[] GATED_PATHS = { "/api/**", "/webhooks/**" };

	private final CurrentCaller callers = new RequestCurrentCaller();

	@Bean
	CurrentCaller currentCaller() {
		return this.callers;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AccessGateInterceptor(this.callers)).addPathPatterns(GATED_PATHS);
	}

}
