package vn.vibefix.matching.internal;

import java.util.List;
import java.util.Map;

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
 * {@value #REFUSED} (FR-13, AD-19): every Administrator learns that an Application was refused for a
 * shared person-link signal. It commits in a transaction of its own, so the refusal that follows does
 * not roll it back; it names neither the signal nor the people.
 */
@Component
class ConflictAlerts {

	static final String REFUSED = "matching.conflict_of_interest_refused";

	private final Administrators administrators;

	private final Notifications notifications;

	private final IdGenerator ids;

	ConflictAlerts(Administrators administrators, Notifications notifications, IdGenerator ids) {
		this.administrators = administrators;
		this.notifications = notifications;
		this.ids = ids;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void conflictRefused() {
		List<Recipient> recipients = this.administrators.activeIds()
			.stream()
			.map(id -> new Recipient(id, Audience.ADMIN))
			.toList();
		if (!recipients.isEmpty()) {
			this.notifications.send(new NotificationRequest(REFUSED, this.ids.newId(), recipients, Map.of(), "/quan-tri",
					null, null));
		}
	}

}
