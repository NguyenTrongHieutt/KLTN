package vn.vibefix.identity.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AccountAdministration.Result;
import vn.vibefix.identity.AccountErased;
import vn.vibefix.identity.Administrators;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.shared.Role;

/**
 * NFR-11, AD-24, SG-9: a person's own data requests. They download every participant's export of their data,
 * and request their account's deletion once; every Administrator hears of it. The request waits while any
 * participant reports a blocker, and an Administrator confirms it on ADM-2 behind the step-up: every participant
 * erases or anonymizes the account, its sessions end, the sign-in grant is revoked through an operation when a
 * Customer token was kept, and {@link AccountErased} is published.
 */
@Component
class DataRequests {

	static final String DELETION_REQUESTED = "identity.deletion_requested";

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	/**
	 * The person's request as ALL-2 and ADM-2 show it.
	 * @param blockers what keeps it waiting now; none before a request, or once it is completed
	 * @param tokenKept a Customer sign-in token is kept, so the grant is revoked for them at the erasure
	 */
	record State(@Nullable Instant requestedAt, @Nullable Instant completedAt, List<ErasureBlocker> blockers,
			boolean tokenKept) {
	}

	private record Request(UUID id, Instant requestedAt, @Nullable Instant completedAt) {
	}

	private final JdbcClient jdbc;

	private final PersonalData personalData;

	private final Accounts accounts;

	private final StepUps stepUps;

	private final Notifications notifications;

	private final Administrators administrators;

	private final ExternalOperations operations;

