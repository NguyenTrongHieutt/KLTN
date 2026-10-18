package vn.vibefix.notification.internal;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vibefix.email.*}: how vibefix email goes out (AD-27, AD-34).
 * @param from the sender, for example {@code vibefix <no-reply@vibefix.vn>}
 * @param dailyBudget emails per UTC day the provider allows; past it, email waits for the next day
 * @param alertPercent the share of the budget whose crossing records {@code notification.email_budget_80}
 * @param linkBaseUrl the platform's address, before a template's {@code link:} path
 */
@ConfigurationProperties("vibefix.email")
record EmailProperties(String from, int dailyBudget, int alertPercent, @Nullable String linkBaseUrl, Smtp smtp) {

	/**
	 * @param host the SMTP server; without one, {@code prod} refuses to start and other profiles send nothing
	 * @param starttls whether the connection must switch to TLS before anything is sent
	 */
	record Smtp(@Nullable String host, int port, boolean starttls, Duration connectTimeout, Duration timeout) {
	}

	EmailProperties {
		if (dailyBudget < 1) {
			throw new IllegalArgumentException("vibefix.email.daily-budget must be at least 1");
		}
		if (alertPercent < 1 || alertPercent > 100) {
			throw new IllegalArgumentException("vibefix.email.alert-percent must be between 1 and 100");
		}
	}

	/** The send that crosses the alert share: the 80th of 100. */
	int alertThreshold() {
		return (int) Math.ceil(this.dailyBudget * this.alertPercent / 100.0);
	}

	boolean hasSmtpHost() {
		return this.smtp.host() != null && !this.smtp.host().isBlank();
	}

}
