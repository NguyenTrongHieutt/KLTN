package vn.vibefix.scanning.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.scanning.ScanCompleted;
import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.internal.ScanRules.PersonalKind;
import vn.vibefix.scanning.internal.ScanRules.Rule;
import vn.vibefix.scanning.internal.ScanRuns.Run;
import vn.vibefix.shared.IdGenerator;

/**
 * Records what a scan job found, in one transaction (AD-18): each credential becomes a Finding unless
 * its fingerprint is already one of the Product's, the run gets its counts, and the Customer hears
 * about each new Finding and, for a clean intake scan, about that. Each new Intake Finding gets the
 * Customer as its resolver and opens their resolve to-do (UX-DR15). A clean-repository candidate
 * with any personal data stores no Finding at all (FR-65): the Product keeps its repository. A
 * complete run then publishes {@link ScanCompleted}; a run a cap stopped keeps its Findings and fails.
 * <p>
 * The job's output is checked, not trusted: a rule ID or a personal-data kind this platform does not
 * know is dropped, and the type the Customer reads comes from this platform's own rules.
 */
@Component
class ScanCompletion {

	/** New Findings announced per run; CUS-4 lists every one (AD-34: the daily email budget). */
	static final int NOTIFIED_FINDINGS = 10;

	static final String INTAKE_SCAN_CLEAN = "scanning.intake_scan_clean";

	static final String FINDING_OPENED = "scanning.finding_opened";

	private static final Logger log = LoggerFactory.getLogger(ScanCompletion.class);

	private final JdbcClient jdbc;

	private final FingerprintKey key;

	private final Notifications notifications;

	private final Todos todos;

	private final FindingResolutions resolutions;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	private final ScanRules rules = ScanRules.load();

	private final Map<String, String> types = this.rules.rules()
		.stream()
		.collect(Collectors.toUnmodifiableMap(Rule::id, Rule::type));

