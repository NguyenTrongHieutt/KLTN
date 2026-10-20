package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AdministratorStepUp;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.settings.SettingsAdministration;
import vn.vibefix.settings.SettingsAdministration.Change;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-10 (FR-61, FR-63, SG-10, UX-DR42): the rule values within their bounds, the school allowlist, and
 * the latest changes; saving takes the step-up and is audited with each change's old and new value. The
 * Team Issue values stay off the screen until Team Issues are built (Epic 12).
 */
@RestController
@RequestMapping("/api/v1/admin/rules")
@Access(Gate.ADMINISTRATOR)
class AdminRulesController {

	/** Rules whose features are not built yet (Epic 12). */
	static final Set<Rule> NOT_BUILT = EnumSet.of(Rule.TEAM_ISSUE_ANSWER_DAYS, Rule.TEAM_ISSUE_ESCALATION_DAYS);

	record RuleView(Rule key, int value, int min, int max) {
	}

	record RuleChangeView(String kind, @Nullable Rule rule, @Nullable Integer oldValue, @Nullable Integer newValue,
			@Nullable String domain, Instant changedAt) {
	}

	record RulesView(List<RuleView> rules, List<String> schoolDomains, List<RuleChangeView> history) {
	}

	record SaveRulesRequest(@NotNull Map<Rule, Integer> values, @NotNull @Size(max = 200) List<String> schoolDomains,
			@NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	private final Settings settings;

	private final SettingsAdministration administration;

	private final AdministratorStepUp stepUp;

	private final AuditLog auditLog;

	private final CurrentCaller callers;

	private final TransactionTemplate transactions;

	AdminRulesController(Settings settings, SettingsAdministration administration, AdministratorStepUp stepUp,
			AuditLog auditLog, CurrentCaller callers, TransactionTemplate transactions) {
		this.settings = settings;
		this.administration = administration;
		this.stepUp = stepUp;
		this.auditLog = auditLog;
		this.callers = callers;
		this.transactions = transactions;
	}

	@GetMapping
	RulesView rules() {
		return view();
	}

	/**
	 * Saves after the step-up; a value of a rule not built yet is ignored.
	 * @throws ApiException {@code VALIDATION} before the step-up; the step-up refusal
	 */
	@PutMapping
	RulesView save(@Valid @RequestBody SaveRulesRequest body, HttpServletRequest request) {
		Map<Rule, Integer> values = new LinkedHashMap<>(body.values());
		NOT_BUILT.forEach(values::remove);
		this.administration.validate(values, body.schoolDomains());
		var administrator = this.callers.get().requireAccountId();
		// The step-up's failure count commits with the refusal, so the refusal is thrown after the transaction.
		AdministratorStepUp.Result result = this.transactions.execute(status -> {
			AdministratorStepUp.Result checked = this.stepUp.check(administrator, body.stepUpCode());
			if (checked.passed()) {
				List<Change> changes = this.administration.save(administrator, values, body.schoolDomains());
				if (!changes.isEmpty()) {
					this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "admin.rules_changed", changes.stream()
						.map(change -> AuditObject.of("settings.change", change.id()))
						.toArray(AuditObject[]::new)));
				}
			}
			return checked;
		});
		if (result == null || !result.passed()) {
			if (result != null && result.endSession()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			throw result == null ? new ApiException(ErrorCode.NOT_FOUND) : result.refusal();
		}
		return view();
	}

	private RulesView view() {
		List<RuleView> rules = Arrays.stream(Rule.values())
			.filter(rule -> !NOT_BUILT.contains(rule))
			.map(rule -> new RuleView(rule, this.settings.value(rule), rule.min(), rule.max()))
			.toList();
		List<RuleChangeView> history = this.administration.history(30)
			.stream()
			.map(change -> new RuleChangeView(change.kind(), change.rule(), change.oldValue(), change.newValue(), change.domain(),
					change.changedAt()))
			.toList();
		return new RulesView(rules, this.settings.schoolDomains(), history);
	}

}
