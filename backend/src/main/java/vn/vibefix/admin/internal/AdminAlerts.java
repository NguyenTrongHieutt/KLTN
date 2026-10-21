package vn.vibefix.admin.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.Administrators;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.EmailBudgetAlert;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.OperationFailed;
import vn.vibefix.shared.EventPublicationsStalled;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The Administrator alerts the lower modules only record (UX-DR16, Stories 1.6 and 10.6, the PM's C-7
 * check of 2026-10-21): a failed External Operation, stalled event publications, and today's email budget
 * at its alert share reach every active Administrator in-app and by email. A failed email operation is
 * told in-app only, so one failing SMTP server cannot loop on its own alerts. Each is replay-safe.
 */
@Component
class AdminAlerts {

	static final String OPERATION_FAILED = "operations.operation_failed";

	static final String EMAIL_OPERATION_FAILED = "operations.email_operation_failed";

	static final String PUBLICATIONS_STALLED = "operations.event_publication_stalled";

	static final String EMAIL_BUDGET = "notification.email_budget_80";

	/** The email lane's operation type ({@code notification.EmailOperationHandler}). */
	static final String EMAIL_TYPE = "notification.email";

	private static final String FAILURES = "/quan-tri/thao-tac-loi";

	private final Administrators administrators;

	private final Notifications notifications;

	private final ProcessedEvents processedEvents;

	AdminAlerts(Administrators administrators, Notifications notifications, ProcessedEvents processedEvents) {
		this.administrators = administrators;
		this.notifications = notifications;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onOperationFailed(OperationFailed event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onOperationFailed", event.eventId())) {
			alert(EMAIL_TYPE.equals(event.type()) ? EMAIL_OPERATION_FAILED : OPERATION_FAILED, event.operationId(), Map.of(),
					FAILURES);
		}
	}

	@ApplicationModuleListener
	void onPublicationsStalled(EventPublicationsStalled event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onPublicationsStalled", event.eventId())) {
			alert(PUBLICATIONS_STALLED, event.eventId(), Map.of("n", String.valueOf(event.count())), FAILURES);
		}
	}

	@ApplicationModuleListener
	void onEmailBudget(EmailBudgetAlert event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onEmailBudget", event.eventId())) {
			alert(EMAIL_BUDGET, event.eventId(),
					Map.of("sent", String.valueOf(event.sent()), "budget", String.valueOf(event.budget())), "/quan-tri");
		}
	}

	private void alert(String type, UUID cause, Map<String, String> params, String link) {
		List<Recipient> admins = this.administrators.activeIds()
			.stream()
			.map(admin -> new Recipient(admin, Audience.ADMIN))
			.toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(type, cause, admins, params, link, null, null));
		}
	}

}
