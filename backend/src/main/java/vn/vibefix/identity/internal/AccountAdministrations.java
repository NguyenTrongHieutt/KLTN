package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.Role;

/**
 * {@link AccountAdministration} (FR-5, FR-59, AD-19, SG-11): each command locks the account row, records an
 * {@code identity.account_action}, and is audited with its reason. A lock deletes the account's sessions by
 * principal name, as an erasure does, so its next request is anonymous; the caller filter refuses a locked
 * account on every request anyway. The person links are read from the person-link signals, by kind only.
 */
@Component
class AccountAdministrations implements AccountAdministration {

	static final String LOCKED = "identity.account_locked";

	private static final String NAME = Profiles.NAME;

	private static final String FROM = """
			FROM identity.account a
			LEFT JOIN identity.profile p ON p.account_id = a.id
			LEFT JOIN identity.verification v ON v.account_id = a.id AND v.kind = 'STUDENT' AND v.ended_at IS NULL
			""";

	/** @param role absent for a Google account, which has none (Story 19.1) */
	private record Target(UUID id, boolean administrator, @Nullable Role role, boolean locked) {
	}

	private final JdbcClient jdbc;

	private final Notifications notifications;

	private final ApplicationEventPublisher events;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final ObjectProvider<DataRequests> dataRequests;

