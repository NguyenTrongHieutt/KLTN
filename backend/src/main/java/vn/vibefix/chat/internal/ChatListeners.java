package vn.vibefix.chat.internal;

import java.util.List;
import java.util.Map;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.chat.internal.Chats.Kind;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J4 opens the Job Chat and the Crew Chat (FR-46, FR-77, AD-5) and tells the Customer and the Mentor
 * in-app of the Job Chat; J9 and J10 make both read-only (AD-9) and store masked every message sent as
 * written despite the warning (FR-66, {@link ChatRedaction}). Each is replay-safe through
 * {@link ProcessedEvents}.
 */
@Component
class ChatListeners {

	static final String OPENED = "chat.chat_opened";

	private final Chats chats;

	private final ChatRedaction redaction;

	private final JobPosts posts;

	private final Notifications notifications;

	private final ProcessedEvents processedEvents;

	ChatListeners(Chats chats, ChatRedaction redaction, JobPosts posts, Notifications notifications,
			ProcessedEvents processedEvents) {
		this.chats = chats;
		this.redaction = redaction;
		this.posts = posts;
		this.notifications = notifications;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean opens = "J4".equals(event.transition());
		boolean closes = "J9".equals(event.transition()) || "J10".equals(event.transition());
		if ((!opens && !closes)
				|| !this.processedEvents.markProcessed("chat.ChatListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		if (closes) {
			this.chats.close(event.jobId());
			this.redaction.redact(event.jobId());
			return;
		}
		this.posts.post(event.jobId()).ifPresent(post -> {
			this.chats.open(post.jobId(), post.productId(), Kind.CREW);
			this.chats.open(post.jobId(), post.productId(), Kind.JOB).ifPresent(chat -> {
				if (post.mentorId() == null) {
					return;
				}
				this.notifications.send(new NotificationRequest(OPENED, chat.id(),
						List.of(new Recipient(post.customerId(), Audience.CUSTOMER),
								new Recipient(post.mentorId(), Audience.MENTOR)),
						Map.of("job", post.title()), "/cong-viec/" + post.jobId() + "/tro-chuyen", post.jobId(), null));
			});
		});
	}

}
