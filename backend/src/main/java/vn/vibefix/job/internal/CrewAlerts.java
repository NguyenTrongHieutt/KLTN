package vn.vibefix.job.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Administrators;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.IdGenerator;

/**
 * Administrator alerts about the Crew (AD-19): a Crew addition refused for a shared person-link signal
 * ({@value #REFUSED}), in a transaction of its own so the refusal does not roll it back and never
 * saying which signal; and two participants of a running Job sharing a signal ({@value #PERSON_LINK}).
 */
@Component
class CrewAlerts {

	static final String REFUSED = "job.conflict_of_interest_refused";

	static final String PERSON_LINK = "job.person_link_alert";

	private final Administrators administrators;

	private final Notifications notifications;

	private final IdGenerator ids;

	CrewAlerts(Administrators administrators, Notifications notifications, IdGenerator ids) {
		this.administrators = administrators;
		this.notifications = notifications;
		this.ids = ids;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void conflictRefused() {
		send(REFUSED, this.ids.newId(), Map.of(), "/quan-tri");
	}

	/** In the listener's transaction; {@code sourceId} keys the alert so a redelivery sends it once. */
	void personLink(UUID sourceId, UUID jobId, String job, String firstRole, String secondRole) {
		send(PERSON_LINK, sourceId, Map.of("job", job, "first", firstRole, "second", secondRole), "/quan-tri/cong-viec/" + jobId);
	}

	private void send(String type, UUID sourceId, Map<String, String> params, String link) {
		List<Recipient> recipients = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!recipients.isEmpty()) {
			this.notifications.send(new NotificationRequest(type, sourceId, recipients, params, link, null, null));
		}
	}

}
