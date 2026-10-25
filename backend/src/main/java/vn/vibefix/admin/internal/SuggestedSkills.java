package vn.vibefix.admin.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.CatalogAdministration;
import vn.vibefix.catalog.CatalogAdministration.Saved;
import vn.vibefix.catalog.CatalogAdministration.SkillInput;
import vn.vibefix.catalog.Skill;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.AdvisoryLocks;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;
import vn.vibefix.shared.Role;

/**
 * Skill suggestions (FR-73, FR-72, AD-34, AD-38, SG-50): a Customer, a Mentor, or a Student suggests a Skill the
 * picker does not find, at most 5 a day. A name an active Skill already has, ignoring accents and case, is
 * refused with that Skill; one an inactive Skill has is queued and flagged. On ADM-12 an Administrator approves a
 * suggestion into a Skill through the catalog command, which adds it to no profile or Job Post, or declines it
 * with a reason; either way the suggester is told.
 */
@Component
class SuggestedSkills {

	/** At most this many suggestions per account per day (AD-34). */
	static final int DAILY_LIMIT = 5;

	static final String APPROVED = "admin.skill_suggestion_approved";

	static final String DECLINED = "admin.skill_suggestion_declined";

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	/**
	 * One suggestion.
	 * @param status {@code OPEN}, {@code APPROVED}, or {@code DECLINED}
	 * @param inactiveSkillId the inactive Skill of the same name, flagged for the Administrator
	 * @param skillId the Skill an approval created
	 */
	record Suggestion(UUID id, @Nullable UUID suggesterId, Role suggesterRole, String name, @Nullable UUID categoryId,
			@Nullable String note, @Nullable UUID inactiveSkillId, String status, Instant createdAt, @Nullable Instant decidedAt,
			@Nullable String declineReason, @Nullable UUID skillId) {
	}

	private static final String COLUMNS = """
			id, suggester_account_id, suggester_role, name, category_id, note, inactive_skill_id, status, created_at,
			decided_at, decline_reason, skill_id
			""";

	private final JdbcClient jdbc;

	private final Catalog catalog;

