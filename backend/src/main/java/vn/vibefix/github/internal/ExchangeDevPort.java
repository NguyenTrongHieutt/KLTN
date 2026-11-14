package vn.vibefix.github.internal;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The dev stack's own port for the Actions exchange (AD-44, the recorded Dev-first exception): GitHub's runs
 * reach the exchange through a cloudflared quick tunnel, which points at
 * {@code vibefix.github.exchange.dev-port} (8098) rather than the whole backend. The port opens only on the
 * {@code dev} profile and while {@code vibefix.github.exchange.public-url} names the tunnel, and on it a filter
 * answers 404 to everything but {@code POST} {@value ActionsExchangeController#PATH} and {@code POST}
 * {@value ActionsReportController#PATH}, the handover copy's path report (Story 23.8a). Compose publishes it on
 * 127.0.0.1 only.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
class ExchangeDevPort {

	/** An extra Tomcat connector on the dev port, while the tunnel is set. */
	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> exchangeDevPortConnector(GitHubProperties properties) {
		return factory -> {
			GitHubProperties.Exchange exchange = properties.exchange();
			if (exchange.publicUrl() != null && exchange.devPort() > 0) {
				Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
				connector.setPort(exchange.devPort());
				factory.addAdditionalConnectors(connector);
			}
		};
	}

	@Bean
	ExchangeOnlyFilter exchangeOnlyFilter(GitHubProperties properties) {
		return new ExchangeOnlyFilter(properties.exchange().devPort());
	}

	/** On the dev port, only the two {@code POST}s a run makes pass; everything else is 404. */
	@Order(Ordered.HIGHEST_PRECEDENCE + 1)
	static class ExchangeOnlyFilter extends OncePerRequestFilter {

		private final int port;

		ExchangeOnlyFilter(int port) {
			this.port = port;
		}

		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			return this.port <= 0 || request.getLocalPort() != this.port;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if ("POST".equals(request.getMethod()) && (ActionsExchangeController.PATH.equals(request.getRequestURI())
					|| ActionsReportController.PATH.equals(request.getRequestURI()))) {
				chain.doFilter(request, response);
				return;
			}
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}

	}

}
