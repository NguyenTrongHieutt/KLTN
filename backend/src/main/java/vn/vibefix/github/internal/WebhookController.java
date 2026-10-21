package vn.vibefix.github.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.IdGenerator;

/**
 * {@code POST /webhooks} (AD-12, SG-18): both Apps' webhooks. Anyone may call it, since the signature
 * proves who sent it: a delivery is accepted only with a valid {@code X-Hub-Signature-256} for the App
 * {@code X-GitHub-Hook-Installation-Target-ID} names, and otherwise answers 401 without reading it. A
 * delivery seen before answers 200 and publishes nothing; a new one is kept and published as
 * {@link GitHubWebhookReceived} in one transaction and answers 202 at once, while the listeners run after
 * commit. The body is never logged: it may name people.
 */
@RestController
@Access(Gate.PUBLIC)
class WebhookController {

	private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

	private final WebhookSignatures signatures;

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	WebhookController(WebhookSignatures signatures, JdbcClient jdbc, PlatformTransactionManager transactionManager,
			ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		this.signatures = signatures;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	@PostMapping("/webhooks")
	ResponseEntity<Void> webhook(@RequestHeader(value = "X-GitHub-Hook-Installation-Target-ID", required = false) @Nullable String target,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) @Nullable String signature,
			@RequestHeader(value = "X-GitHub-Delivery", required = false) @Nullable String deliveryId,
			@RequestHeader(value = "X-GitHub-Event", required = false) @Nullable String event,
			@RequestBody(required = false) byte @Nullable [] body) {
		byte[] payload = body == null ? new byte[0] : body;
		App app = this.signatures.app(target).orElse(null);
		if (app == null || !this.signatures.verifies(app, payload, signature)) {
			log.warn("A webhook was refused: {}", app == null ? "no known App" : "its signature does not check out");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 100 || event == null || event.isBlank()
				|| event.length() > 100) {
			return ResponseEntity.badRequest().build();
		}
		JsonNode json;
		try {
			json = GitHubHttp.JSON.readTree(payload);
		}
		catch (RuntimeException notJson) {
			return ResponseEntity.badRequest().build();
		}
		Boolean fresh = this.transactions.execute(status -> {
			int kept = this.jdbc.sql("""
					INSERT INTO github.webhook_delivery (delivery_id, app, event, received_at)
					VALUES (:id, :app, :event, :now) ON CONFLICT DO NOTHING
					""")
				.param("id", deliveryId)
				.param("app", app.name())
				.param("event", event)
				.param("now", Timestamp.from(this.clock.instant()))
				.update();
			if (kept == 1) {
				this.events.publishEvent(new GitHubWebhookReceived(this.ids.newId(), deliveryId, app, event,
						text(json.path("action")), number(json.path("installation").path("id")), repositoryIds(json),
						number(json.path("sender").path("id")), number(json.path("requester").path("id"))));
			}
			return kept == 1;
		});
		return ResponseEntity.status(Boolean.TRUE.equals(fresh) ? HttpStatus.ACCEPTED : HttpStatus.OK).build();
	}

	/** Every repository the payload names, in its order and once each. */
	static List<Long> repositoryIds(JsonNode json) {
		List<Long> ids = new ArrayList<>();
		add(ids, json.path("repository").path("id"));
		for (String list : List.of("repositories", "repositories_added", "repositories_removed")) {
			for (JsonNode repository : json.path(list)) {
				add(ids, repository.path("id"));
			}
		}
		return ids;
	}

	private static void add(List<Long> ids, JsonNode id) {
		Long value = number(id);
		if (value != null && !ids.contains(value)) {
			ids.add(value);
		}
	}

	private static @Nullable Long number(JsonNode node) {
		return node.isIntegralNumber() ? node.asLong() : null;
	}

	private static @Nullable String text(JsonNode node) {
		return node.isString() ? node.asString() : null;
	}

}