	AccountAdministrations(JdbcClient jdbc, Notifications notifications, ApplicationEventPublisher events,
			AuditLog auditLog, IdGenerator ids, Clock clock, ObjectProvider<DataRequests> dataRequests) {
		this.dataRequests = dataRequests;
		this.jdbc = jdbc;
		this.notifications = notifications;
		this.events = events;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Page search(Query query) {
		StringBuilder where = new StringBuilder(" WHERE a.kind = 'USER' AND a.erased_at IS NULL");
		Map<String, Object> params = new HashMap<>();
		LocalDate today = today();
		params.put("today", today);
		if (query.role() != null) {
			where.append(" AND a.role = :role");
			params.put("role", query.role().name());
		}
		if (query.text() != null && !query.text().isBlank()) {
			// The declared student name is searched too, though the name rule does not show it.
			where.append(" AND platform.vi_normalize(coalesce(" + NAME + ", '') || ' ' || coalesce(p.student_name, '')")
				.append(" || ' ' || coalesce(a.github_login, '')")
				.append(" || ' ' || coalesce(p.contact_email, '') || ' ' || coalesce(a.google_email, '')")
				.append(" || ' ' || coalesce(a.github_email, ''))")
				.append(" LIKE '%' || platform.vi_normalize(:text) || '%' ESCAPE '\\'");
			params.put("text", escapeLike(query.text().strip()));
		}
		if (query.ids() != null) {
			if (query.ids().isEmpty()) {
				return new Page(List.of(), 0, query.page(), PAGE_SIZE);
			}
			where.append(" AND a.id IN (:ids)");
			params.put("ids", List.copyOf(query.ids()));
		}
		if (query.status() != null) {
			switch (query.status()) {
				case LOCKED -> where.append(" AND a.locked_at IS NOT NULL");
				case VERIFIED -> where.append(" AND v.valid_until >= :today");
			}
		}
		long total = this.jdbc.sql("SELECT count(*) " + FROM + where).params(params).query(Long.class).single();
		int page = Math.max(0, query.page());
		params.put("offset", page * PAGE_SIZE);
		params.put("size", PAGE_SIZE);
		List<Row> rows = this.jdbc.sql("SELECT a.id, a.role, a.github_login, a.locked_at, a.created_at, " + NAME
				+ " AS name, v.valid_until " + FROM + where + " ORDER BY a.created_at DESC, a.id LIMIT :size OFFSET :offset")
			.params(params)
			.query((rs, n) -> {
				Role role = role(rs);
				LocalDate validUntil = rs.getObject("valid_until", LocalDate.class);
				// A verified student is any person's account with a live, valid row (Story 19.4).
				boolean verified = validUntil != null && !validUntil.isBefore(today);
				String status = verified ? "VERIFIED" : role == Role.STUDENT ? "UNVERIFIED" : null;
				return new Row(rs.getObject("id", UUID.class), role, rs.getString("name"), rs.getString("github_login"),
						rs.getTimestamp("locked_at") != null, status, rs.getTimestamp("created_at").toInstant());
			})
			.list();
		return new Page(rows, total, page, PAGE_SIZE);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> names(Collection<UUID> accountIds) {
		Map<UUID, String> names = new HashMap<>();
		if (accountIds.isEmpty()) {
			return names;
		}
		this.jdbc.sql("SELECT a.id, coalesce(" + NAME + ", ac.display_name) AS name " + FROM
				+ " LEFT JOIN identity.administrator_credential ac ON ac.account_id = a.id WHERE a.id IN (:ids)")
			.param("ids", List.copyOf(accountIds))
			.query((rs, n) -> {
				if (rs.getString("name") != null) {
					names.put(rs.getObject("id", UUID.class), rs.getString("name"));
				}
				return null;
			})
			.list();
		return names;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Account> account(UUID accountId) {
		LocalDate today = today();
		return this.jdbc.sql("SELECT a.id, a.role, a.github_login, a.created_at, a.signed_in_at, a.locked_at, a.lock_reason, "
				+ "coalesce(p.contact_email, a.google_email, a.github_email) AS email, " + NAME + " AS name, "
				+ "v.provider, v.verified_at, v.valid_until " + FROM
				+ " WHERE a.id = :id AND a.kind = 'USER' AND a.erased_at IS NULL")
			.param("id", accountId)
			.query((rs, n) -> {
				LocalDate validUntil = rs.getObject("valid_until", LocalDate.class);
				Verification verification = validUntil == null ? null
						: new Verification(VerificationRows.Provider.valueOf(rs.getString("provider")).method(),
								rs.getTimestamp("verified_at").toInstant(), validUntil, !validUntil.isBefore(today));
				return new Account(rs.getObject("id", UUID.class), role(rs), rs.getString("name"),
						rs.getString("github_login"), rs.getString("email"), rs.getTimestamp("created_at").toInstant(),
						instant(rs, "signed_in_at"), instant(rs, "locked_at"), rs.getString("lock_reason"), verification,
						history(accountId));
			})
			.optional();
	}

	/**
	 * Every other account holding a signal of the same kind, key version, and HMAC (AD-19), as
	 * {@code PersonSignals.sharesPersonSignal} compares them. An erased account's signals are kept 24
	 * months, so it is listed, without a name. Moved here from the retired ADM-13 (Story 19.7).
	 */
	@Override
	@Transactional(readOnly = true)
	public List<PersonLink> personLinks(UUID accountId) {
		return this.jdbc.sql("SELECT a.id, CASE WHEN a.erased_at IS NULL THEN " + NAME + " END AS name, l.kinds,"
				+ " a.locked_at IS NOT NULL AS locked, a.erased_at IS NOT NULL AS erased, a.created_at\n" + """
				FROM (SELECT theirs.account_id, array_agg(DISTINCT theirs.kind ORDER BY theirs.kind) AS kinds
				      FROM identity.person_signal mine
				      JOIN identity.person_signal theirs ON theirs.kind = mine.kind
				           AND theirs.key_version = mine.key_version AND theirs.hmac = mine.hmac
				           AND theirs.account_id <> mine.account_id
				      WHERE mine.account_id = :id
				      GROUP BY theirs.account_id) l
				JOIN identity.account a ON a.id = l.account_id
				LEFT JOIN identity.profile p ON p.account_id = a.id
				ORDER BY a.created_at, a.id
				""")
			.param("id", accountId)
			.query((rs, n) -> new PersonLink(rs.getObject("id", UUID.class), rs.getString("name"),
					Arrays.stream((String[]) rs.getArray("kinds").getArray()).map(SignalKind::valueOf).toList(),
					rs.getBoolean("locked"), rs.getBoolean("erased"), rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	@Override
	@Transactional
	public List<PersonLink> viewPersonLinks(UUID administrator, UUID accountId) {
		List<PersonLink> links = personLinks(accountId);
		// Who may be the same person is itself read under audit, whatever the read finds (AD-8, AD-19).
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "identity.person_links_viewed",
				AuditObject.of("identity.account", accountId)));
		return links;
	}

	@Override
	@Transactional
	public void lock(UUID administrator, UUID accountId, String reason) {
		Target target = target(accountId);
		if (target.administrator()) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ADMINISTRATOR_ACCOUNT);
		}
		if (target.locked()) {
			throw new ApiException(ErrorCode.CONFLICT, IdentityReason.ALREADY_LOCKED);
		}
		Instant now = now();
		String text = reason.strip();
		this.jdbc.sql("UPDATE identity.account SET locked_at = :now, lock_reason = :reason WHERE id = :id")
			.param("id", accountId)
			.param("now", Timestamp.from(now))
			.param("reason", text)
			.update();
		UUID action = record(accountId, "LOCKED", text, administrator, now);
		// The account's sessions end at once (SG-11): the next request is anonymous.
		this.jdbc.sql("DELETE FROM platform.spring_session WHERE principal_name = :name")
			.param("name", accountId.toString())
			.update();
		this.events.publishEvent(new AccountLocked(this.ids.newId(), accountId, target.role()));
		this.notifications.send(new NotificationRequest(LOCKED, action, List.of(new Recipient(accountId, Audience.SUBJECT)),
				Map.of("reason", text), "/", null, accountId));
		audit(administrator, "identity.account_locked", accountId, action);
	}

	@Override
	@Transactional
	public void unlock(UUID administrator, UUID accountId, String reason) {
		Target target = target(accountId);
		if (!target.locked()) {
			throw new ApiException(ErrorCode.CONFLICT, IdentityReason.NOT_LOCKED);
		}
		Instant now = now();
		this.jdbc.sql("UPDATE identity.account SET locked_at = NULL, lock_reason = NULL WHERE id = :id")
			.param("id", accountId)
			.update();
		UUID action = record(accountId, "UNLOCKED", reason.strip(), administrator, now);
		audit(administrator, "identity.account_unlocked", accountId, action);
	}

	@Override
	public Optional<DeletionRequest> deletionRequest(UUID accountId) {
		DataRequests.State state = this.dataRequests.getObject().state(accountId);
		return state.requestedAt() == null ? Optional.empty()
				: Optional.of(new DeletionRequest(state.requestedAt(), state.completedAt(), state.blockers()));
	}

	@Override
	public Result erase(UUID administrator, UUID accountId, String stepUpCode) {
		return this.dataRequests.getObject().erase(administrator, accountId, stepUpCode);
	}

	/** The account to act on, locked; {@code NOT_FOUND} for one erased, gone, or an Administrator's on unlock. */
	private Target target(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id, kind, role, locked_at IS NOT NULL AS locked FROM identity.account
				WHERE id = :id AND erased_at IS NULL FOR UPDATE
				""")
			.param("id", accountId)
			.query((rs, n) -> new Target(rs.getObject("id", UUID.class), "ADMINISTRATOR".equals(rs.getString("kind")),
					role(rs), rs.getBoolean("locked")))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private UUID record(UUID accountId, String action, String reason, UUID administrator, Instant now) {
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO identity.account_action (id, account_id, action, reason, acted_by, acted_at)
				VALUES (:id, :account, :action, :reason, :by, :now)
				""")
			.param("id", id)
			.param("account", accountId)
			.param("action", action)
			.param("reason", reason)
			.param("by", administrator)
			.param("now", Timestamp.from(now))
			.update();
		return id;
	}

	private List<Action> history(UUID accountId) {
		return this.jdbc.sql("""
				SELECT x.action, x.reason, x.ground, c.display_name, x.acted_at FROM identity.account_action x
				LEFT JOIN identity.administrator_credential c ON c.account_id = x.acted_by
				WHERE x.account_id = :id ORDER BY x.acted_at DESC, x.id DESC
				""")
			.param("id", accountId)
			.query((rs, n) -> new Action(rs.getString("action"), rs.getString("reason"), rs.getString("ground"),
					rs.getString("display_name"), rs.getTimestamp("acted_at").toInstant()))
			.list();
	}

	private void audit(UUID administrator, String action, UUID accountId, UUID actionId) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), action, AuditObject.of("identity.account", accountId),
				AuditObject.of("identity.account_action", actionId)));
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), StudentVerifications.VIETNAM);
	}

	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static @Nullable Role role(ResultSet rs) throws SQLException {
		String role = rs.getString("role");
		return role == null ? null : Role.valueOf(role);
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp at = rs.getTimestamp(column);
		return at == null ? null : at.toInstant();
	}

}
