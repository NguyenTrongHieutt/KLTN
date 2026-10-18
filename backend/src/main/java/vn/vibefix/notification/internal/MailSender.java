package vn.vibefix.notification.internal;

import vn.vibefix.notification.internal.EmailTemplate.RenderedEmail;

/**
 * The SMTP port (AD-27): the adapter is {@link SmtpMailSender}, and tests use a fake. It classifies
 * every failure, so the email lane knows whether to retry.
 */
interface MailSender {

	/**
	 * Sends {@code email} to {@code to}.
	 * @throws Rejected when the server refused the message for good (an address it will not accept)
	 * @throws Unavailable when nothing was sent and a later attempt may succeed
	 * @throws OutcomeUnknown when the message may have been sent (a timeout after it was handed over)
	 */
	void send(String to, RenderedEmail email);

	/** The server refused the message for good. */
	final class Rejected extends RuntimeException {

		Rejected(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/** Nothing was sent; a later attempt may succeed. */
	final class Unavailable extends RuntimeException {

		Unavailable(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/** The message may have been sent. */
	final class OutcomeUnknown extends RuntimeException {

		OutcomeUnknown(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
