package vn.vibefix.authz;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * One row of {@code src/test/resources/authz/matrix.csv} (AD-20): an endpoint, a caller, the
 * caller's state, an optional request body, and the expected answer.
 * <p>
 * The file has the header {@code endpoint,caller,subject_state,body,expect,check}; lines starting with
 * {@code #} and blank lines are ignored. Fields hold no commas.
 * <ul>
 * <li>{@code endpoint}: {@code METHOD /path}, where {@code {name}} is a path variable a fixture
 * seeds, for example {@code GET /api/v1/jobs/{jobId}}.</li>
 * <li>{@code caller}: a {@link MatrixCaller}.</li>
 * <li>{@code subject_state}: {@code key=value} pairs separated by {@code ;}. A world-binding key ({@link Binding})
 * rebinds a placeholder before the runner expands the path and the body, and no realizer sees it:
 * {@code job=crew} makes {@code {jobId}} name the crew Job, the Active Job whose {@code {boardId}} it already is,
 * instead of the Customer's Draft; {@code job=post} makes it name the Open Job Post's Job, whose {@code {postId}} it
 * is and where {@code {applicationId}} lives; {@code teamIssue=escalated} makes {@code {teamIssueId}} name the
 * escalated Team Issue that {@code ADMIN_TEAM_ISSUE_GRANT} took, instead of the one waiting for the Mentor's
 * answer; {@code file=upload}, {@code file=attachment}, {@code file=chat} and {@code file=screenshot} make
 * {@code {fileId}} name one of the crew Job's stored files (the Mentor's screenshot upload waiting for the next
 * handover, a Work Item attachment, a Crew Chat attachment, a handover's screenshot) instead of the Open Job Post's
 * public screenshot; {@code file=proof} and {@code file=proof-upload} make it name the post's bid's proof file or the
 * Bidder's proof upload no bid took yet (Story 21.2); and {@code item=raiser} makes {@code {workItemId}} name the escalated Team Issue's raiser's
 * Work Item instead of the active member's. The
 * fixture that realizes the caller must understand every other key; since Story 20.5 no caller takes one: a
 * locked account is the caller {@code LOCKED}, and the GitHub link tells {@code USER} from
 * {@code USER_UNVERIFIED}.</li>
 * <li>{@code body}: the name of a JSON file under {@code authz/bodies/}, with the same
 * {@code {name}} placeholders; any other file there (for example {@code upload.png}) is sent
 * as the multipart part {@code file}, byte for byte.</li>
 * <li>{@code expect}: an {@link Expectation}.</li>
 * <li>{@code check} (optional): the global gate the Mentor endpoint applies (AD-19), {@value #GITHUB_LINKED}
 * for new-work commands (bidding, the Mentor's NDA) and for reading "Tìm việc" (X1), or {@value #ACTIVE_ACCOUNT}
 * for everything else. Every row of an endpoint under {@value #MENTOR_PATH} names one, and no other row does,
 * so a Mentor endpoint cannot be added without deciding which. The retired Team endpoints under
 * {@value #REFUSING_PATH} refuse every caller before any gate (Story 20.3), so their rows name none.</li>
 * </ul>
 */
public record MatrixRow(HttpMethod method, String path, MatrixCaller caller, Map<String, String> state,
		@Nullable String body, Expectation expect, @Nullable String check) {

	public static final String RESOURCE = "authz/matrix.csv";

	static final List<String> HEADER = List.of("endpoint", "caller", "subject_state", "body", "expect", "check");

	static final String GITHUB_LINKED = "requireGitHubLinked";

	static final String ACTIVE_ACCOUNT = "requireActiveAccount";

	/** Where the Mentor's own endpoints live, each behind one of the two global gates. */
	static final String MENTOR_PATH = "/api/v1/mentor/";

	/** The retired Team endpoints, which answer nothing to every caller until Story 21.7 deletes them. */
	static final String REFUSING_PATH = "/api/v1/mentor/team";

	/** The Student side of the retired Team endpoints, refusing every caller too. */
	static final String REFUSING_STUDENT_PATH = "/api/v1/team";

	/**
	 * The placeholders that name a Job or an object of one (Story 20.5): a Job, a Board, a Job Post, or an object
	 * of the crew Job, its stored files included (review iteration 4). An endpoint whose path or body holds one is
	 * Job-scoped, so every caller of a Job runs it.
	 */
	public static final Set<String> JOB_SCOPED = Set.of("jobId", "boardId", "postId", "applicationId", "crewChatId",
			"messageId", "workItemId", "workItemNoteId", "attachmentId", "handoverId", "followUpId", "changeRequestId",
			"teamIssueId", "pageId", "crewMilestoneId", "crewCriterionId", "crewRowId", "milestoneId", "paymentOrderId",
			"accessId", "screenshotId", "disputeId", "reportId", "fileId");

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

	/**
	 * A world-binding key of {@code subject_state} (Story 20.5, review iteration 1): {@code key=value} makes the
	 * placeholder name the world's value {@code worldValue} instead of its own.
	 */
	public enum Binding {

		/** {@code job=crew}: {@code {jobId}} names the crew Job, where every caller of a Job holds its role. */
		CREW_JOB("job", "crew", "jobId", "crewJobId"),

		/**
		 * {@code job=post}: {@code {jobId}} names the Open Job Post's Job, where the bid {@code {applicationId}} and its
		 * {@code BIDDER} live (review iteration 2): the post's bid, invitation and selection endpoints run on it.
		 */
		POST_JOB("job", "post", "jobId", "postJobId"),

		/** {@code teamIssue=escalated}: {@code {teamIssueId}} names the escalated Team Issue an Administrator took. */
		ESCALATED_TEAM_ISSUE("teamIssue", "escalated", "teamIssueId", "escalatedTeamIssueId"),

		/**
		 * {@code file=upload}: {@code {fileId}} names the crew Job's Mentor's screenshot upload that waits for the next
		 * handover (review iteration 3), which only that Mentor removes.
		 */
		HANDOVER_UPLOAD("file", "upload", "fileId", "handoverUploadId"),

		/**
		 * {@code file=attachment}: {@code {fileId}} names the stored file of the crew Job's Work Item attachment
		 * ({@code ATTACHMENT}), which {@code AttachmentFiles} serves (review iteration 4).
		 */
		WORK_ITEM_FILE("file", "attachment", "fileId", "attachmentFileId"),

		/**
		 * {@code file=chat}: {@code {fileId}} names a file sent in the crew Job's Crew Chat after the former members'
		 * windows ended, which {@code ChatAttachmentFiles} serves.
		 */
		CHAT_FILE("file", "chat", "fileId", "chatFileId"),

		/**
		 * {@code file=screenshot}: {@code {fileId}} names a screenshot attached to the crew Job's handover, which
		 * {@code HandoverScreenshotFiles} serves to the Delivery Report's readers.
		 */
		HANDOVER_FILE("file", "screenshot", "fileId", "handoverScreenshotFileId"),

		/**
		 * {@code file=proof}: {@code {fileId}} names the stored file of the Open post's bid's proof, which
		 * {@code BidProofFiles} serves to the Bidder and the Job's Customer only (Story 21.2).
		 */
		BID_PROOF_FILE("file", "proof", "fileId", "proofFileId"),

		/**
		 * {@code file=proof-upload}: {@code {fileId}} names the Bidder's proof upload on the post that no bid took yet,
		 * which only the Bidder reads and removes (Story 21.2).
		 */
		PROOF_UPLOAD("file", "proof-upload", "fileId", "proofUploadId"),

		/**
		 * {@code item=raiser}: {@code {workItemId}} names the Work Item of the escalated Team Issue's raiser, which that
		 * issue's grant reads (AdminReadItem.forEscalatedTeamIssue, review iteration 4).
		 */
		RAISER_ITEM("item", "raiser", "workItemId", "raiserWorkItemId"),

		/**
		 * {@code user=member}: {@code {username}} names the crew Job's member who hides their student name, whose PUB-4
		 * must never show it (X9, Story 21.3), instead of the crew Job's Mentor.
		 */
		MEMBER_PROFILE("user", "member", "username", "memberUsername");

		private final String key;

		private final String value;

		private final String placeholder;

		private final String worldValue;

		Binding(String key, String value, String placeholder, String worldValue) {
			this.key = key;
			this.value = value;
			this.placeholder = placeholder;
			this.worldValue = worldValue;
		}

		public String key() {
			return this.key;
		}

		public String value() {
			return this.value;
		}

		/** The placeholder it rebinds. */
		public String placeholder() {
			return this.placeholder;
		}

		/** The name of the world's value the placeholder takes. */
		public String worldValue() {
			return this.worldValue;
		}

		/** {@code key=value}, as a row's {@code subject_state} holds it. */
		public String state() {
			return this.key + "=" + this.value;
		}

		/** The bindings of {@code key}, of which a row's {@code key=value} must name one; empty for another key. */
		static List<Binding> ofKey(String key) {
			return Arrays.stream(values()).filter(binding -> binding.key.equals(key)).toList();
		}

	}

	private static final Set<HttpMethod> METHODS = Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
			HttpMethod.PATCH, HttpMethod.DELETE);

	public MatrixRow {
		state = Map.copyOf(state);
		state.forEach((key, value) -> {
			List<Binding> bindings = Binding.ofKey(key);
			if (!bindings.isEmpty() && bindings.stream().noneMatch(binding -> binding.value().equals(value))) {
				throw new IllegalArgumentException("subject_state " + key + " binds the world as "
						+ bindings.stream().map(Binding::state).toList() + ", not " + key + "=" + value);
			}
		});
		if (!path.startsWith("/")) {
			throw new IllegalArgumentException("A path starts with '/': " + path);
		}
		if (check != null && !check.equals(GITHUB_LINKED) && !check.equals(ACTIVE_ACCOUNT)) {
			throw new IllegalArgumentException("check is " + GITHUB_LINKED + " or " + ACTIVE_ACCOUNT + ", not " + check);
		}
		boolean gated = path.startsWith(MENTOR_PATH) && !path.equals(REFUSING_PATH)
				&& !path.startsWith(REFUSING_PATH + "/");
		if (gated && check == null) {
			throw new IllegalArgumentException("a Mentor endpoint names its check, " + GITHUB_LINKED + " or "
					+ ACTIVE_ACCOUNT + " (AD-19): " + path);
		}
		if (!gated && check != null) {
			throw new IllegalArgumentException("only a Mentor endpoint under " + MENTOR_PATH
					+ " that a gate guards names a check: " + path);
		}
	}

	/** The world-binding keys the row holds. */
	public Set<Binding> bindings() {
		Set<Binding> bindings = new LinkedHashSet<>();
		for (Binding binding : Binding.values()) {
			if (binding.value().equals(this.state.get(binding.key()))) {
				bindings.add(binding);
			}
		}
		return bindings;
	}

	/** Whether the row holds the world-binding key {@code binding}. */
	public boolean binds(Binding binding) {
		return binding.value().equals(this.state.get(binding.key()));
	}

	/** The placeholders the row rebinds, each to the name of the world's value it takes. */
	public Map<String, String> rebound() {
		Map<String, String> rebound = new LinkedHashMap<>();
		bindings().forEach(binding -> rebound.put(binding.placeholder(), binding.worldValue()));
		return rebound;
	}

	/** The row's {@code subject_state} without its world-binding keys: what the caller's realizer sees. */
	public Map<String, String> callerState() {
		Map<String, String> callerState = new LinkedHashMap<>(this.state);
		bindings().forEach(binding -> callerState.remove(binding.key()));
		return callerState;
	}

	/** Whether this is one of the retired Team endpoints, which refuse every caller until Story 21.7. */
	public boolean retiredTeam() {
		return within(this.path, REFUSING_PATH) || within(this.path, REFUSING_STUDENT_PATH);
	}

	private static boolean within(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}

	/** The placeholders of the path and, when it has one, of the JSON body. */
	public Set<String> placeholders() {
		Set<String> names = new TreeSet<>(placeholders(this.path));
		if (this.body != null && this.body.endsWith(".json")) {
			names.addAll(placeholders(MatrixRowRunner.body(this.body)));
		}
		return names;
	}

	/** The placeholders of {@code template}. */
	public static Set<String> placeholders(String template) {
		Set<String> names = new TreeSet<>();
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}

	/** Whether the path or the body names a Job or an object of one ({@link #JOB_SCOPED}). */
	public boolean jobScoped() {
		return placeholders().stream().anyMatch(JOB_SCOPED::contains);
	}

	/**
	 * {@code METHOD /path}, the form the coverage check compares with the handlers: a query the path carries, such as
	 * {@code ?username={username}} (Story 21.3), is sent with the request but names no other endpoint.
	 */
	public String endpoint() {
		int query = this.path.indexOf('?');
		return this.method.name() + " " + (query < 0 ? this.path : this.path.substring(0, query));
	}

	@Override
	public String toString() {
		return endpoint() + " as " + this.caller + (this.state.isEmpty() ? "" : " " + this.state) + " -> "
				+ this.expect;
	}

	/** Every row of the matrix file. */
	public static List<MatrixRow> loadMatrix() {
		try (InputStream in = MatrixRow.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing");
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static List<MatrixRow> parse(String csv) {
		List<MatrixRow> rows = new ArrayList<>();
		boolean headerSeen = false;
		int number = 0;
		for (String line : csv.lines().toList()) {
			number++;
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			List<String> fields = List.of(trimmed.split(",", -1)).stream().map(String::strip).toList();
			if (!headerSeen) {
				if (!fields.equals(HEADER)) {
					throw new IllegalArgumentException("The matrix header must be " + String.join(",", HEADER));
				}
				headerSeen = true;
				continue;
			}
			try {
				rows.add(row(fields));
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("matrix line " + number + ": " + ex.getMessage(), ex);
			}
		}
		if (!headerSeen) {
			throw new IllegalArgumentException("The matrix has no header");
		}
		return rows;
	}

	private static MatrixRow row(List<String> fields) {
		if (fields.size() != HEADER.size() && fields.size() != HEADER.size() - 1) {
			throw new IllegalArgumentException("expected " + (HEADER.size() - 1) + " or " + HEADER.size()
					+ " fields, found " + fields.size());
		}
		String[] endpoint = fields.get(0).split("\\s+", 2);
		if (endpoint.length != 2) {
			throw new IllegalArgumentException("endpoint must be 'METHOD /path', not '" + fields.get(0) + "'");
		}
		HttpMethod method = HttpMethod.valueOf(endpoint[0]);
		if (!METHODS.contains(method)) {
			throw new IllegalArgumentException("unsupported method " + method);
		}
		return new MatrixRow(method, endpoint[1], MatrixCaller.valueOf(fields.get(1)), state(fields.get(2)),
				fields.get(3).isEmpty() ? null : fields.get(3), Expectation.parse(fields.get(4)),
				fields.size() < HEADER.size() || fields.get(5).isEmpty() ? null : fields.get(5));
	}

	private static Map<String, String> state(String text) {
		Map<String, String> state = new LinkedHashMap<>();
		if (text.isEmpty()) {
			return state;
		}
		for (String pair : text.split(";")) {
			String[] keyValue = pair.split("=", 2);
			if (keyValue.length != 2 || keyValue[0].isBlank()) {
				throw new IllegalArgumentException("subject_state must be key=value;..., not '" + text + "'");
			}
			if (state.put(keyValue[0].strip(), keyValue[1].strip()) != null) {
				throw new IllegalArgumentException("subject_state repeats " + keyValue[0]);
			}
		}
		return state;
	}

}