	private final CatalogAdministration administration;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	SuggestedSkills(JdbcClient jdbc, Catalog catalog, CatalogAdministration administration, Notifications notifications,
			AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.administration = administration;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * "Đề xuất thêm kỹ năng này".
	 * @throws ApiException {@code FORBIDDEN} for an account with no role; {@code NOT_FOUND} for an Administrator;
	 * {@code VALIDATION}; {@code CONFLICT
	 * SKILL_ALREADY_EXISTS} with {@code skillId} and {@code skillName}; {@code RATE_LIMITED SKILL_SUGGESTION_LIMIT}
	 */
	@Transactional
	Suggestion suggest(Caller caller, String name, @Nullable UUID categoryId, @Nullable String note) {
		Role role = caller.role();
		if (role == null) {
			// A Google account has no role until Epic 20: refused, as every role check refuses it (AD-20).
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		if (role != Role.CUSTOMER && role != Role.MENTOR && role != Role.STUDENT) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		String text = name.strip();
		String said = note == null || note.isBlank() ? null : note.strip();
		if (text.length() < 2 || text.length() > 60 || said != null && said.length() > 500) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		if (categoryId != null && this.catalog.active().stream().noneMatch(category -> category.id().equals(categoryId))) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Skill existing = this.catalog.byName(text).orElse(null);
		if (existing != null && existing.active()) {
			throw new ApiException(ErrorCode.CONFLICT, AdminReason.SKILL_ALREADY_EXISTS,
					Map.of("skillId", existing.id().toString(), "skillName", existing.name()));
		}
		UUID suggester = caller.requireAccountId();
		AdvisoryLocks.lockAccount(this.jdbc, suggester);
		Instant now = this.clock.instant();
		Instant today = LocalDate.ofInstant(now, VIETNAM).atStartOfDay(VIETNAM).toInstant();
		long sent = this.jdbc.sql("SELECT count(*) FROM admin.skill_suggestion WHERE suggester_account_id = :id AND created_at >= :since")
			.param("id", suggester)
			.param("since", Timestamp.from(today))
			.query(Long.class)
			.single();
		if (sent >= DAILY_LIMIT) {
			Instant tomorrow = LocalDate.ofInstant(now, VIETNAM).plusDays(1).atStartOfDay(VIETNAM).toInstant();
			throw new RateLimitedException(Duration.between(now, tomorrow), AdminReason.SKILL_SUGGESTION_LIMIT);
		}
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO admin.skill_suggestion (id, suggester_account_id, suggester_role, name, category_id, note,
				    inactive_skill_id, status, created_at)
				VALUES (:id, :suggester, :role, :name, :category, :note, :inactive, 'OPEN', :now)
				""")
			.param("id", id)
			.param("suggester", suggester)
			.param("role", role.name())
			.param("name", text)
			.param("category", categoryId)
			.param("note", said)
			.param("inactive", existing == null ? null : existing.id())
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(suggester), "admin.skill_suggested",
				AuditObject.of("admin.skill_suggestion", id)));
		return find(id);
	}

	/** The suggester's own suggestions, newest first. */
	@Transactional(readOnly = true)
	List<Suggestion> mine(UUID accountId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM admin.skill_suggestion WHERE suggester_account_id = :id ORDER BY created_at DESC, id")
			.param("id", accountId)
			.query(SuggestedSkills::suggestion)
			.list();
	}

	/** ADM-12 "Đề xuất": the open suggestions, oldest first, then the decided ones, newest first; at most 200. */
	@Transactional(readOnly = true)
	List<Suggestion> queue() {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				FROM admin.skill_suggestion
				ORDER BY status = 'OPEN' DESC, CASE WHEN status = 'OPEN' THEN created_at END ASC, created_at DESC, id
				LIMIT 200
				""").query(SuggestedSkills::suggestion).list();
	}

	/** The open suggestions, for ADM-1. */
	@Transactional(readOnly = true)
	List<Suggestion> open() {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM admin.skill_suggestion WHERE status = 'OPEN' ORDER BY created_at, id")
			.query(SuggestedSkills::suggestion)
			.list();
	}

	/**
	 * "Duyệt": the Skill is created through the catalog command, audited, and the suggester told.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_DECIDED}; the catalog's refusals
	 */
	@Transactional
	Suggestion approve(UUID administrator, UUID suggestionId, SkillInput input) {
		Suggestion suggestion = lockedOpen(suggestionId);
		Saved saved = this.administration.createSkill(administrator, input);
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "admin.skill_created",
				AuditObject.of("catalog.skill", saved.id()), AuditObject.of("catalog.change", saved.changeId())));
		this.jdbc.sql("""
				UPDATE admin.skill_suggestion SET status = 'APPROVED', skill_id = :skill, decided_by_admin_id = :admin,
				    decided_at = :now
				WHERE id = :id
				""")
			.param("skill", saved.id())
			.param("admin", administrator)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", suggestionId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), APPROVED,
				AuditObject.of("admin.skill_suggestion", suggestionId)));
		tell(suggestion, APPROVED, Map.of("name", input.name().strip()));
		return find(suggestionId);
	}

	/**
	 * "Từ chối" with the reason the suggester reads.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_DECIDED}; {@code VALIDATION}
	 */
	@Transactional
	Suggestion decline(UUID administrator, UUID suggestionId, String reason) {
		String text = reason.strip();
		if (text.isEmpty() || text.length() > 500) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Suggestion suggestion = lockedOpen(suggestionId);
		this.jdbc.sql("""
				UPDATE admin.skill_suggestion SET status = 'DECLINED', decline_reason = :reason, decided_by_admin_id = :admin,
				    decided_at = :now
				WHERE id = :id
				""")
			.param("reason", text)
			.param("admin", administrator)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", suggestionId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), DECLINED,
				AuditObject.of("admin.skill_suggestion", suggestionId)));
		tell(suggestion, DECLINED, Map.of("name", suggestion.name(), "reason", text));
		return find(suggestionId);
	}

	private Suggestion lockedOpen(UUID suggestionId) {
		Suggestion suggestion = this.jdbc.sql("SELECT " + COLUMNS + " FROM admin.skill_suggestion WHERE id = :id FOR UPDATE")
			.param("id", suggestionId)
			.query(SuggestedSkills::suggestion)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!"OPEN".equals(suggestion.status())) {
			throw new ApiException(ErrorCode.CONFLICT, AdminReason.ALREADY_DECIDED);
		}
		return suggestion;
	}

	private void tell(Suggestion suggestion, String type, Map<String, String> params) {
		if (suggestion.suggesterId() != null) {
			this.notifications.send(new NotificationRequest(type, suggestion.id(),
					List.of(new Recipient(suggestion.suggesterId(), Audience.SUBJECT)), params, "/ho-so", null,
					suggestion.suggesterId()));
		}
	}

	private Suggestion find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM admin.skill_suggestion WHERE id = :id")
			.param("id", id)
			.query(SuggestedSkills::suggestion)
			.single();
	}

	private static Suggestion suggestion(ResultSet rs, int n) throws SQLException {
		Timestamp decided = rs.getTimestamp("decided_at");
		return new Suggestion(rs.getObject("id", UUID.class), rs.getObject("suggester_account_id", UUID.class),
				Role.valueOf(rs.getString("suggester_role")), rs.getString("name"), rs.getObject("category_id", UUID.class),
				rs.getString("note"), rs.getObject("inactive_skill_id", UUID.class), rs.getString("status"),
				rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant(),
				rs.getString("decline_reason"), rs.getObject("skill_id", UUID.class));
	}

}
