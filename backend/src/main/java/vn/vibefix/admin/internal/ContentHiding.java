package vn.vibefix.admin.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ContentModeration;
import vn.vibefix.shared.ContentModeration.Item;
import vn.vibefix.shared.ErrorCode;

/**
 * FR-60's one way to hide content, for ADM-6's tabs and for an Abuse Report (FR-69): the owning module hides
 * and filters, and this records the action in the Audit Log and tells the author the reason.
 */
@Component
class ContentHiding {

	static final String CONTENT_HIDDEN = "admin.content_hidden";

	/** The kinds FR-60 hides. */
	static final Set<String> KINDS = Set.of("PRODUCT", "JOB_POST", "PAGE", "RATING");

	private static final Map<String, String> LABELS = Map.of("PRODUCT", "Sản phẩm", "JOB_POST", "Tin tuyển", "PAGE", "Trang",
			"RATING", "Đánh giá");

	private static final Map<String, String> AUDIT_OBJECTS = Map.of("PRODUCT", "product.product", "JOB_POST", "job.job",
			"PAGE", "work.page", "RATING", "reputation.rating");

	private final Map<String, ContentModeration> kinds;

	private final Notifications notifications;

	private final AuditLog auditLog;

	ContentHiding(List<ContentModeration> kinds, Notifications notifications, AuditLog auditLog) {
		this.kinds = kinds.stream().collect(Collectors.toMap(ContentModeration::kind, Function.identity()));
		this.notifications = notifications;
		this.auditLog = auditLog;
	}

	/** The owning module's moderation of {@code kind}, if FR-60 covers it. */
	Optional<ContentModeration> moderation(String kind) {
		return Optional.ofNullable(this.kinds.get(kind));
	}

	/**
	 * Hides the item with the reason its author reads, audited and told.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_HIDDEN}
	 */
	@Transactional
	Item hide(String kind, UUID contentId, String reason, UUID administrator) {
		ContentModeration moderation = moderation(kind).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		String text = reason.strip();
		Item item = moderation.hide(contentId, text).orElseThrow(() -> {
			boolean exists = moderation.items(true, Integer.MAX_VALUE).stream().anyMatch(found -> found.id().equals(contentId));
			return exists ? new ApiException(ErrorCode.CONFLICT, AdminReason.ALREADY_HIDDEN) : new ApiException(ErrorCode.NOT_FOUND);
		});
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), CONTENT_HIDDEN,
				AuditObject.of(AUDIT_OBJECTS.get(kind), contentId)));
		if (item.authorId() != null) {
			this.notifications.send(new NotificationRequest(CONTENT_HIDDEN, contentId,
					List.of(new Recipient(item.authorId(), Audience.SUBJECT)),
					Map.of("kind", LABELS.get(kind), "title", item.title(), "reason", text), "/", null, item.authorId()));
		}
		return item;
	}

}
