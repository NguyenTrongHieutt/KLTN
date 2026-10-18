package vn.vibefix.notification.internal;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;

import org.jspecify.annotations.Nullable;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import vn.vibefix.notification.internal.EmailTemplate.RenderedEmail;

/**
 * The SMTP adapter (AD-27): one multipart message, plain text and HTML, in UTF-8, from the configured
 * sender. The provider (Resend or Brevo in production, Mailpit on the dev stack) is configuration;
 * its credentials are the secret files {@code smtp-username} and {@code smtp-password} (AD-22).
 * <p>
 * The {@link JavaMailSenderImpl} is not a bean, so Spring Boot adds no mail health indicator: an
 * unreachable provider delays email, it does not take the application down.
 */
final class SmtpMailSender implements MailSender {

	private final JavaMailSenderImpl mail;

	private final String from;

	SmtpMailSender(EmailProperties.Smtp smtp, String from, @Nullable String username, @Nullable String password) {
		this.from = from;
		this.mail = new JavaMailSenderImpl();
		this.mail.setHost(smtp.host());
		this.mail.setPort(smtp.port());
		this.mail.setDefaultEncoding(StandardCharsets.UTF_8.name());
		Properties properties = this.mail.getJavaMailProperties();
		properties.setProperty("mail.smtp.connectiontimeout", Long.toString(smtp.connectTimeout().toMillis()));
		properties.setProperty("mail.smtp.timeout", Long.toString(smtp.timeout().toMillis()));
		properties.setProperty("mail.smtp.writetimeout", Long.toString(smtp.timeout().toMillis()));
		properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(smtp.starttls()));
		properties.setProperty("mail.smtp.starttls.required", Boolean.toString(smtp.starttls()));
		if (username != null && !username.isBlank()) {
			this.mail.setUsername(username.strip());
			this.mail.setPassword(password == null ? "" : password.strip());
			properties.setProperty("mail.smtp.auth", "true");
		}
	}

	@Override
	public void send(String to, RenderedEmail email) {
		MimeMessage message = this.mail.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
					StandardCharsets.UTF_8.name());
			helper.setFrom(this.from);
			helper.setTo(to);
			helper.setSubject(email.subject());
			helper.setText(email.text(), email.html());
		}
		catch (MessagingException | IllegalArgumentException ex) {
			throw new Rejected("The message could not be built", ex);
		}
		try {
			this.mail.send(message);
		}
		catch (MailException ex) {
			throw classify(ex);
		}
	}

	/**
	 * A refused recipient (SMTP 5xx on RCPT) is final; a timeout once the server accepted the
	 * connection may mean the message went out; everything else (no connection, 4xx, authentication)
	 * sent nothing.
	 */
	static RuntimeException classify(MailException ex) {
		List<Throwable> causes = new ArrayList<>();
		causes.add(ex);
		if (ex instanceof MailSendException send) {
			causes.addAll(send.getFailedMessages().values());
		}
		for (Throwable cause : causes) {
			for (Throwable current = cause; current != null; current = current.getCause()) {
				if (current instanceof SendFailedException failed && hasAny(failed.getInvalidAddresses())) {
					return new Rejected("The server refused the recipient", ex);
				}
			}
		}
		for (Throwable cause : causes) {
			for (Throwable current = cause; current != null; current = current.getCause()) {
				if (current instanceof SocketTimeoutException timeout && !isConnectTimeout(timeout)) {
					return new OutcomeUnknown("The server stopped answering", ex);
				}
			}
		}
		return new Unavailable("The server took no message", ex);
	}

	/** No connection was made, so nothing can have been sent. */
	private static boolean isConnectTimeout(SocketTimeoutException timeout) {
		String message = timeout.getMessage();
		return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("connect");
	}

	private static boolean hasAny(Address @Nullable [] addresses) {
		return addresses != null && addresses.length > 0;
	}

}
