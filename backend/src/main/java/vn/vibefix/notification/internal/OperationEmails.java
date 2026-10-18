package vn.vibefix.notification.internal;

import java.util.HashMap;
import java.util.Map;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

	OperationEmails(ExternalOperations operations, EmailTemplates templates) {
		this.operations = operations;
		this.templates = templates;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void send(EmailRequest request) {
		this.templates.require(request.type()).requireValues(request.parameters());
		String to = address(request.to());
		Map<String, Object> payload = new HashMap<>();
		payload.put(EmailOperationHandler.TEMPLATE, request.type());
		payload.put("class", request.emailClass().name());
		if (request.link() != null) {
			if (!NotificationRegistry.LINK.matcher(request.link()).matches()) {
				throw new IllegalArgumentException("An email links to a platform path, not '" + request.link() + "'");
			}
			payload.put(EmailOperationHandler.LINK, request.link());
		}
		this.operations.request(new OperationRequest(EmailOperationHandler.TYPE, request.causingRecordId(), payload,
				Map.of(EmailOperationHandler.TO, to, EmailOperationHandler.PARAMETERS, request.parameters()),
				request.recipientAccountId(), request.emailClass().priority()));
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
