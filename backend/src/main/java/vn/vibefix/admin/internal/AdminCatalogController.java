package vn.vibefix.admin.internal;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.CatalogAdministration;
import vn.vibefix.catalog.CatalogAdministration.CategoryRow;
import vn.vibefix.catalog.CatalogAdministration.Saved;
import vn.vibefix.catalog.CatalogAdministration.SkillInput;
import vn.vibefix.catalog.CatalogAdministration.SkillPage;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * ADM-12 (FR-72, FR-63, AD-8, AD-38): the Skill Categories and Skills. Each command calls {@code catalog},
 * which records the change with its old and new values, and audits it in the same transaction. There is
 * no delete endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin/catalog")
@Access(Gate.ADMINISTRATOR)
class AdminCatalogController {

	record CatalogCategoryView(UUID id, String name, int displayOrder, boolean active, int activeSkills) {
	}

	record CatalogSkillView(UUID id, UUID categoryId, String categoryName, String name, String hint,
			List<String> matchLanguages, List<String> matchTools, boolean active) {
	}

	record CatalogSkillsView(List<CatalogSkillView> items, long total, int page, int size) {
	}

	record SkillRequest(@NotNull UUID categoryId, @NotBlank @Size(max = 60) String name, @NotBlank @Size(max = 160) String hint,
			@Nullable @Size(max = 20) List<String> matchLanguages, @Nullable @Size(max = 20) List<String> matchTools) {
	}

	record NameRequest(@NotBlank @Size(max = 60) String name) {
	}

	record ActiveRequest(@NotNull Boolean active) {
	}

	record OrderRequest(@NotNull @Size(max = 200) List<UUID> ids) {
	}

	private final CatalogAdministration catalog;

	private final AuditLog auditLog;

	private final CurrentCaller callers;

	AdminCatalogController(CatalogAdministration catalog, AuditLog auditLog, CurrentCaller callers) {
		this.catalog = catalog;
		this.auditLog = auditLog;
		this.callers = callers;
	}

	@GetMapping("/categories")
	List<CatalogCategoryView> categories() {
		return this.catalog.categories().stream().map(AdminCatalogController::view).toList();
	}

	@PostMapping("/categories")
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	List<CatalogCategoryView> createCategory(@Valid @RequestBody NameRequest body) {
		audit("admin.skill_category_created", this.catalog.createCategory(administrator(), body.name()), "catalog.skill_category");
		return categories();
	}

	@PutMapping("/categories/{categoryId}")
	@Transactional
	List<CatalogCategoryView> renameCategory(@PathVariable UUID categoryId, @Valid @RequestBody NameRequest body) {
		audit("admin.skill_category_renamed", this.catalog.renameCategory(administrator(), categoryId, body.name()),
				"catalog.skill_category");
		return categories();
	}

	@PostMapping("/categories/{categoryId}/active")
	@Transactional
	List<CatalogCategoryView> setCategoryActive(@PathVariable UUID categoryId, @Valid @RequestBody ActiveRequest body) {
		audit("admin.skill_category_active", this.catalog.setCategoryActive(administrator(), categoryId, body.active()),
				"catalog.skill_category");
		return categories();
	}

	@PutMapping("/categories/order")
	@Transactional
	List<CatalogCategoryView> reorder(@Valid @RequestBody OrderRequest body) {
		Saved saved = this.catalog.reorderCategories(administrator(), body.ids());
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator()), "admin.skill_categories_reordered",
				AuditObject.of("catalog.change", saved.changeId())));
		return categories();
	}

	@GetMapping("/skills")
	CatalogSkillsView skills(@RequestParam(required = false) @Nullable String q,
			@RequestParam(required = false) @Nullable UUID categoryId, @RequestParam(required = false) @Nullable Boolean active,
			@RequestParam(defaultValue = "0") int page) {
		SkillPage found = this.catalog.skills(q, categoryId, active, page);
		return new CatalogSkillsView(found.items()
			.stream()
			.map(skill -> new CatalogSkillView(skill.id(), skill.categoryId(), skill.categoryName(), skill.name(), skill.hint(),
					skill.matchLanguages(), skill.matchTools(), skill.active()))
			.toList(), found.total(), found.page(), found.size());
	}

	@PostMapping("/skills")
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	CatalogSkillsView createSkill(@Valid @RequestBody SkillRequest body) {
		audit("admin.skill_created", this.catalog.createSkill(administrator(), input(body)), "catalog.skill");
		return skills(null, body.categoryId(), null, 0);
	}

	@PutMapping("/skills/{skillId}")
	@Transactional
	CatalogSkillsView updateSkill(@PathVariable UUID skillId, @Valid @RequestBody SkillRequest body) {
		audit("admin.skill_updated", this.catalog.updateSkill(administrator(), skillId, input(body)), "catalog.skill");
		return skills(null, body.categoryId(), null, 0);
	}

	@PostMapping("/skills/{skillId}/active")
	@Transactional
	CatalogSkillsView setSkillActive(@PathVariable UUID skillId, @Valid @RequestBody ActiveRequest body) {
		audit("admin.skill_active", this.catalog.setSkillActive(administrator(), skillId, body.active()), "catalog.skill");
		return skills(null, null, null, 0);
	}

	private void audit(String action, Saved saved, String type) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator()), action, AuditObject.of(type, saved.id()),
				AuditObject.of("catalog.change", saved.changeId())));
	}

	private static SkillInput input(SkillRequest body) {
		return new SkillInput(body.categoryId(), body.name(), body.hint(),
				body.matchLanguages() == null ? List.of() : body.matchLanguages(),
				body.matchTools() == null ? List.of() : body.matchTools());
	}

	private static CatalogCategoryView view(CategoryRow row) {
		return new CatalogCategoryView(row.id(), row.name(), row.displayOrder(), row.active(), row.activeSkills());
	}

	private UUID administrator() {
		return this.callers.get().requireAccountId();
	}

}
