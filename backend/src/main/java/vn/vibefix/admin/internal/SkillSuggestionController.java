package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.admin.internal.SuggestedSkills.Suggestion;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * "Đề xuất thêm kỹ năng này" (FR-73, AD-34, UX-DR77): any unlocked user suggests a Skill the picker does not
 * find, and reads their own suggestions and how each was decided (Story 20.3). Nobody reads another's, and an
 * Administrator finds nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class SkillSuggestionController {

	record SkillSuggestionRequest(@NotBlank @Size(max = 60) String name, @Nullable UUID categoryId,
			@Nullable @Size(max = 500) String note) {
	}

	/** @param status {@code OPEN}, {@code APPROVED}, or {@code DECLINED} */
	record MySkillSuggestionView(UUID id, String name, String status, Instant createdAt, @Nullable Instant decidedAt,
			@Nullable String declineReason) {
	}

	private final SuggestedSkills suggestions;

	private final CurrentCaller callers;

	SkillSuggestionController(SuggestedSkills suggestions, CurrentCaller callers) {
		this.suggestions = suggestions;
		this.callers = callers;
	}

	@PostMapping("/api/v1/skill-suggestions")
	@ResponseStatus(HttpStatus.CREATED)
	MySkillSuggestionView suggest(@Valid @RequestBody SkillSuggestionRequest body) {
		return view(this.suggestions.suggest(this.callers.get(), body.name(), body.categoryId(), body.note()));
	}

	@GetMapping("/api/v1/skill-suggestions")
	List<MySkillSuggestionView> mine() {
		return this.suggestions.mine(this.suggestions.user(this.callers.get()))
			.stream()
			.map(SkillSuggestionController::view)
			.toList();
	}

	private static MySkillSuggestionView view(Suggestion suggestion) {
		return new MySkillSuggestionView(suggestion.id(), suggestion.name(), suggestion.status(), suggestion.createdAt(),
				suggestion.decidedAt(), suggestion.declineReason());
	}

}