	private final ApplicationEventPublisher events;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	DataRequests(JdbcClient jdbc, PersonalData personalData, Accounts accounts, StepUps stepUps, Notifications notifications,
			Administrators administrators, ExternalOperations operations, ApplicationEventPublisher events, AuditLog auditLog,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.personalData = personalData;
		this.accounts = accounts;
		this.stepUps = stepUps;
		this.notifications = notifications;
		this.administrators = administrators;
		this.operations = operations;
		this.events = events;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	State state(UUID accountId) {
		Request request = stored(accountId).orElse(null);
		List<ErasureBlocker> blockers = request == null || request.completedAt() != null ? List.of()
				: this.personalData.erasureBlockers(accountId);
		return new State(request == null ? null : request.requestedAt(), request == null ? null : request.completedAt(),
				blockers, this.accounts.customerToken(accountId).isPresent());
	}

	/** "Tải dữ liệu của tôi": every participant's section for the person, as one JSON document, audited. */
	@Transactional
	byte[] export(UUID accountId) {
		List<PersonalDataSection> sections = this.personalData.export(accountId);
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("exportedAt", this.clock.instant().toString());
		document.put("accountId", accountId.toString());
		document.put("sections", sections);
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.data_exported",
				AuditObject.of("identity.account", accountId)));
		return JSON.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * "Yêu cầu xóa tài khoản": saved once, audited, and told to every active Administrator.
	 * @throws ApiException {@code CONFLICT ALREADY_REQUESTED}
	 */
	@Transactional
	State request(UUID accountId) {
		UUID id = this.ids.newId();
		int saved = this.jdbc.sql("""
				INSERT INTO identity.deletion_request (id, account_id, requested_at) VALUES (:id, :account, :now)
				ON CONFLICT (account_id) DO NOTHING
				""").param("id", id).param("account", accountId).param("now", Timestamp.from(this.clock.instant())).update();
		if (saved == 0) {
			throw new ApiException(ErrorCode.CONFLICT, IdentityReason.ALREADY_REQUESTED);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), DELETION_REQUESTED,
				AuditObject.of("identity.account", accountId)));
		List<Recipient> admins = this.administrators.activeIds()
			.stream()
			.map(admin -> new Recipient(admin, Audience.ADMIN))
			.toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(DELETION_REQUESTED, id, admins, Map.of("name", name(accountId)),
					"/quan-tri/nguoi-dung/" + accountId, null, null));
		}
		return state(accountId);
	}

	/** {@link vn.vibefix.identity.AccountAdministration#erase}: refusals come back in the result. */
	@Transactional
	Result erase(UUID administrator, UUID accountId, String stepUpCode) {
		StepUps.Outcome stepUp = this.stepUps.check(administrator, stepUpCode);
		if (stepUp instanceof StepUps.Outcome.Refused refused) {
			return new Result(refused.refusal(), refused.endSession());
		}
		String[] kindAndRole = this.jdbc
			.sql("SELECT kind, role FROM identity.account WHERE id = :id AND erased_at IS NULL FOR UPDATE")
			.param("id", accountId)
			.query((rs, n) -> new String[] { rs.getString("kind"), rs.getString("role") })
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// A Google account has no role (Story 19.1).
		Role role = kindAndRole[1] == null ? null : Role.valueOf(kindAndRole[1]);
		if (Account.Kind.ADMINISTRATOR.name().equals(kindAndRole[0])) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ADMINISTRATOR_ACCOUNT);
		}
		Request request = stored(accountId).filter(found -> found.completedAt() == null).orElse(null);
		if (request == null) {
			return new Result(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.NO_DELETION_REQUEST), false);
		}
		Optional<TokenCipher.Sealed> token = this.accounts.customerToken(accountId);
		if (this.personalData.erase(accountId) instanceof ErasureOutcome.Blocked blocked) {
			List<Map<String, Object>> blockers = blocked.blockers().stream().map(DataRequests::blocker).toList();
			return new Result(new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.ERASURE_BLOCKED,
					Map.of("blockers", blockers)), false);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("UPDATE identity.deletion_request SET completed_at = :now, completed_by_admin_id = :admin WHERE id = :id")
			.param("now", Timestamp.from(now))
			.param("admin", administrator)
			.param("id", request.id())
			.update();
		// The account's sessions end at once, as a lock ends them (SG-11).
		this.jdbc.sql("DELETE FROM platform.spring_session WHERE principal_name = :name")
			.param("name", accountId.toString())
			.update();
		token.ifPresent(sealed -> this.operations.request(new OperationRequest(OAuthGrantRevocation.TYPE, request.id(),
				Map.of("accountId", accountId.toString()),
				Map.of("keyVersion", sealed.keyVersion(), "nonce", Base64.getEncoder().encodeToString(sealed.nonce()),
						"ciphertext", Base64.getEncoder().encodeToString(sealed.ciphertext())),
				accountId, 0)));
		this.events.publishEvent(new AccountErased(this.ids.newId(), accountId, role));
		this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "identity.account_erased",
				AuditObject.of("identity.account", accountId)));
		return new Result(null, false);
	}

	private Optional<Request> stored(UUID accountId) {
		return this.jdbc.sql("SELECT id, requested_at, completed_at FROM identity.deletion_request WHERE account_id = :account")
			.param("account", accountId)
			.query((rs, n) -> {
				Timestamp completed = rs.getTimestamp("completed_at");
				return new Request(rs.getObject("id", UUID.class), rs.getTimestamp("requested_at").toInstant(),
						completed == null ? null : completed.toInstant());
			})
			.optional();
	}

	private String name(UUID accountId) {
		return this.jdbc.sql("""
				SELECT coalesce(cp.display_name, ma.display_name, sp.real_name, a.google_name, '@' || a.github_login, '')
				       AS name
				FROM identity.account a
				LEFT JOIN identity.customer_profile cp ON cp.account_id = a.id
				LEFT JOIN identity.mentor_application ma ON ma.account_id = a.id
				LEFT JOIN identity.student_profile sp ON sp.account_id = a.id
				WHERE a.id = :id
				""").param("id", accountId).query(String.class).optional().orElse("");
	}

	private static Map<String, Object> blocker(ErasureBlocker blocker) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("module", blocker.module());
		row.put("reason", blocker.reason());
		row.put("jobId", blocker.jobId() == null ? null : blocker.jobId().toString());
		return row;
	}

}