	ScanCompletion(JdbcClient jdbc, FingerprintKey key, Notifications notifications, Todos todos,
			FindingResolutions resolutions, ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.key = key;
		this.notifications = notifications;
		this.todos = todos;
		this.resolutions = resolutions;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	private record Opened(UUID id, String type) {
	}

	private static final java.util.regex.Pattern BRANCH = java.util.regex.Pattern.compile("[A-Za-z0-9._/-]{1,255}");

	private static final java.util.regex.Pattern SHA = java.util.regex.Pattern.compile("[0-9a-f]{40}");

	/** A value from the sandbox's output, kept only when it has the expected form. */
	private static @Nullable String matching(JsonNode value, java.util.regex.Pattern form) {
		return value.isString() && form.matcher(value.stringValue()).matches() ? value.stringValue() : null;
	}

	/**
	 * Records {@code output}.
	 * @param stopped the cap that stopped the walk, or {@code null} for a complete scan
	 * @return whether this call recorded it; {@code false} when the run had succeeded already
	 */
	@Transactional
	boolean record(Run run, JsonNode output, @Nullable String stopped) {
		String status = this.jdbc.sql("SELECT status FROM scanning.scan_run WHERE id = :id FOR UPDATE")
			.param("id", run.id())
			.query(String.class)
			.optional()
			.orElse("SUCCEEDED");
		if ("SUCCEEDED".equals(status)) {
			return false;
		}
		Instant now = this.clock.instant();
		List<Opened> opened = new ArrayList<>();
		int found = 0;
		boolean personalDataFound = false;
		for (JsonNode match : output.path("found")) {
			personalDataFound |= "PERSONAL_DATA".equals(match.path("category").stringValue());
		}
		boolean keep = !(personalDataFound && run.purpose() == ScanPurpose.CLEAN_REPOSITORY);
		for (JsonNode match : output.path("found")) {
			boolean personal = "PERSONAL_DATA".equals(match.path("category").stringValue());
			String type = personal ? personalType(match) : this.types.get(match.path("ruleId").stringValue());
			if (type == null) {
				log.warn("Scan run {} reported a rule or kind this platform does not know; dropped", run.id());
				continue;
			}
			found++;
			if (!keep) {
				continue;
			}
			UUID id = this.ids.newId();
			int inserted = this.jdbc.sql("""
					INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
					    commit_sha, committed_at, masked_preview, match_count, fingerprint, status, created_at)
					VALUES (:id, :product, :run, :category, :rule, :type, :path, :commit, :committedAt, :masked, :matches,
					    :fingerprint, 'OPEN', :now)
					ON CONFLICT (product_id, fingerprint) DO NOTHING
					""")
				.param("id", id)
				.param("category", personal ? "PERSONAL_DATA" : "CREDENTIAL")
				.param("matches", personal ? Math.max(1, match.path("lines").intValue()) : null)
				.param("product", run.productId())
				.param("run", run.id())
				.param("rule", match.path("ruleId").stringValue())
				.param("type", type)
				.param("path", match.path("path").stringValue())
				.param("commit", match.path("commit").stringValue())
				.param("committedAt", Timestamp.from(Instant.parse(match.path("committedAt").stringValue())))
				.param("masked", match.path("masked").stringValue())
				.param("fingerprint", this.key.fingerprint(match.path("hash").stringValue()))
				.param("now", Timestamp.from(now))
				.update();
			if (inserted == 1) {
				opened.add(new Opened(id, type));
				if (run.purpose() == ScanPurpose.PUSH) {
					// AD-3: repo pushes down a push Finding's resolvers and tells the Job's parties.
					continue;
				}
				// The other purposes scan the Customer's own repository: they resolve its Findings.
				this.resolutions.addResolver(id, run.customerId());
				this.todos.open(new TodoRequest(FindingResolutions.RESOLVE_INTAKE_FINDING, run.customerId(),
						Audience.SUBJECT, id, Map.of("product", run.productName()),
						"/san-pham/" + run.productId() + "/van-de/" + id, null, null));
			}
		}
		boolean complete = stopped == null;
		this.jdbc.sql("""
				UPDATE scanning.scan_run
				SET status = :status, rules_version = :version, commits = :commits, scanned_files = :scanned,
				    unscanned_count = :unscannedCount, unscanned = CAST(:unscanned AS JSONB), found_count = :found,
				    new_findings = :opened, stopped = :stopped, error_code = :stopped, finished_at = :now,
				    default_branch = :branch, default_branch_sha = :sha
				WHERE id = :id
				""")
			.param("id", run.id())
			.param("branch", matching(output.path("defaultBranch"), BRANCH))
			.param("sha", matching(output.path("defaultBranchSha"), SHA))
			.param("status", complete ? "SUCCEEDED" : "FAILED")
			.param("version", this.rules.version())
			.param("commits", Math.max(0, output.path("commits").intValue()))
			.param("scanned", Math.max(0, output.path("scannedBlobs").intValue()))
			.param("unscannedCount", Math.max(0, output.path("unscannedCount").intValue()))
			.param("unscanned", unscanned(output.path("unscanned")))
			.param("found", found)
			.param("opened", opened.size())
			.param("stopped", stopped)
			.param("now", Timestamp.from(now))
			.update();
		Recipient customer = new Recipient(run.customerId(), Audience.SUBJECT);
		String link = "/san-pham/" + run.productId();
		opened.stream()
			.filter(finding -> run.purpose() != ScanPurpose.PUSH)
			.limit(NOTIFIED_FINDINGS)
			.forEach(finding -> this.notifications.send(new NotificationRequest(FINDING_OPENED, finding.id(),
					List.of(customer), Map.of("type", finding.type(), "product", run.productName()), link, null,
					run.productId())));
		if (complete) {
			if (found == 0 && run.purpose() == ScanPurpose.INTAKE) {
				this.notifications.send(new NotificationRequest(INTAKE_SCAN_CLEAN, run.id(), List.of(customer),
						Map.of("product", run.productName()), link, null, run.productId()));
			}
			this.events.publishEvent(
					new ScanCompleted(this.ids.newId(), run.id(), run.productId(), found == 0, personalDataFound, run.purpose(),
							opened.stream().map(Opened::id).toList()));
		}
		return true;
	}

	/** A personal-data Finding's type from its kinds, or {@code null} when it names none this platform knows. */
	private @Nullable String personalType(JsonNode match) {
		if (!PersonalDataMatcher.RULE_ID.equals(match.path("ruleId").stringValue())) {
			return null;
		}
		Set<PersonalKind> kinds = EnumSet.noneOf(PersonalKind.class);
		for (JsonNode kind : match.path("kinds")) {
			try {
				kinds.add(PersonalKind.valueOf(kind.stringValue()));
			}
			catch (IllegalArgumentException | NullPointerException ex) {
				return null;
			}
		}
		return kinds.isEmpty() ? null : this.rules.personalData().label(kinds);
	}

	/** The listed unscanned files: path, size, and reason, at most {@value HistoryScanner#LISTED_UNSCANNED}. */
	private static String unscanned(JsonNode files) {
		ArrayNode kept = JsonNodeFactory.instance.arrayNode();
		for (JsonNode file : files) {
			if (kept.size() >= HistoryScanner.LISTED_UNSCANNED) {
				break;
			}
			if (file.path("path").isString() && file.path("sizeBytes").isIntegralNumber()) {
				String given = file.path("reason").stringValue();
				String reason = HistoryScanner.NOT_EXTRACTED.equals(given) || HistoryScanner.UNREADABLE.equals(given) ? given
						: HistoryScanner.TOO_LARGE;
				kept.addObject()
					.put("path", file.path("path").stringValue())
					.put("sizeBytes", file.path("sizeBytes").longValue())
					.put("reason", reason);
			}
		}
		return kept.toString();
	}

}
