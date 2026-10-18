package vn.vibefix.notification.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import vn.vibefix.notification.internal.EmailTemplate.RenderedEmail;

/**
 * The fake SMTP adapter of the tests (spine Testing): it keeps every message instead of sending it,
 * and can be told to fail the next sends the way a server would.
 */
public final class RecordingMailSender implements MailSender {

	/** One message as it would have gone out. */
	public record Sent(String to, String subject, String text, String html) {
	}

	private final List<Sent> sent = new ArrayList<>();

	private final Deque<Supplier<RuntimeException>> failures = new ArrayDeque<>();

	@Override
	public synchronized void send(String to, RenderedEmail email) {
		Supplier<RuntimeException> failure = this.failures.poll();
		if (failure != null) {
			RuntimeException ex = failure.get();
			if (ex instanceof OutcomeUnknown) {
				// The server took the message and then stopped answering.
				this.sent.add(new Sent(to, email.subject(), email.text(), email.html()));
			}
			throw ex;
		}
		this.sent.add(new Sent(to, email.subject(), email.text(), email.html()));
	}

	public synchronized List<Sent> sent() {
		return List.copyOf(this.sent);
	}

	public synchronized RecordingMailSender rejectNext() {
		this.failures.add(() -> new Rejected("refused", null));
		return this;
	}

	public synchronized RecordingMailSender failNext() {
		this.failures.add(() -> new Unavailable("down", null));
		return this;
	}

	public synchronized RecordingMailSender loseAnswerNext() {
		this.failures.add(() -> new OutcomeUnknown("timeout", null));
		return this;
	}

	public synchronized void reset() {
		this.sent.clear();
		this.failures.clear();
	}

}
