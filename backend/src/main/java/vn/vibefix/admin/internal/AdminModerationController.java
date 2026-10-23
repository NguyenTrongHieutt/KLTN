package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ContentModeration;
import vn.vibefix.shared.ContentModeration.Item;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-6 (FR-60, FR-63, AD-2, UX-DR16): the newest Products, Open Job Posts, Pages, and Ratings, visible or
 * hidden, and "Ẩn" with the reason the author reads, through {@link ContentHiding}: the owning module hides
 * and filters, and the action is audited and told to the author.
 */
@RestController
@RequestMapping("/api/v1/admin/moderation")
@Access(Gate.ADMINISTRATOR)
class AdminModerationController {

	static final int LIMIT = 50;

	/** @param authorName who reads the reason, as ADM-2 names them */
	record ModerationItemView(UUID id, String kind, String title, @Nullable String excerpt, @Nullable String authorName,
			Instant createdAt, @Nullable Instant hiddenAt, @Nullable String hiddenReason) {
	}

	record HideRequest(@NotBlank @Size(max = 500) String reason) {
	}

	private final ContentHiding hiding;

	private final AccountAdministration accounts;

	private final CurrentCaller callers;

	AdminModerationController(ContentHiding hiding, AccountAdministration accounts, CurrentCaller callers) {
		this.hiding = hiding;
		this.accounts = accounts;
		this.callers = callers;
	}

	@GetMapping
	List<ModerationItemView> items(@RequestParam(defaultValue = "PRODUCT") String kind, @RequestParam(defaultValue = "false") boolean hidden) {
		ContentModeration moderation = this.hiding.moderation(kind).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return moderation.items(hidden, LIMIT).stream().map(item -> view(kind, item)).toList();
	}

	/** @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_HIDDEN} */
	@PostMapping("/{kind}/{contentId}/hide")
	ModerationItemView hide(@PathVariable String kind, @PathVariable UUID contentId, @Valid @RequestBody HideRequest body) {
		return view(kind, this.hiding.hide(kind, contentId, body.reason(), this.callers.get().requireAccountId()));
	}

	private ModerationItemView view(String kind, Item item) {
		String author = item.authorId() == null ? null
				: this.accounts.account(item.authorId()).map(AccountAdministration.Account::name).orElse(null);
		return new ModerationItemView(item.id(), kind, item.title(), item.excerpt(), author, item.createdAt(), item.hiddenAt(),
				item.hiddenReason());
	}

}
