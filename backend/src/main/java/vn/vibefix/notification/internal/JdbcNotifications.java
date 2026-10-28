package vn.vibefix.notification.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.notification.EmailRequest;
import vn.vibefix.notification.Emails;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.NotificationType;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.RecipientAddresses;
import vn.vibefix.shared.IdGenerator;

/**
 * Sends notifications (AD-27): every check first, then one in-app row per recipient and, for types
 * that email, one email per recipient with an address, all in the caller's transaction.
 */
@Component
class JdbcNotifications implements Notifications {

	/** How long a notification is listed, and then kept before it is purged (UX-DR16). */
	static final Duration RETENTION = Duration.ofDays(90);

	private static final Logger log = LoggerFactory.getLogger(JdbcNotifications.class);

	private final JdbcClient jdbc;

	private final NotificationRegistry registry;

	private final NotificationJson json;

	private final Emails emails;

	private final List<RecipientAddresses> addresses;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcNotifications(JdbcClient jdbc, NotificationRegistry registry, NotificationJson json, Emails emails,
			List<RecipientAddresses> addresses, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.registry = registry;
		this.json = json;
		this.emails = emails;
		this.addresses = List.copyOf(addresses);
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void send(NotificationRequest request) {
		NotificationType type = this.registry.requireNotificationType(request.type());
		NotificationRegistry.checkParams(type.key(), type.params(), request.params());
		NotificationRegistry.checkLink(type.key(), request.link());
		if (request.recipients().isEmpty()) {
			throw new IllegalArgumentException(type.key() + " needs at least one recipient");
		}
		for (Recipient recipient : request.recipients()) {
			this.registry.checkRecipient(type.key(), type.audiences(), recipient.accountId(), recipient.audience(),
					request.jobId(), request.subjectRecordId(), request.milestoneId());
		}
		Set<UUID> accounts = new LinkedHashSet<>();
		request.recipients().forEach(recipient -> accounts.add(recipient.accountId()));
		Timestamp now = Timestamp.from(this.clock.instant());
		String params = this.json.write(request.params());
		int withoutAddress = 0;
		for (UUID account : accounts) {
			if (type.channel().inApp()) {
				this.jdbc.sql("""
						INSERT INTO notification.notification
						    (id, account_id, type, causing_record_id, params, link, job_id, created_at)
						VALUES (:id, :account, :type, :cause, CAST(:params AS jsonb), :link, :jobId, :now)
						ON CONFLICT (type, causing_record_id, account_id) DO NOTHING
						""")
					.param("id", this.ids.newId())
					.param("account", account)
					.param("type", type.key())
					.param("cause", request.causingRecordId())
					.param("params", params)
					.param("link", request.link())
					.param("jobId", request.jobId())
					.param("now", now)
					.update();
			}
			if (type.channel().email()) {
				Optional<String> address = address(account);
				if (address.isPresent()) {
					this.emails.send(new EmailRequest(type.key(), emailCause(type.key(), request.causingRecordId(), account),
							account, address.get(), type.emailClass(), request.params(), request.link()));
				}
				else {
					withoutAddress++;
				}
			}
		}
		if (withoutAddress > 0) {
			log.info("{}: {} recipient(s) have no email address and get the in-app item only", type.key(),
					withoutAddress);
		}
	}

	private Optional<String> address(UUID account) {
		return this.addresses.stream().map(lookup -> lookup.emailAddress(account)).flatMap(Optional::stream).findFirst();
	}

	/** The email's own causing record: one message per type, cause and recipient, so a resend joins it. */
	static UUID emailCause(String type, UUID cause, UUID account) {
		return UUID.nameUUIDFromBytes((type + ":" + cause + ":" + account).getBytes(StandardCharsets.UTF_8));
	}

	/** The cutoff before which notifications are neither listed nor kept. */
	static Instant cutoff(Instant now) {
		return now.minus(RETENTION);
	}

}
