package vn.vibefix.admin.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubConfigurationDrift;
import vn.vibefix.github.GitHubExchangeIssued;
import vn.vibefix.github.GitHubExchangeRefused;
import vn.vibefix.github.GitHubExchangeRevokeUnknown;
import vn.vibefix.github.GitHubExchangeRevoked;
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
 * check of 2026-10-21): a failed External Operation, stalled event publications, today's email budget at
 * its alert share, and a GitHub App unlike AD-10 (SG-18, Story 7.1) reach every active Administrator
 * in-app and by email. A failed email operation is
 * told in-app only, so one failing SMTP server cannot loop on its own alerts. Each is replay-safe.
 * <p>
 * The Actions token exchange (AD-44, FR-63, Story 23.4) is audited here from {@code github}'s events, since
 * {@code github} may depend on nothing but {@code shared}: every token issued and revoked, every refusal after
 * verification ({@code github.exchange_refused_<reason>}), and a token left unrevoked by a restart, each on the
 * object {@value #EXCHANGE_CORRELATION}. A refusal and an unrevoked token also alert every Administrator, once per
 * correlation and reason, naming the correlation so its audit entries can be looked up.
 */
@Component
class AdminAlerts {

	static final String OPERATION_FAILED = "operations.operation_failed";

	static final String EXCHANGE_REFUSED = "github.exchange_refused";

	static final String EXCHANGE_REVOKE_UNKNOWN = "github.exchange_revoke_unknown";

	/** The audited object of every exchange entry: {@code github}'s correlation row. */
	static final String EXCHANGE_CORRELATION = "github.exchange_correlation";

	static final String EMAIL_OPERATION_FAILED = "operations.email_operation_failed";

	static final String PUBLICATIONS_STALLED = "operations.event_publication_stalled";

	static final String EMAIL_BUDGET = "notification.email_budget_80";

	static final String CONFIGURATION_DRIFT = "github.app_configuration_drift";

	/** The email lane's operation type ({@code notification.EmailOperationHandler}). */
	static final String EMAIL_TYPE = "notification.email";

	private static final String FAILURES = "/quan-tri/thao-tac-loi";

	private final Administrators administrators;

	private final Notifications notifications;

	private final ProcessedEvents processedEvents;

	private final AuditLog auditLog;

	AdminAlerts(Administrators administrators, Notifications notifications, ProcessedEvents processedEvents,
			AuditLog auditLog) {
		this.administrators = administrators;
		this.notifications = notifications;
		this.processedEvents = processedEvents;
		this.auditLog = auditLog;
	}

	/** AD-44: a token issued to an Actions run, audited. */
	@ApplicationModuleListener
	void onExchangeIssued(GitHubExchangeIssued event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onExchangeIssued", event.eventId())) {
			audit("github.exchange_issued", event.correlationId());
		}
	}

	/** AD-44: a token revoked once its run ended, audited. */
	@ApplicationModuleListener
	void onExchangeRevoked(GitHubExchangeRevoked event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onExchangeRevoked", event.eventId())) {
			audit("github.exchange_revoked", event.correlationId());
		}
	}

	/** AD-44: a verified ID token refused, audited with its reason, and every Administrator alerted. */
	@ApplicationModuleListener
	void onExchangeRefused(GitHubExchangeRefused event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onExchangeRefused", event.eventId())) {
			audit("github.exchange_refused_" + event.reason().code(), event.correlationId());
			UUID cause = UUID.nameUUIDFromBytes(
					(EXCHANGE_REFUSED + ":" + event.correlationId() + ":" + event.reason()).getBytes(StandardCharsets.UTF_8));
			alert(EXCHANGE_REFUSED, cause, Map.of("operation", event.operation().code(), "reason", event.reason().code(),
					"correlation", event.correlationId().toString()), "/quan-tri");
		}
	}

	/** AD-44: a token a restart left unrevoked, audited, and every Administrator alerted. */
	@ApplicationModuleListener
	void onExchangeRevokeUnknown(GitHubExchangeRevokeUnknown event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onExchangeRevokeUnknown", event.eventId())) {
			audit("github.exchange_revoke_unknown", event.correlationId());
			UUID cause = UUID.nameUUIDFromBytes(
					(EXCHANGE_REVOKE_UNKNOWN + ":" + event.correlationId()).getBytes(StandardCharsets.UTF_8));
			alert(EXCHANGE_REVOKE_UNKNOWN, cause,
					Map.of("operation", event.operation().code(), "correlation", event.correlationId().toString()), "/quan-tri");
		}
	}

	private void audit(String action, UUID correlation) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action, AuditObject.of(EXCHANGE_CORRELATION, correlation)));
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

	/** SG-18: an App unlike AD-10, once a day per App. */
	@ApplicationModuleListener
	void onConfigurationDrift(GitHubConfigurationDrift event) {
		if (this.processedEvents.markProcessed("admin.AdminAlerts.onConfigurationDrift", event.eventId())) {
			UUID cause = UUID.nameUUIDFromBytes((event.app() + ":" + event.day()).getBytes(StandardCharsets.UTF_8));
			alert(CONFIGURATION_DRIFT, cause,
					Map.of("app", event.app() == App.PLATFORM ? "Platform App" : "Merge App", "differences",
							String.join("; ", event.differences())),
					"/quan-tri");
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
