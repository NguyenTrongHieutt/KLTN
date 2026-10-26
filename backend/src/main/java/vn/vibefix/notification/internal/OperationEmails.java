package vn.vibefix.notification.internal;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.EmailRequest;
import vn.vibefix.notification.Emails;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;

/**
 * {@link Emails} as External Operations (AD-6): the payload names the template and the class; the
 * address and the parameters are personal data, purged once the email completes (AD-24). A request
 * is checked before it is recorded, so a bad one fails the caller, not the lane.
 */
@Component
class OperationEmails implements Emails {

	private final ExternalOperations operations;

	private final EmailTemplates templates;

	private final Clock clock;

	OperationEmails(ExternalOperations operations, EmailTemplates templates, Clock clock) {
		this.operations = operations;
		this.templates = templates;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void send(EmailRequest request) {
		this.templates.require(request.type()).requireValues(request.parameters());
		String to = address(request.to());
		Map<String, Object> payload = new HashMap<>();
		payload.put(EmailOperationHandler.TEMPLATE, request.type());
		payload.put("class", request.emailClass().name());
		if (request.emailClass() == EmailClass.ONE_TIME_CODE) {
			// A code's email is useless once the code expired: the lane aborts it then (EmailOperationHandler).
			payload.put(EmailOperationHandler.REQUESTED_AT, this.clock.instant().toString());
		}
		Map<String, Object> personal = new HashMap<>();
		personal.put(EmailOperationHandler.TO, to);
		personal.put(EmailOperationHandler.PARAMETERS, request.parameters());
		if (request.link() != null) {
			if (!NotificationRegistry.LINK.matcher(request.link()).matches()) {
				throw new IllegalArgumentException("An email links to a platform path, not '" + request.link() + "'");
			}
			// A link with a secret in it is purged with the address; any other stays with the payload.
			(request.privateLink() ? personal : payload).put(EmailOperationHandler.LINK, request.link());
		}
		this.operations.request(new OperationRequest(EmailOperationHandler.TYPE, request.causingRecordId(), payload,
				personal, request.recipientAccountId(), request.emailClass().priority()));
	}

	/** One plain address, as RFC 822 strictly allows it; never a display name or a list. */
	private static String address(String to) {
		if (to.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("An email address holds no control characters");
		}
		try {
			InternetAddress parsed = new InternetAddress(to.strip(), true);
			if (parsed.getPersonal() != null || parsed.isGroup()) {
				throw new IllegalArgumentException("An email goes to one plain address");
			}
			return parsed.getAddress();
		}
		catch (AddressException ex) {
			throw new IllegalArgumentException("Not an email address", ex);
		}
	}

}
