package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ContentModeration;
import vn.vibefix.shared.ContentModeration.Item;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-6 (FR-60, FR-63, AD-2, UX-DR16): the newest Products, Open Job Posts, Pages, and Ratings, visible or
 * hidden, and "Ẩn" with the reason the author reads. The owning module hides and filters; this records
 * the action in the Audit Log and tells the author.
 */
@RestController
@RequestMapping("/api/v1/admin/moderation")
@Access(Gate.ADMINISTRATOR)
class AdminModerationController {

	static final String CONTENT_HIDDEN = "admin.content_hidden";

	static final int LIMIT = 50;

	private static final Map<String, String> LABELS = Map.of("PRODUCT", "Sản phẩm", "JOB_POST", "Tin tuyển", "PAGE", "Trang",
			"RATING", "Đánh giá");

	private static final Map<String, String> AUDIT_OBJECTS = Map.of("PRODUCT", "product.product", "JOB_POST", "job.job",
			"PAGE", "work.page", "RATING", "reputation.rating");

	/** @param authorName who reads the reason, as ADM-2 names them */
	record ModerationItemView(UUID id, String kind, String title, @Nullable String excerpt, @Nullable String authorName,
			Instant createdAt, @Nullable Instant hiddenAt, @Nullable String hiddenReason) {
	}

	record HideRequest(@NotBlank @Size(max = 500) String reason) {
	}

	private final Map<String, ContentModeration> kinds;

	private final AccountAdministration accounts;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final CurrentCaller callers;

	AdminModerationController(List<ContentModeration> kinds, AccountAdministration accounts, Notifications notifications,
			AuditLog auditLog, CurrentCaller callers) {
		this.kinds = kinds.stream().collect(Collectors.toMap(ContentModeration::kind, Function.identity()));
		this.accounts = accounts;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.callers = callers;
	}

	@GetMapping
	List<ModerationItemView> items(@RequestParam(defaultValue = "PRODUCT") String kind, @RequestParam(defaultValue = "false") boolean hidden) {
		ContentModeration moderation = moderation(kind);
		return moderation.items(hidden, LIMIT).stream().map(item -> view(kind, item)).toList();
	}

	/** @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_HIDDEN} */
	@PostMapping("/{kind}/{contentId}/hide")
	@Transactional
	ModerationItemView hide(@PathVariable String kind, @PathVariable UUID contentId, @Valid @RequestBody HideRequest body) {
		ContentModeration moderation = moderation(kind);
		String reason = body.reason().strip();
		Item item = moderation.hide(contentId, reason).orElseThrow(() -> {
			boolean exists = moderation.items(true, Integer.MAX_VALUE).stream().anyMatch(found -> found.id().equals(contentId));
			return exists ? new ApiException(ErrorCode.CONFLICT, AdminReason.ALREADY_HIDDEN) : new ApiException(ErrorCode.NOT_FOUND);
		});
		this.auditLog.record(AuditEntry.of(AuditActor.user(this.callers.get().requireAccountId()), CONTENT_HIDDEN,
				AuditObject.of(AUDIT_OBJECTS.get(kind), contentId)));
		if (item.authorId() != null) {
			this.notifications.send(new NotificationRequest(CONTENT_HIDDEN, contentId,
					List.of(new Recipient(item.authorId(), Audience.SUBJECT)),
					Map.of("kind", LABELS.get(kind), "title", item.title(), "reason", reason), "/", null, item.authorId()));
		}
		return view(kind, item);
	}

	private ContentModeration moderation(String kind) {
		ContentModeration moderation = this.kinds.get(kind);
		if (moderation == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return moderation;
	}

	private ModerationItemView view(String kind, Item item) {
		String author = item.authorId() == null ? null
				: this.accounts.account(item.authorId()).map(AccountAdministration.Account::name).orElse(null);
		return new ModerationItemView(item.id(), kind, item.title(), item.excerpt(), author, item.createdAt(), item.hiddenAt(),
				item.hiddenReason());
	}

}
