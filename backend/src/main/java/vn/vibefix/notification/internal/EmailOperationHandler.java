package vn.vibefix.notification.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.internal.EmailTemplate.RenderedEmail;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;

/**
 * Sends one email (AD-6, AD-27): the {@code notification.email} operations of the email lane. SMTP
 * cannot be read back, so an ambiguous attempt is simply tried again: email is at least once. A
 * one-time code's email that is still waiting (retries, the daily budget) once its code expired is
 * aborted instead, which purges its address, with no Administrator alert (AD-42).
 */
@Component
class EmailOperationHandler implements OperationHandler {

	static final String TYPE = "notification.email";

	static final String TEMPLATE = "template";

	static final String TO = "to";

	static final String PARAMETERS = "parameters";

	static final String LINK = "link";

	/** When a one-time code's email was requested, in its payload. */
	static final String REQUESTED_AT = "requestedAt";

	private static final Logger log = LoggerFactory.getLogger(EmailOperationHandler.class);

	private final EmailTemplates templates;

	private final EmailBudget budget;

	private final MailSender mail;

	private final EmailProperties properties;

	private final Clock clock;

	EmailOperationHandler(EmailTemplates templates, EmailBudget budget, MailSender mail, EmailProperties properties,
			Clock clock) {
		this.templates = templates;
		this.budget = budget;
		this.mail = mail;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.EMAIL;
	}

	@Override
	public Outcome run(OperationRun run) {
		Map<String, Object> personal = run.personalData();
		if (personal == null) {
			// The recipient's account was erased before the email went out.
			return new Outcome.Obsolete("RECIPIENT_ERASED");
		}
		if (EmailClass.ONE_TIME_CODE.name().equals(run.payload().get("class")) && run.payload().get(REQUESTED_AT) != null
				&& !this.clock.instant()
					.isBefore(Instant.parse(String.valueOf(run.payload().get(REQUESTED_AT)))
						.plus(EmailClass.ONE_TIME_CODE_LIFETIME))) {
			return new Outcome.Aborted("CODE_EXPIRED");
		}
		String type = String.valueOf(run.payload().get(TEMPLATE));
		Optional<EmailTemplate> template = this.templates.find(type);
		if (template.isEmpty()) {
			return new Outcome.Aborted("TEMPLATE_MISSING");
		}
		Map<String, String> values = strings(personal.get(PARAMETERS));
		RenderedEmail email;
		try {
			Object link = run.payload().containsKey(LINK) ? run.payload().get(LINK) : personal.get(LINK);
			email = template.get().render(values, this.properties.linkBaseUrl() == null ? "" : this.properties.linkBaseUrl(),
					link == null ? null : String.valueOf(link));
		}
		catch (IllegalArgumentException ex) {
			return new Outcome.Aborted("TEMPLATE_VALUE_MISSING");
		}
		Optional<LocalDate> taken = this.budget.take();
		if (taken.isEmpty()) {
			log.info("Email {} waits: today's budget is used up", type);
			return new Outcome.Deferred(this.budget.nextDay());
		}
		try {
			this.mail.send(String.valueOf(personal.get(TO)), email);
			return Outcome.succeeded();
		}
		catch (MailSender.Rejected ex) {
			this.budget.giveBack(taken.get());
			return new Outcome.Aborted("EMAIL_REJECTED");
		}
		catch (MailSender.Unavailable ex) {
			this.budget.giveBack(taken.get());
			return new Outcome.Transient("SMTP_UNAVAILABLE");
		}
		catch (MailSender.OutcomeUnknown ex) {
			return new Outcome.Ambiguous("SMTP_OUTCOME_UNKNOWN");
		}
	}

	private static Map<String, String> strings(Object value) {
		Map<String, String> strings = new HashMap<>();
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, item) -> strings.put(String.valueOf(key), item == null ? null : String.valueOf(item)));
		}
		return strings;
	}

}
