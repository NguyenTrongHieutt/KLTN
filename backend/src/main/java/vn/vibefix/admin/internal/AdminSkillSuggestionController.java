package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.admin.internal.SuggestedSkills.Suggestion;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.CatalogAdministration;
import vn.vibefix.catalog.CatalogAdministration.SkillInput;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * ADM-12's "Đề xuất" tab (FR-73, FR-72, AD-38): the suggestions, open ones first, each with the suggester, the
 * Category they chose, their note, and an inactive Skill of the same name when there is one; "Duyệt", which
 * creates the Skill through the catalog command with the name and Category the Administrator settles and the
 * hint they must add; and "Từ chối" with the reason the suggester reads.
 */
@RestController
@RequestMapping("/api/v1/admin/skill-suggestions")
@Access(Gate.ADMINISTRATOR)
class AdminSkillSuggestionController {

	/** An inactive Skill of the same name: "Dùng lại" may be the better answer. */
	record SuggestedInactiveSkill(UUID id, String name) {
	}

	/** @param status {@code OPEN}, {@code APPROVED}, or {@code DECLINED} */
	record AdminSkillSuggestionView(UUID id, String name, @Nullable UUID categoryId, @Nullable String categoryName,
			@Nullable String note, @Nullable String suggesterName, Instant createdAt, String status,
			@Nullable SuggestedInactiveSkill inactiveSkill, @Nullable Instant decidedAt, @Nullable String declineReason,
			@Nullable UUID skillId) {
	}

	/** "Duyệt": the name and Category as the Administrator settles them, and the hint they must add. */
	record ApproveSuggestionRequest(@NotNull UUID categoryId, @NotBlank @Size(max = 60) String name,
			@NotBlank @Size(max = 160) String hint, @Nullable @Size(max = 20) List<String> matchLanguages,
			@Nullable @Size(max = 20) List<String> matchTools) {
	}

	record DeclineSuggestionRequest(@NotBlank @Size(max = 500) String reason) {
	}

	private final SuggestedSkills suggestions;

	private final CatalogAdministration catalogAdministration;

	private final Catalog catalog;

	private final AccountAdministration accounts;

	private final CurrentCaller callers;

	AdminSkillSuggestionController(SuggestedSkills suggestions, CatalogAdministration catalogAdministration, Catalog catalog,
			AccountAdministration accounts, CurrentCaller callers) {
		this.suggestions = suggestions;
		this.catalogAdministration = catalogAdministration;
		this.catalog = catalog;
		this.accounts = accounts;
		this.callers = callers;
	}

	@GetMapping
	List<AdminSkillSuggestionView> queue() {
		return views(this.suggestions.queue());
	}

	@PostMapping("/{suggestionId}/approve")
	List<AdminSkillSuggestionView> approve(@PathVariable UUID suggestionId, @Valid @RequestBody ApproveSuggestionRequest body) {
		this.suggestions.approve(this.callers.get().requireAccountId(), suggestionId,
				new SkillInput(body.categoryId(), body.name(), body.hint(),
						body.matchLanguages() == null ? List.of() : body.matchLanguages(),
						body.matchTools() == null ? List.of() : body.matchTools()));
		return queue();
	}

	@PostMapping("/{suggestionId}/decline")
	List<AdminSkillSuggestionView> decline(@PathVariable UUID suggestionId, @Valid @RequestBody DeclineSuggestionRequest body) {
		this.suggestions.decline(this.callers.get().requireAccountId(), suggestionId, body.reason());
		return queue();
	}

	private List<AdminSkillSuggestionView> views(List<Suggestion> found) {
		Map<UUID, String> categories = new HashMap<>();
		this.catalogAdministration.categories().forEach(category -> categories.put(category.id(), category.name()));
		Set<UUID> people = new HashSet<>();
		Set<UUID> inactive = new HashSet<>();
		for (Suggestion suggestion : found) {
			if (suggestion.suggesterId() != null) {
				people.add(suggestion.suggesterId());
			}
			if (suggestion.inactiveSkillId() != null) {
				inactive.add(suggestion.inactiveSkillId());
			}
		}
		Map<UUID, String> names = this.accounts.names(people);
		Map<UUID, Skill> skills = new HashMap<>();
		this.catalog.byIds(inactive).forEach(skill -> skills.put(skill.id(), skill));
		return found.stream().map(suggestion -> {
			Skill flagged = suggestion.inactiveSkillId() == null ? null : skills.get(suggestion.inactiveSkillId());
			return new AdminSkillSuggestionView(suggestion.id(), suggestion.name(), suggestion.categoryId(),
					suggestion.categoryId() == null ? null : categories.get(suggestion.categoryId()), suggestion.note(),
					suggestion.suggesterId() == null ? null : names.get(suggestion.suggesterId()), suggestion.createdAt(),
					suggestion.status(),
					flagged == null ? null : new SuggestedInactiveSkill(flagged.id(), flagged.name()), suggestion.decidedAt(),
					suggestion.declineReason(), suggestion.skillId());
		}).toList();
	}

}
