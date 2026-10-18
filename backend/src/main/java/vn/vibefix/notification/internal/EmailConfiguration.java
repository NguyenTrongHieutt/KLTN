package vn.vibefix.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import vn.vibefix.notification.internal.EmailTemplate.RenderedEmail;

/**
 * Chooses the SMTP adapter (AD-27). {@code prod} refuses to start without an SMTP server or the
 * platform address its links need (AD-25); elsewhere, without a server, email waits and retries.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
class EmailConfiguration {

	static final String SMTP_USERNAME = "smtp-username";

	static final String SMTP_PASSWORD = "smtp-password";

	private static final Logger log = LoggerFactory.getLogger(EmailConfiguration.class);

	@Bean
	MailSender mailSender(EmailProperties properties, Environment environment) {
		boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
		if (prod && (!properties.hasSmtpHost() || properties.linkBaseUrl() == null || properties.linkBaseUrl().isBlank())) {
			throw new IllegalStateException("Email needs vibefix.email.smtp.host and vibefix.email.link-base-url in"
					+ " production (AD-27); refusing to start");
		}
		if (!properties.hasSmtpHost()) {
			log.warn("vibefix.email.smtp.host is not set: emails wait and retry until an SMTP server is configured");
			return new UnconfiguredMailSender();
		}
		return new SmtpMailSender(properties.smtp(), properties.from(), environment.getProperty(SMTP_USERNAME),
				environment.getProperty(SMTP_PASSWORD));
	}

	/** Sends nothing: there is no server to send to. */
	static final class UnconfiguredMailSender implements MailSender {

		@Override
		public void send(String to, RenderedEmail email) {
			throw new Unavailable("No SMTP server is configured", null);
		}

	}

}
