package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

/** How SMTP failures are classified for the email lane (AD-6). */
class SmtpMailSenderTest {

	@Test
	void aRefusedRecipientIsFinal() throws Exception {
		SendFailedException refused = new SendFailedException("Invalid Addresses", null, new InternetAddress[0],
				new InternetAddress[0], new InternetAddress[] { new InternetAddress("x@example.com") });

		assertThat(SmtpMailSender.classify(new MailSendException(Map.of(new Object(), refused))))
			.isInstanceOf(MailSender.Rejected.class);
	}

	@Test
	void aTimeoutAfterTheConnectionMayHaveSentTheMessage() {
		MessagingException timedOut = new MessagingException("Exception reading response",
				new SocketTimeoutException("Read timed out"));

		assertThat(SmtpMailSender.classify(new MailSendException("failed", timedOut)))
			.isInstanceOf(MailSender.OutcomeUnknown.class);
	}

	@Test
	void noConnectionAnAuthenticationFailureOrA4xxSentNothing() throws Exception {
		assertThat(SmtpMailSender.classify(new MailSendException("Mail server connection failed",
				new MessagingException("Couldn't connect", new ConnectException("Connection refused")))))
			.isInstanceOf(MailSender.Unavailable.class);
		assertThat(SmtpMailSender.classify(new MailSendException("Mail server connection failed",
				new MessagingException("Couldn't connect", new SocketTimeoutException("Connect timed out")))))
			.isInstanceOf(MailSender.Unavailable.class);
		assertThat(SmtpMailSender.classify(new MailAuthenticationException("535 bad credentials")))
			.isInstanceOf(MailSender.Unavailable.class);
		SendFailedException busy = new SendFailedException("Mailbox busy", null, new InternetAddress[0],
				new InternetAddress[] { new InternetAddress("x@example.com") }, new InternetAddress[0]);
		assertThat(SmtpMailSender.classify(new MailSendException(Map.of(new Object(), busy))))
			.isInstanceOf(MailSender.Unavailable.class);
	}

}
