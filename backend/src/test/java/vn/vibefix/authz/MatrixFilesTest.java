package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.util.ClassUtils;

import vn.vibefix.authz.MatrixRow.Binding;
import vn.vibefix.files.FileReadPolicy;

/**
 * The matrix and canary files parse strictly, so a malformed row never silently tests nothing, and they keep
 * AD-20's rules (Story 20.5): every endpoint runs the global callers and every Job-scoped one every caller of a
 * Job, on the Job where that caller holds its role; the callers that hold no relation never learn that a hidden
 * object exists (SG-31); and the canaries keep the Customer, the Crew states and the cross-role person apart.
 */
class MatrixFilesTest {

	private static final String HEADER = "endpoint,caller,subject_state,body,expect,check\n";

	/** Every endpoint's callers, the retired Team endpoints' excepted. */
	static final Set<MatrixCaller> GLOBAL = EnumSet.of(MatrixCaller.ANONYMOUS, MatrixCaller.USER_UNVERIFIED,
			MatrixCaller.USER, MatrixCaller.ADMINISTRATOR, MatrixCaller.CUSTOMER);

	/** A Job-scoped endpoint's callers on top of {@link #GLOBAL}. */
	static final Set<MatrixCaller> PER_JOB = EnumSet.of(MatrixCaller.CUSTOMER, MatrixCaller.MENTOR, MatrixCaller.BIDDER,
			MatrixCaller.CREW_ASSIGNED, MatrixCaller.CREW_ACTIVE, MatrixCaller.CREW_FORMER, MatrixCaller.CROSS_ROLE,
			MatrixCaller.LOCKED);

	/**
	 * The callers whose relation is to the crew Job only, never to the Customer's Draft, so their rows on an
	 * endpoint naming {@code {jobId}} carry {@code job=crew} (review iteration 1, B1, VO1, V1).
	 */
	static final Set<MatrixCaller> ON_THE_CREW_JOB = EnumSet.of(MatrixCaller.MENTOR, MatrixCaller.CREW_ASSIGNED,
			MatrixCaller.CREW_ACTIVE, MatrixCaller.CREW_FORMER, MatrixCaller.CROSS_ROLE, MatrixCaller.LOCKED,
			MatrixCaller.ADMIN_DISPUTE_GRANT, MatrixCaller.ADMIN_TEAM_ISSUE_GRANT, MatrixCaller.ADMIN_ABUSE_REPORT_GRANT);

	/**
	 * The crew Job's own objects (review iteration 2, B2): an endpoint naming one beside {@code {jobId}} runs every row on
	 * the crew Job ({@code job=crew}), since the Draft holds none of them.
	 */
	static final Set<String> CREW_JOB_OBJECTS = Set.of("milestoneId", "paymentOrderId", "crewRowId", "accessId");

	/** The post's bid and invitation endpoints, whose Customer and Bidder rows run on the post ({@code job=post}, B5). */
	static final Set<String> ON_THE_POST = Set.of("GET /api/v1/jobs/{jobId}/applications",
			"GET /api/v1/jobs/{jobId}/invitations", "POST /api/v1/jobs/{jobId}/invitations",
			"GET /api/v1/jobs/{jobId}/recommendations");

	/**
	 * The Customer's commands on a Draft or an Open Job Post (B4): their Customer keeps a row on that Job beside the
	 * one on the crew Job; every other command of the Customer runs on the crew Job only.
	 */
	static final Map<String, String> ON_A_DRAFT_OR_POST = Map.of("PUT /api/v1/jobs/{jobId}", "edits the Draft (FR-8)",
			"POST /api/v1/jobs/{jobId}/screenshots", "adds a screenshot to the Draft",
			"DELETE /api/v1/jobs/{jobId}/screenshots/{screenshotId}", "removes the Draft's screenshot",
			"POST /api/v1/jobs/{jobId}/publish", "publishes the Draft (J1)",
			"POST /api/v1/jobs/{jobId}/withdraw", "returns an Open Job Post to Draft (J2)",
			"POST /api/v1/jobs/{jobId}/selection/cancel", "cancels the post's selection before the NDA",
			"POST /api/v1/jobs/{jobId}/invitations", "invites a Mentor to bid on the post (FR-14)");

	/**
	 * The Job-scoped endpoints no reachable fixture world lets any caller past, each with why (review iterations 2-4,
	 * liveness): every other one has a row {@link #pastThePolicy past its policy}, answering {@code 2xx}, {@code 409} or
	 * {@code 422}, never a {@code 403} and never {@code 409 NOT_ASSIGNED}. The retired Team endpoints wait for Story 21.7.
	 */
	static final Map<String, String> UNREACHABLE = Map.of(
			"POST /api/v1/boards/{boardId}/revocation-checklist/{accessId}",
			"the Revocation Checklist opens when the Job ends, at J8 or J10 (Story 8.10): the crew Job is Active, so it"
					+ " holds no item to confirm",
			"PUT /api/v1/mentor/jobs/{jobId}/crew/change",
			"a Crew change needs large Crews (AD-30), which are off in the matrix's application, so CrewChanges.propose"
					+ " finds nothing for anyone; OneRolePerJobPropertyIT runs it with them on",
			"GET /api/v1/jobs/{jobId}/final-merge",
			"the Final Merge authorization opens with a closure proposal or at J8 (Story 8.7): the crew Job's held"
					+ " Milestone refuses a proposal (MILESTONE_HELD), so it has none to read");

	/**
	 * The four reads of a Crew Chat (review iteration 3, G5): every grant caller runs each, so a grant that reads more
	 * of the Crew Chat than its scope fails on a canary there.
	 */
	static final Set<String> CREW_CHAT_READS = Set.of("GET /api/v1/jobs/{jobId}/crew-chat",
			"GET /api/v1/crew-chats/{crewChatId}/messages", "GET /api/v1/boards/{boardId}/crew-chat/messages",
			"GET /api/v1/chat-messages/{messageId}");

	/**
	 * A read of Job content as a row names it (review iteration 4, G8): its endpoint and the world binding its row
	 * carries, {@code ""} for none.
	 */
	record ContentRead(String endpoint, String state) {

		static ContentRead of(String endpoint) {
			return new ContentRead(endpoint, "");
		}

		boolean names(MatrixRow row) {
			return row.endpoint().equals(this.endpoint) && stateText(row).equals(this.state);
		}

		@Override
		public String toString() {
			return this.endpoint + (this.state.isEmpty() ? "" : " {" + this.state + "}");
		}

	}

	/**
	 * A grant caller's scope (review iterations 3 and 4): the endpoints of its own case, at or under {@code casePath},
	 * and the reads of Job content its grant serves, each of which answers it {@code 2xx}.
	 */
	record GrantScope(String casePath, Set<ContentRead> reads) {
	}

	/**
	 * Every read of Job content of every grant scope (review iteration 4, G8): the Job Chat's messages, the four Crew
	 * Chat reads and a Crew Chat file, the Crew Terms, the Work Items (the active member's and the raiser's) with their
	 * notes and an attachment's file, and the Pages. Each grant caller runs each, so a read outside its scope is
	 * scanned for the canaries hidden from it.
	 */
	static final Set<ContentRead> CONTENT_READS = Set.of(new ContentRead("GET /api/v1/jobs/{jobId}/chat/messages", "job=crew"),
			new ContentRead("GET /api/v1/jobs/{jobId}/crew-chat", "job=crew"),
			ContentRead.of("GET /api/v1/crew-chats/{crewChatId}/messages"),
			ContentRead.of("GET /api/v1/boards/{boardId}/crew-chat/messages"),
			ContentRead.of("GET /api/v1/chat-messages/{messageId}"),
			new ContentRead("GET /api/v1/files/{fileId}", "file=chat"),
			new ContentRead("GET /api/v1/admin/jobs/{jobId}/crew-terms", "job=crew"),
			ContentRead.of("GET /api/v1/boards/{boardId}/work-items"), ContentRead.of("GET /api/v1/work-items/{workItemId}"),
			new ContentRead("GET /api/v1/work-items/{workItemId}", "item=raiser"),
			ContentRead.of("GET /api/v1/work-item-notes/{workItemNoteId}"),
			new ContentRead("GET /api/v1/files/{fileId}", "file=attachment"),
			ContentRead.of("GET /api/v1/boards/{boardId}/pages"), ContentRead.of("GET /api/v1/pages/{pageId}"),
			ContentRead.of("GET /api/v1/pages/{pageId}/versions"),
			ContentRead.of("GET /api/v1/pages/{pageId}/versions/{versionNo}"));

	/**
	 * Each grant caller's scope, the reads that serve its content: the Dispute grant ({@code AdminReadItem.forDispute})
	 * reads the Job Chat's messages (not the Customer and Mentor's {@code /chat} summary), every Work Item with its notes
	 * and attachments, and the Pages; the escalated Team Issue's reads the Crew Chat by its ID and by the Board, with
	 * its files, the Crew Terms and the raiser's Work Items; the Abuse Report's reads the reported message only.
	 */
	static final Map<MatrixCaller, GrantScope> GRANT_SCOPES = Map.of(MatrixCaller.ADMIN_DISPUTE_GRANT,
			new GrantScope("/api/v1/admin/disputes/{disputeId}",
					Set.of(new ContentRead("GET /api/v1/jobs/{jobId}/chat/messages", "job=crew"),
							ContentRead.of("GET /api/v1/boards/{boardId}/work-items"),
							ContentRead.of("GET /api/v1/work-items/{workItemId}"),
							new ContentRead("GET /api/v1/work-items/{workItemId}", "item=raiser"),
							ContentRead.of("GET /api/v1/work-item-notes/{workItemNoteId}"),
							new ContentRead("GET /api/v1/files/{fileId}", "file=attachment"),
							ContentRead.of("GET /api/v1/boards/{boardId}/pages"), ContentRead.of("GET /api/v1/pages/{pageId}"),
							ContentRead.of("GET /api/v1/pages/{pageId}/versions"),
							ContentRead.of("GET /api/v1/pages/{pageId}/versions/{versionNo}"))),
			MatrixCaller.ADMIN_TEAM_ISSUE_GRANT,
			new GrantScope("/api/v1/admin/team-issues/{teamIssueId}",
					Set.of(ContentRead.of("GET /api/v1/crew-chats/{crewChatId}/messages"),
							ContentRead.of("GET /api/v1/boards/{boardId}/crew-chat/messages"),
							ContentRead.of("GET /api/v1/chat-messages/{messageId}"),
							new ContentRead("GET /api/v1/files/{fileId}", "file=chat"),
							new ContentRead("GET /api/v1/admin/jobs/{jobId}/crew-terms", "job=crew"),
							ContentRead.of("GET /api/v1/boards/{boardId}/work-items"),
							new ContentRead("GET /api/v1/work-items/{workItemId}", "item=raiser"))),
			MatrixCaller.ADMIN_ABUSE_REPORT_GRANT, new GrantScope("/api/v1/admin/abuse-reports/{reportId}",
					Set.of(ContentRead.of("GET /api/v1/chat-messages/{messageId}"))));

	/**
	 * The file read policies (review iteration 4, G7), each by its class: the file binding whose {@code /files/{fileId}}
	 * rows run it ({@code ""} for the Open Job Post's public screenshot, the default {@code {fileId}}).
	 */
	static final Map<String, String> FILE_POLICIES = Map.of("JobScreenshotFiles", "", "AttachmentFiles",
			"file=attachment", "ChatAttachmentFiles", "file=chat", "HandoverScreenshotFiles", "file=screenshot",
			"BidProofFiles", "file=proof");

	/** The file read policies no fixture world can reach, each with why. */
	static final Map<String, String> UNREACHABLE_FILE_POLICIES = Map.of("FinalZipFiles",
			"the final ZIP is built when the Job ends, at J8 or J10 (Story 7.7), and every fixture Job is Active");

	/**
	 * The callers every crew-Job file binding runs: the Job's roles, the cross-role member, an unrelated user, the
	 * grant-less Administrator and every grant caller.
	 */
	static final Set<MatrixCaller> ON_A_JOB_FILE = EnumSet.of(MatrixCaller.USER, MatrixCaller.CUSTOMER, MatrixCaller.MENTOR,
			MatrixCaller.CREW_ASSIGNED, MatrixCaller.CREW_ACTIVE, MatrixCaller.CREW_FORMER, MatrixCaller.CROSS_ROLE,
			MatrixCaller.ADMINISTRATOR, MatrixCaller.ADMIN_DISPUTE_GRANT, MatrixCaller.ADMIN_TEAM_ISSUE_GRANT,
			MatrixCaller.ADMIN_ABUSE_REPORT_GRANT);

	/** The unrelated callers (review iteration 4, G6): no role on the crew Job, so it is hidden from them. */
	static final Set<MatrixCaller> UNRELATED_TO_THE_CREW_JOB = EnumSet.of(MatrixCaller.USER, MatrixCaller.USER_UNVERIFIED,
			MatrixCaller.BIDDER);

	/** The placeholders that name the Draft or the Open post, whose rows an unrelated caller may get past. */
	static final Set<String> OFF_THE_CREW_JOB = Set.of("jobId", "postId", "applicationId", "fileId", "screenshotId");

	/** The escalated Team Issues' Administrator endpoints, whose Administrators read the issue they took (B2). */
	static final String ESCALATED_TEAM_ISSUE = "/api/v1/admin/team-issues/{teamIssueId}";

	static final Set<MatrixCaller> ON_THE_ESCALATED_ISSUE = EnumSet.of(MatrixCaller.ADMINISTRATOR,
			MatrixCaller.ADMIN_TEAM_ISSUE_GRANT);

	/** The global reads that list a Job's content, run by every caller of a Job (B4). */
	static final List<String> JOB_LISTS = List.of("GET /api/v1/crew/assignments", "GET /api/v1/notifications",
			"GET /api/v1/todos", "GET /api/v1/balance", "GET /api/v1/payments/history", "GET /api/v1/mentor/jobs",
			"GET /api/v1/jobs", "GET /api/v1/my-jobs", "GET /api/v1/team-issues");

	static final Set<MatrixCaller> IN_THE_CREW_JOB = EnumSet.of(MatrixCaller.MENTOR, MatrixCaller.CREW_ASSIGNED,
			MatrixCaller.CREW_ACTIVE, MatrixCaller.CREW_FORMER);

	/** The Product's placeholders: the crew Job works on that Product, so its Mentor and Crew run them (B5, E6). */
	static final Set<String> PRODUCT_PLACEHOLDERS = Set.of("productId", "findingId");

	static final Set<MatrixCaller> ON_THE_PRODUCT = EnumSet.of(MatrixCaller.MENTOR, MatrixCaller.CREW_ACTIVE);

	/** The retired Team endpoints keep only these until Story 21.7 deletes them. */
	static final Set<MatrixCaller> RETIRED_TEAM = EnumSet.of(MatrixCaller.ANONYMOUS, MatrixCaller.USER);

	/** The callers that hold no relation to any fixture object (SG-31). */
	static final Set<MatrixCaller> UNRELATED = EnumSet.of(MatrixCaller.USER, MatrixCaller.USER_UNVERIFIED,
			MatrixCaller.BIDDER, MatrixCaller.LOCKED, MatrixCaller.ADMINISTRATOR);

	/** The only refusals with {@code FORBIDDEN} those callers may receive: the global gates' reasons. */
	static final Set<String> GATE_REASONS = Set.of("GITHUB_NOT_LINKED", "ACCOUNT_LOCKED", "PRIVACY_NOTICE_REQUIRED");

	/** The Crew canaries: none reaches the Customer (AD-20, UX-DR108). */
	static final Set<String> CREW_CANARIES = Set.of("CREW_SHARE", "CREW_EMAIL", "CREW_NAME", "CREW_LINKEDIN",
			"WORK_ITEM_NOTE", "WORK_ITEM_TITLE", "ATTACHMENT", "CREW_CHAT", "CREW_CHAT_UNREPORTED", "CREW_PAGE",
			"CREW_PAGE_TITLE", "EARLY_PAGE", "TEAM_ISSUE_ID", "STUDENT_HIDDEN_NAME", "CROSS_CREW_NAME", "CROSS_CREW_EMAIL", "CROSS_LINKEDIN", "CROSS_CREW_CHAT", "CROSS_WORK_ITEM_NOTE",
			"CROSS_PAGE");

	static final Set<String> CROSS_CANARIES = Set.of("CROSS_CREW_NAME", "CROSS_CREW_EMAIL", "CROSS_LINKEDIN",
			"CROSS_CREW_CHAT", "CROSS_WORK_ITEM_NOTE", "CROSS_PAGE");

	@Test
	void parsesAFullRow() {
		MatrixRow row = MatrixRow.parse(HEADER + """
				# a comment

				POST /api/v1/jobs/{jobId}/apply , USER , github=unlinked;locked=false , apply.json , 403 FORBIDDEN GITHUB_NOT_LINKED
				""").getFirst();

		assertThat(row.method()).isEqualTo(HttpMethod.POST);
		assertThat(row.path()).isEqualTo("/api/v1/jobs/{jobId}/apply");
		assertThat(row.caller()).isEqualTo(MatrixCaller.USER);
		assertThat(row.state()).isEqualTo(Map.of("github", "unlinked", "locked", "false"));
		assertThat(row.body()).isEqualTo("apply.json");
		assertThat(row.expect()).isEqualTo(new Expectation(403, "FORBIDDEN", "GITHUB_NOT_LINKED"));
		assertThat(row.endpoint()).isEqualTo("POST /api/v1/jobs/{jobId}/apply");
	}

	@Test
	void aWorldBindingKeyRebindsItsPlaceholderAndNoRealizerSeesIt() {
		MatrixRow row = MatrixRow.parse(HEADER + "GET /api/v1/jobs/{jobId}/crew-chat,MENTOR,job=crew;mood=calm,,200")
			.getFirst();

		assertThat(row.bindings()).containsExactly(Binding.CREW_JOB);
		assertThat(row.binds(Binding.CREW_JOB)).isTrue();
		assertThat(row.rebound()).isEqualTo(Map.of("jobId", "crewJobId"));
		assertThat(row.callerState()).isEqualTo(Map.of("mood", "calm"));
		MatrixWorld world = new MatrixWorld();
		world.put("jobId", "draft");
		world.put("crewJobId", "crew");
		assertThat(world.expand(row.path(), row.rebound())).isEqualTo("/api/v1/jobs/crew/crew-chat");
		assertThat(world.expand(row.path())).isEqualTo("/api/v1/jobs/draft/crew-chat");
		assertThat(MatrixRow.parse(HEADER + "GET /api/v1/admin/team-issues/{teamIssueId},ADMINISTRATOR,teamIssue=escalated,,200")
			.getFirst()
			.rebound()).isEqualTo(Map.of("teamIssueId", "escalatedTeamIssueId"));
		for (String line : new String[] { "GET /api/v1/jobs/{jobId},MENTOR,job=draft,,404 NOT_FOUND",
				"GET /api/v1/admin/team-issues/{teamIssueId},ADMINISTRATOR,teamIssue=open,,404 NOT_FOUND" }) {
			assertThatThrownBy(() -> MatrixRow.parse(HEADER + line)).as(line)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("binds the world as");
		}
	}

	@Test
	void rejectsMalformedRows() {
		for (String line : new String[] { "GET /api/v1/x,NOBODY,,,200", "GET /api/v1/x,ANONYMOUS,,,ok",
				"GET /api/v1/x,ANONYMOUS,,200", "/api/v1/x,ANONYMOUS,,,200", "HEAD /api/v1/x,ANONYMOUS,,,200",
				"GET api/v1/x,ANONYMOUS,,,200", "GET /api/v1/x,ANONYMOUS,locked,,200",
				"GET /api/v1/x,ANONYMOUS,a=1;a=2,,200", "GET /api/v1/x,ANONYMOUS,,,200 VALIDATION X Y",
				"GET /api/v1/x,OWNER,,,200", "GET /api/v1/x,JOB_MENTOR,,,200", "GET /api/v1/x,STUDENT,,,200",
				"GET /api/v1/x,TEAM_MENTOR,,,200", "GET /api/v1/x,TEAM_MEMBER,,,200", "GET /api/v1/x,CREW_INACTIVE,,,200" }) {
			assertThatThrownBy(() -> MatrixRow.parse(HEADER + line)).as(line)
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> MatrixRow.parse("GET /api/v1/x,ANONYMOUS,,,200\n"))
			.hasMessageContaining("header");
	}

	@Test
	void aMentorEndpointNamesItsCheckAndNoOtherDoes() {
		assertThat(MatrixRow.parse(HEADER + "POST /api/v1/mentor/jobs/{jobId}/nda,MENTOR,,nda.json,404 NOT_FOUND,requireGitHubLinked")
			.getFirst()
			.check()).isEqualTo("requireGitHubLinked");
		assertThat(MatrixRow.parse(HEADER + "POST /api/v1/mentor/jobs/{jobId}/rating,MENTOR,,,204,requireActiveAccount")
			.getFirst()
			.check()).isEqualTo("requireActiveAccount");
		assertThat(MatrixRow.parse(HEADER + "GET /api/v1/profile,MENTOR,,,200").getFirst().check()).isNull();
		// The retired Team endpoints refuse every caller before any gate (Story 20.3).
		assertThat(MatrixRow.parse(HEADER + "GET /api/v1/mentor/team/students,USER,,,404 NOT_FOUND").getFirst().check())
			.isNull();
		for (String line : new String[] { "GET /api/v1/mentor/job-posts,MENTOR,,,200",
				"GET /api/v1/mentor/job-posts,MENTOR,,,200,",
				"GET /api/v1/mentor/job-posts,MENTOR,,,200,requireNothing",
				"GET /api/v1/profile,MENTOR,,,200,requireGitHubLinked",
				"GET /api/v1/mentor/job-posts,MENTOR,,,200,requireApprovedMentor",
				"GET /api/v1/mentor/team,USER,,,404 NOT_FOUND,requireActiveAccount" }) {
			assertThatThrownBy(() -> MatrixRow.parse(HEADER + line)).as(line).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void theMatrixFileParses() {
		assertThat(MatrixRow.loadMatrix()).isNotEmpty()
			.allSatisfy(row -> assertThat(row.path()).matches("/(api/.+|webhooks(/.+)?)"))
			.allSatisfy(row -> assertThat(row.callerState()).as("no caller takes a subject state: %s", row).isEmpty())
			.allSatisfy(row -> assertThat(row.caller()).as("AUTHOR is the harness's own").isNotEqualTo(MatrixCaller.AUTHOR));
		// The spec's "about 2,800": at most 2,850 since review iteration 4 made room for the file bindings, 2,900
		// since Story 20.6 added the one shell's role read and Job list, and 2,950 since Story 21.2 added the bid's proof.
		assertThat(MatrixRow.loadMatrix()).as("2,950 rows or fewer").hasSizeLessThanOrEqualTo(2_950);
	}

	@Test
	void everyEndpointRunsItsCallers() {
		assertThat(coverageViolations(MatrixRow.loadMatrix())).isEmpty();
	}

	@Test
	void theCoverageRuleFindsAMissingCallerAndATeamRowTooMany() {
		String global = "GET /api/v1/x,ANONYMOUS,,,401\nGET /api/v1/x,USER_UNVERIFIED,,,200\nGET /api/v1/x,USER,,,200\n"
				+ "GET /api/v1/x,ADMINISTRATOR,,,404 NOT_FOUND\nGET /api/v1/x,CUSTOMER,,,200\n";
		assertThat(coverageViolations(MatrixRow.parse(HEADER + global))).isEmpty();
		assertThat(coverageViolations(MatrixRow.parse(HEADER + global.replace("GET /api/v1/x,USER,,,200\n", ""))))
			.containsExactly("GET /api/v1/x has no row for [USER]");
		String job = global.replace("/api/v1/x", "/api/v1/jobs/{jobId}");
		assertThat(coverageViolations(MatrixRow.parse(HEADER + job)))
			.containsExactly("GET /api/v1/jobs/{jobId} has no row for [LOCKED, MENTOR, BIDDER, CREW_ASSIGNED, CREW_ACTIVE, "
					+ "CREW_FORMER, CROSS_ROLE]");
		String body = global.replace("GET /api/v1/x,", "POST /api/v1/team-issues,").replace(",,,", ",,team-issue.json,");
		assertThat(coverageViolations(MatrixRow.parse(HEADER + body))).as("a Job in the body scopes the endpoint")
			.singleElement()
			.asString()
			.startsWith("POST /api/v1/team-issues has no row for [LOCKED, MENTOR");
		assertThat(coverageViolations(MatrixRow.parse(HEADER + "GET /api/v1/team,ANONYMOUS,,,401\nGET /api/v1/team,USER,,,404 NOT_FOUND\n")))
			.isEmpty();
		assertThat(coverageViolations(MatrixRow.parse(HEADER + "GET /api/v1/team,ANONYMOUS,,,401\nGET /api/v1/team,USER,,,404 NOT_FOUND\n"
				+ "GET /api/v1/team,ADMINISTRATOR,,,404 NOT_FOUND\n")))
			.containsExactly("GET /api/v1/team is a retired Team endpoint, whose callers are [ANONYMOUS, USER] until Story 21.7,"
					+ " not [ANONYMOUS, USER, ADMINISTRATOR]");
		assertThat(coverageViolations(MatrixRow.parse(HEADER + global + "GET /api/v1/x,USER,,,200\n")))
			.containsExactly("GET /api/v1/x has two rows for USER");
		assertThat(coverageViolations(MatrixRow.parse(HEADER + global + "GET /api/v1/x,CUSTOMER,job=crew,,200\n")))
			.as("one caller on two Jobs is two rows")
			.isEmpty();
	}

	@Test
	void everyCallerOfTheCrewJobRunsTheJobEndpointsOnIt() {
		assertThat(jobBindingViolations(MatrixRow.loadMatrix())).isEmpty();
	}

	@Test
	void theJobBindingRuleFindsARowOnTheDraftAndAMissingCustomerRow() {
		assertThat(jobBindingViolations(MatrixRow.parse(HEADER + """
				GET /api/v1/jobs/{jobId}/chat,CUSTOMER,,,200
				GET /api/v1/jobs/{jobId}/chat,CUSTOMER,job=crew,,200
				GET /api/v1/jobs/{jobId}/chat,MENTOR,job=crew,,200
				GET /api/v1/jobs/{jobId}/chat,USER,job=crew,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/chat,ADMINISTRATOR,job=crew,,404 NOT_FOUND
				POST /api/v1/jobs/{jobId}/withdraw,CUSTOMER,,,409 STALE
				POST /api/v1/jobs/{jobId}/withdraw,CUSTOMER,job=crew,,409 STALE
				POST /api/v1/jobs/{jobId}/withdraw,CREW_ACTIVE,job=crew,,404 NOT_FOUND
				POST /api/v1/jobs/{jobId}/chat/messages,CUSTOMER,job=crew,chat-message.json,201
				"""))).isEmpty();
		assertThat(jobBindingViolations(MatrixRow.parse(HEADER + """
				GET /api/v1/jobs/{jobId}/chat,CUSTOMER,,,200
				GET /api/v1/jobs/{jobId}/chat,MENTOR,,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/chat,USER_UNVERIFIED,,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/chat,BIDDER,,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/chat,ADMIN_DISPUTE_GRANT,,,404 NOT_FOUND
				POST /api/v1/jobs/{jobId}/withdraw,LOCKED,,,401
				POST /api/v1/jobs/{jobId}/withdraw,CUSTOMER,job=crew,,409 STALE
				GET /api/v1/boards/{boardId}/work-items,MENTOR,job=crew,,200
				"""))).containsExactly(
						"GET /api/v1/jobs/{jobId}/chat as MENTOR -> 404 NOT_FOUND names the Draft: the caller's Job is job=crew",
						"GET /api/v1/jobs/{jobId}/chat as USER_UNVERIFIED -> 404 NOT_FOUND is not on the crew Job, whose objects it"
								+ " must not learn of: an unrelated caller runs on job=crew",
						"GET /api/v1/jobs/{jobId}/chat as BIDDER -> 404 NOT_FOUND is not on the crew Job, whose objects it must not"
								+ " learn of: an unrelated caller runs on job=crew",
						"GET /api/v1/jobs/{jobId}/chat as ADMIN_DISPUTE_GRANT -> 404 NOT_FOUND names the Draft: the caller's Job is"
								+ " job=crew",
						"POST /api/v1/jobs/{jobId}/withdraw as LOCKED -> 401 names the Draft: the caller's Job is job=crew",
						"GET /api/v1/boards/{boardId}/work-items as MENTOR {job=crew} -> 200 binds a Job but names no {jobId}",
						"GET /api/v1/jobs/{jobId}/chat has no CUSTOMER row with job=crew",
						"POST /api/v1/jobs/{jobId}/withdraw has no CUSTOMER row on the Draft or the post beside the crew Job's");
	}

	@Test
	void theCustomersCommandsAndTheAdministratorRunOnTheCrewJob() {
		assertThat(jobBindingViolations(MatrixRow.parse(HEADER + """
				POST /api/v1/jobs/{jobId}/chat/messages,CUSTOMER,,chat-message.json,404 NOT_FOUND
				POST /api/v1/jobs/{jobId}/chat/messages,CUSTOMER,job=crew,chat-message.json,201
				GET /api/v1/admin/jobs/{jobId},ADMINISTRATOR,,,200
				GET /api/v1/admin/jobs/{jobId},CUSTOMER,,,404 NOT_FOUND
				GET /api/v1/admin/jobs/{jobId},CUSTOMER,job=crew,,404 NOT_FOUND
				"""))).containsExactly(
						"GET /api/v1/admin/jobs/{jobId} as ADMINISTRATOR -> 200 names the Draft: the caller's Job is job=crew",
						"POST /api/v1/jobs/{jobId}/chat/messages runs the Customer's command on the Draft: it acts on the crew Job");
	}

	@Test
	void thePostsBidsAndTheCrewJobsObjectsRunOnTheirOwnJob() {
		assertThat(jobBindingViolations(MatrixRow.parse(HEADER + """
				POST /api/v1/jobs/{jobId}/applications/{applicationId}/select,CUSTOMER,job=post,select.json,409 STALE
				POST /api/v1/jobs/{jobId}/applications/{applicationId}/select,USER,job=post,select.json,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/applications,CUSTOMER,job=post,,200
				GET /api/v1/jobs/{jobId}/applications,CUSTOMER,job=crew,,200
				GET /api/v1/jobs/{jobId}/applications,BIDDER,job=post,,404 NOT_FOUND
				POST /api/v1/jobs/{jobId}/milestones/{milestoneId}/payment-orders,USER,job=crew,payment-order.json,404 NOT_FOUND
				POST /api/v1/jobs/{jobId}/milestones/{milestoneId}/payment-orders,CUSTOMER,job=crew,payment-order.json,409 CONFLICT
				"""))).isEmpty();
		assertThat(jobBindingViolations(MatrixRow.parse(HEADER + """
				POST /api/v1/jobs/{jobId}/applications/{applicationId}/select,CUSTOMER,,select.json,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/applications,CUSTOMER,job=crew,,200
				GET /api/v1/jobs/{jobId}/applications,CUSTOMER,,,200
				GET /api/v1/jobs/{jobId}/applications,BIDDER,,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/invitations,BIDDER,job=crew,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/chat,BIDDER,job=post,,404 NOT_FOUND
				POST /api/v1/jobs/{jobId}/milestones/{milestoneId}/payment-orders,USER,,payment-order.json,404 NOT_FOUND
				"""))).containsExactly(
						"POST /api/v1/jobs/{jobId}/applications/{applicationId}/select as CUSTOMER -> 404 NOT_FOUND names the post's"
								+ " bid: every row is job=post",
						"GET /api/v1/jobs/{jobId}/applications as CUSTOMER -> 200 names the Draft: the post's bids live on job=post",
						"GET /api/v1/jobs/{jobId}/applications as BIDDER -> 404 NOT_FOUND names the Draft: the post's bids live on"
								+ " job=post",
						"GET /api/v1/jobs/{jobId}/invitations as BIDDER {job=crew} -> 404 NOT_FOUND is the post's Bidder: it runs on"
								+ " job=post",
						"GET /api/v1/jobs/{jobId}/chat as BIDDER {job=post} -> 404 NOT_FOUND binds job=post where no bid of theirs"
								+ " lives",
						"GET /api/v1/jobs/{jobId}/chat as BIDDER {job=post} -> 404 NOT_FOUND is not on the crew Job, whose objects it"
								+ " must not learn of: an unrelated caller runs on job=crew",
						"POST /api/v1/jobs/{jobId}/milestones/{milestoneId}/payment-orders as USER -> 404 NOT_FOUND names an object of"
								+ " the crew Job: every row is job=crew",
						"GET /api/v1/jobs/{jobId}/invitations has no CUSTOMER row with job=crew",
						"GET /api/v1/jobs/{jobId}/invitations has no CUSTOMER row on the Draft or the post beside the crew Job's",
						"GET /api/v1/jobs/{jobId}/chat has no CUSTOMER row with job=crew",
						"GET /api/v1/jobs/{jobId}/chat has no CUSTOMER row on the Draft or the post beside the crew Job's",
						"POST /api/v1/jobs/{jobId}/milestones/{milestoneId}/payment-orders has no CUSTOMER row with job=crew");
	}

	@Test
	void someCallerPassesEveryJobScopedEndpointsPolicy() {
		assertThat(livenessViolations(MatrixRow.loadMatrix(), UNREACHABLE)).isEmpty();
	}

	@Test
	void theLivenessRuleFindsAnEndpointNobodyPasses() {
		List<MatrixRow> rows = MatrixRow.parse(HEADER + """
				GET /api/v1/boards/{boardId}/x,ANONYMOUS,,,401
				GET /api/v1/boards/{boardId}/x,CUSTOMER,,,404 NOT_FOUND
				GET /api/v1/boards/{boardId}/y,CUSTOMER,,,404 NOT_FOUND
				GET /api/v1/boards/{boardId}/y,MENTOR,,,422 GUARD_FAILED X
				GET /api/v1/boards/{boardId}/z,CUSTOMER,,,404 NOT_FOUND
				GET /api/v1/boards/{boardId}/gate,USER_UNVERIFIED,,,403 FORBIDDEN GITHUB_NOT_LINKED
				GET /api/v1/boards/{boardId}/gate,MENTOR,,,404 NOT_FOUND
				POST /api/v1/work-items/{workItemId}/held,CREW_ACTIVE,,,403 FORBIDDEN NOT_ASSIGNEE
				POST /api/v1/work-items/{workItemId}/held,MENTOR,,,403 FORBIDDEN NOT_ASSIGNEE
				POST /api/v1/admin/disputes/{disputeId}/taken,ADMINISTRATOR,,,409 CONFLICT NOT_ASSIGNED
				POST /api/v1/admin/disputes/{disputeId}/taken,ADMIN_DISPUTE_GRANT,,,404 NOT_FOUND
				POST /api/v1/admin/disputes/{disputeId}/stale,ADMINISTRATOR,,,409 STALE
				GET /api/v1/x,USER,,,404 NOT_FOUND
				GET /api/v1/team/x,USER,,,404 NOT_FOUND
				""");
		assertThat(livenessViolations(rows, Map.of("GET /api/v1/boards/{boardId}/z", "no world holds one",
				"GET /api/v1/boards/{boardId}/y", "no world holds one")))
			.containsExactly("GET /api/v1/boards/{boardId}/x has no row past its policy: none answers 2xx, 409 or 422",
					"GET /api/v1/boards/{boardId}/y is listed unreachable, but a row passes its policy",
					"GET /api/v1/boards/{boardId}/gate has no row past its policy: none answers 2xx, 409 or 422",
					"POST /api/v1/work-items/{workItemId}/held has no row past its policy: none answers 2xx, 409 or 422",
					"POST /api/v1/admin/disputes/{disputeId}/taken has no row past its policy: none answers 2xx, 409 or 422");
		assertThat(pastThePolicy(Expectation.parse("204"))).isTrue();
		assertThat(pastThePolicy(Expectation.parse("409 CONFLICT TEAM_ISSUE_OPEN"))).isTrue();
		assertThat(pastThePolicy(Expectation.parse("422 GUARD_FAILED MILESTONE_HELD"))).isTrue();
		assertThat(pastThePolicy(Expectation.parse("403 FORBIDDEN GITHUB_NOT_LINKED"))).as("a gate").isFalse();
		assertThat(pastThePolicy(Expectation.parse("403 FORBIDDEN MENTOR_ONLY"))).as("a role holder's").isFalse();
		assertThat(pastThePolicy(Expectation.parse("409 CONFLICT NOT_ASSIGNED"))).as("not the assignee").isFalse();
		assertThat(pastThePolicy(Expectation.parse("400 VALIDATION"))).isFalse();
	}

	@Test
	void theEscalatedTeamIssuesAdministratorsReadTheIssueTheyTook() {
		assertThat(teamIssueBindingViolations(MatrixRow.loadMatrix())).isEmpty();
	}

	@Test
	void theTeamIssueBindingRuleFindsAnAdministratorOnTheWaitingIssue() {
		assertThat(teamIssueBindingViolations(MatrixRow.parse(HEADER + """
				GET /api/v1/admin/team-issues/{teamIssueId},ADMINISTRATOR,teamIssue=escalated,,200
				GET /api/v1/admin/team-issues/{teamIssueId},ADMIN_TEAM_ISSUE_GRANT,teamIssue=escalated,,200
				GET /api/v1/admin/team-issues/{teamIssueId},CREW_ACTIVE,,,404 NOT_FOUND
				"""))).isEmpty();
		assertThat(teamIssueBindingViolations(MatrixRow.parse(HEADER + """
				POST /api/v1/admin/team-issues/{teamIssueId}/take,ADMIN_TEAM_ISSUE_GRANT,,,404 NOT_FOUND
				GET /api/v1/admin/team-issues,ADMINISTRATOR,teamIssue=escalated,,200
				"""))).containsExactly(
						"POST /api/v1/admin/team-issues/{teamIssueId}/take as ADMIN_TEAM_ISSUE_GRANT -> 404 NOT_FOUND names the"
								+ " waiting issue: the Administrators' is teamIssue=escalated",
						"GET /api/v1/admin/team-issues as ADMINISTRATOR {teamIssue=escalated} -> 200 binds teamIssue=escalated but"
								+ " names no {teamIssueId}");
	}

	@Test
	void eachGrantCallerRunsItsCaseAndEveryContentReadAndGetsItsOwnScope() {
		assertThat(grantViolations(MatrixRow.loadMatrix(), GRANT_SCOPES, CONTENT_READS)).isEmpty();
	}

	@Test
	void theGrantRuleFindsAMissingRowAScopeReadNotAnsweredAndAName() {
		List<MatrixRow> rows = MatrixRow.parse(HEADER + """
				GET /api/v1/admin/disputes/{disputeId},ADMIN_DISPUTE_GRANT,,,200
				POST /api/v1/admin/disputes/{disputeId}/decision,ADMINISTRATOR,,dispute-decision.json,409 CONFLICT NOT_ASSIGNED
				GET /api/v1/jobs/{jobId}/chat/messages,ADMIN_DISPUTE_GRANT,job=crew,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/crew-chat,ADMIN_DISPUTE_GRANT,job=crew,,404 NOT_FOUND
				GET /api/v1/crew-chats/{crewChatId}/messages,ADMIN_DISPUTE_GRANT,,,404 NOT_FOUND
				GET /api/v1/boards/{boardId}/crew-chat/messages,ADMIN_DISPUTE_GRANT,,,404 NOT_FOUND
				GET /api/v1/chat-messages/{messageId},ADMINISTRATOR,,,404 NOT_FOUND
				GET /api/v1/files/{fileId},ADMIN_DISPUTE_GRANT,,,404 NOT_FOUND
				""");
		Map<MatrixCaller, GrantScope> scopes = Map.of(MatrixCaller.ADMIN_DISPUTE_GRANT,
				new GrantScope("/api/v1/admin/disputes/{disputeId}",
						Set.of(new ContentRead("GET /api/v1/jobs/{jobId}/chat/messages", "job=crew"))),
				MatrixCaller.ADMIN_ABUSE_REPORT_GRANT, new GrantScope("/api/v1/admin/reports/{reportId}", Set.of()));
		Set<ContentRead> reads = Set.of(ContentRead.of("GET /api/v1/chat-messages/{messageId}"),
				new ContentRead("GET /api/v1/files/{fileId}", "file=attachment"),
				ContentRead.of("GET /api/v1/pages/{pageId}"));
		assertThat(grantViolations(rows, scopes, reads)).containsExactlyInAnyOrder(
				"the content read GET /api/v1/pages/{pageId} is no endpoint of the matrix",
				"ADMIN_ABUSE_REPORT_GRANT's case path /api/v1/admin/reports/{reportId} names no endpoint of the matrix",
				"GET /api/v1/chat-messages/{messageId} has no row for ADMIN_ABUSE_REPORT_GRANT: every grant reads every"
						+ " content read",
				"GET /api/v1/files/{fileId} {file=attachment} has no row for ADMIN_ABUSE_REPORT_GRANT: every grant reads"
						+ " every content read",
				"GET /api/v1/jobs/{jobId}/chat/messages {job=crew} has no row for ADMIN_ABUSE_REPORT_GRANT: every grant"
						+ " reads every content read",
				"POST /api/v1/admin/disputes/{disputeId}/decision has no row for ADMIN_DISPUTE_GRANT, whose case it is",
				"GET /api/v1/chat-messages/{messageId} has no row for ADMIN_DISPUTE_GRANT: every grant reads every content"
						+ " read",
				"GET /api/v1/files/{fileId} {file=attachment} has no row for ADMIN_DISPUTE_GRANT: every grant reads every"
						+ " content read",
				"GET /api/v1/jobs/{jobId}/chat/messages {job=crew} is inside ADMIN_DISPUTE_GRANT's scope, but its row answers"
						+ " 404 NOT_FOUND, not 2xx");
	}

	@Test
	void everyFileReadPolicyRunsThroughItsBindingOrIsListedUnreachable() {
		assertThat(filePolicies()).as("the file read policies").hasSizeGreaterThanOrEqualTo(5);
		assertThat(fileViolations(MatrixRow.loadMatrix(), filePolicies(), FILE_POLICIES, UNREACHABLE_FILE_POLICIES))
			.isEmpty();
	}

	@Test
	void theFileRuleFindsAPolicyNoRowRunsAndABindingWithoutItsCallers() {
		List<MatrixRow> rows = MatrixRow.parse(HEADER + """
				GET /api/v1/files/{fileId},USER,file=attachment,,404 NOT_FOUND
				GET /api/v1/files/{fileId},MENTOR,file=attachment,,200
				""");
		assertThat(fileViolations(rows, Set.of("AttachmentFiles", "ChatAttachmentFiles", "FinalZipFiles"),
				Map.of("AttachmentFiles", "file=attachment", "GoneFiles", ""), Map.of("FinalZipFiles", "never")))
			.containsExactly(
					"ChatAttachmentFiles reads files no row runs: bind {fileId} to one, or list it unreachable with the reason",
					"GoneFiles is no file read policy",
					"GET /api/v1/files/{fileId} {file=attachment} (AttachmentFiles) has no row for [CUSTOMER, CREW_ASSIGNED,"
							+ " CREW_ACTIVE, CREW_FORMER, CROSS_ROLE, ADMINISTRATOR, ADMIN_DISPUTE_GRANT, ADMIN_TEAM_ISSUE_GRANT,"
							+ " ADMIN_ABUSE_REPORT_GRANT]");
	}

	@Test
	void theFileAndWorkItemBindingsNameTheirOwnObjects() {
		MatrixWorld world = new MatrixWorld();
		world.put("fileId", "post-screenshot");
		world.put("boardId", "board");
		world.put("workItemId", "active-item");
		Map<String, String> rebound = Map.of("file=upload", "handoverUploadId", "file=attachment", "attachmentFileId",
				"file=chat", "chatFileId", "file=screenshot", "handoverScreenshotFileId");
		rebound.forEach((state, value) -> {
			MatrixRow row = MatrixRow.parse(HEADER + "GET /api/v1/files/{fileId},MENTOR," + state + ",,200").getFirst();
			assertThat(row.rebound()).as(state).isEqualTo(Map.of("fileId", value));
			assertThat(row.callerState()).as(state).isEmpty();
			world.put(value, value + "-file");
			assertThat(world.expand(row.path(), row.rebound())).as(state).isEqualTo("/api/v1/files/" + value + "-file");
		});
		MatrixRow raiser = MatrixRow.parse(HEADER + "GET /api/v1/work-items/{workItemId},MENTOR,item=raiser,,200").getFirst();
		world.put("raiserWorkItemId", "raiser-item");
		assertThat(world.expand(raiser.path(), raiser.rebound())).isEqualTo("/api/v1/work-items/raiser-item");
		assertThat(world.expand(raiser.path())).isEqualTo("/api/v1/work-items/active-item");
		assertThat(bindingPlaceholderViolations(MatrixRow.loadMatrix())).isEmpty();
		assertThat(bindingPlaceholderViolations(MatrixRow.parse(HEADER + """
				DELETE /api/v1/boards/{boardId}/handovers/screenshots/{fileId},MENTOR,file=upload,,204
				GET /api/v1/files/{fileId},MENTOR,,,404 NOT_FOUND
				GET /api/v1/boards/{boardId}/handovers,MENTOR,file=upload,,200
				GET /api/v1/boards/{boardId}/work-items,MENTOR,item=raiser,,200
				"""))).containsExactly(
						"GET /api/v1/boards/{boardId}/handovers as MENTOR {file=upload} -> 200 binds file=upload but names no"
								+ " {fileId}",
						"GET /api/v1/boards/{boardId}/work-items as MENTOR {item=raiser} -> 200 binds item=raiser but names no"
								+ " {workItemId}");
	}

	@Test
	void anUnrelatedCallerIsToldNothingOnTheCrewJob() {
		assertThat(unrelatedViolations(MatrixRow.loadMatrix())).isEmpty();
	}

	@Test
	void theUnrelatedRuleFindsAStateAnswerOnTheCrewJob() {
		assertThat(unrelatedViolations(MatrixRow.parse(HEADER + """
				GET /api/v1/jobs/{jobId}/chat,USER,job=crew,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/chat,USER_UNVERIFIED,job=crew,,403 FORBIDDEN GITHUB_NOT_LINKED
				POST /api/v1/jobs/{jobId}/withdraw,USER,job=crew,,409 STALE
				POST /api/v1/work-items/{workItemId}/move,BIDDER,,work-item-move.json,422 GUARD_FAILED X
				GET /api/v1/files/{fileId},USER,file=chat,,403 FORBIDDEN NOT_ASSIGNEE
				GET /api/v1/files/{fileId},USER,,,200
				GET /api/v1/jobs/{jobId}/applications,BIDDER,job=post,,200
				"""))).containsExactly(
						"POST /api/v1/jobs/{jobId}/withdraw as USER {job=crew} -> 409 STALE tells an unrelated caller about the"
								+ " crew Job's object: answer NOT_FOUND",
						"POST /api/v1/work-items/{workItemId}/move as BIDDER -> 422 GUARD_FAILED X tells an unrelated caller about"
								+ " the crew Job's object: answer NOT_FOUND",
						"GET /api/v1/files/{fileId} as USER {file=chat} -> 403 FORBIDDEN NOT_ASSIGNEE tells an unrelated caller"
								+ " about the crew Job's object: answer NOT_FOUND");
	}

	@Test
	void theCrossRolePersonReadsTheCrewJobAsAnActiveMember() {
		assertThat(crossRoleViolations(MatrixRow.loadMatrix())).isEmpty();
		assertThat(crossRoleViolations(MatrixRow.parse(HEADER + """
				GET /api/v1/boards/{boardId}/work-items,CREW_ACTIVE,,,200
				GET /api/v1/boards/{boardId}/work-items,CROSS_ROLE,,,404 NOT_FOUND
				GET /api/v1/jobs/{jobId}/crew-chat,CREW_ACTIVE,job=crew,,200
				GET /api/v1/jobs/{jobId}/crew-chat,CROSS_ROLE,job=crew,,200
				POST /api/v1/work-items/{workItemId}/move,CREW_ACTIVE,,work-item-move.json,200
				POST /api/v1/work-items/{workItemId}/move,CROSS_ROLE,,work-item-move.json,403 FORBIDDEN NOT_ASSIGNEE
				GET /api/v1/files/{fileId},CREW_ACTIVE,file=chat,,200
				GET /api/v1/files/{fileId},CROSS_ROLE,,,200
				"""))).containsExactly(
						"GET /api/v1/boards/{boardId}/work-items as CROSS_ROLE -> 404 NOT_FOUND differs from CREW_ACTIVE's 200",
						"GET /api/v1/files/{fileId} as CREW_ACTIVE {file=chat} -> 200 has no CROSS_ROLE row with its state");
	}

	@Test
	void aNumericCanaryIsAlsoScannedAsVnd() {
		Canaries canaries = Canaries.load();

		assertThat(MatrixRowRunner.forms(canaries.get("CREW_SHARE"))).containsExactly("7316429", "7.316.429");
		assertThat(MatrixRowRunner.forms(canaries.get("CREW_NAME"))).containsExactly(canaries.get("CREW_NAME").value());
	}

	@Test
	void everyListOfJobContentRunsEveryCallerOfTheCrewJob() {
		assertThat(callerViolations(MatrixRow.loadMatrix(), JOB_LISTS::contains, IN_THE_CREW_JOB)).isEmpty();
	}

	@Test
	void everyEndpointOfTheProductRunsTheCrewJobsMentorAndCrew() {
		assertThat(callerViolations(MatrixRow.loadMatrix(), MatrixFilesTest::onTheProduct, ON_THE_PRODUCT)).isEmpty();
	}

	@Test
	void theCallerRulesFindAListOrAProductEndpointWithoutTheCrewJobsCallers() {
		List<MatrixRow> rows = MatrixRow.parse(HEADER + """
				GET /api/v1/balance,MENTOR,,,200
				GET /api/v1/balance,CREW_ACTIVE,,,200
				GET /api/v1/products/{productId},MENTOR,,,404 NOT_FOUND
				GET /api/v1/products/{productId},CREW_ACTIVE,,,404 NOT_FOUND
				POST /api/v1/admin/failures/findings/{findingId}/reopen,MENTOR,,,404 NOT_FOUND
				""");
		assertThat(callerViolations(rows, JOB_LISTS::contains, IN_THE_CREW_JOB))
			.containsExactly("GET /api/v1/balance has no row for [CREW_ASSIGNED, CREW_FORMER]");
		assertThat(callerViolations(rows, MatrixFilesTest::onTheProduct, ON_THE_PRODUCT))
			.containsExactly("POST /api/v1/admin/failures/findings/{findingId}/reopen has no row for [CREW_ACTIVE]");
	}

	@Test
	void anUnrelatedCallerIsNeverToldThatAHiddenObjectExists() {
		assertThat(sg31Violations(MatrixRow.loadMatrix())).isEmpty();
	}

	@Test
	void theSg31RuleFindsAForbiddenAnswerToAnUnrelatedCaller() {
		assertThat(sg31Violations(MatrixRow.parse(HEADER + """
				GET /api/v1/x,USER_UNVERIFIED,,,403 FORBIDDEN GITHUB_NOT_LINKED
				GET /api/v1/x,CREW_ACTIVE,,,403 FORBIDDEN MENTOR_ONLY
				GET /api/v1/x,CUSTOMER,,,403 FORBIDDEN MENTOR_ONLY
				GET /api/v1/y,ADMINISTRATOR,,,403 FORBIDDEN NOT_ASSIGNED
				GET /api/v1/y,BIDDER,,,403 FORBIDDEN
				GET /api/v1/y,USER,,,404 NOT_FOUND
				"""))).containsExactly("GET /api/v1/y as ADMINISTRATOR -> 403 FORBIDDEN NOT_ASSIGNED: answer NOT_FOUND (SG-31)",
						"GET /api/v1/y as BIDDER -> 403 FORBIDDEN: answer NOT_FOUND (SG-31)");
	}

	@Test
	void everyCanaryIsHiddenFromTheCallersWithoutARelation() {
		Canaries canaries = Canaries.load();

		assertThat(canaries.all()).extracting(Canaries.Canary::name)
			.containsExactlyInAnyOrder("CREW_SHARE", "CREW_EMAIL", "CREW_NAME", "CREW_LINKEDIN", "WORK_ITEM_NOTE",
					"ATTACHMENT", "CREW_CHAT", "CREW_CHAT_UNREPORTED", "CREW_PAGE", "JOB_CHAT", "TEAM_ISSUE_ID",
					"NOTIFICATION_PARAM", "TODO_PARAM", "CONTACT_EMAIL", "SCHOOL_EMAIL", "PERSON_SIGNAL", "STUDENT_HIDDEN_NAME",
					"PRIVATE_FEEDBACK", "SKILL_SUGGESTION", "CROSS_CREW_NAME", "CROSS_CREW_EMAIL", "CROSS_LINKEDIN",
					"CROSS_CREW_CHAT", "CROSS_WORK_ITEM_NOTE", "CROSS_PAGE", "WORK_ITEM_TITLE", "CREW_PAGE_TITLE",
					"CUSTOMER_PAGE", "EARLY_PAGE", "BID_PROOF_CAPTION", "BID_PROOF_LINK");
		// The canaries of Job content are written after the former members' windows ended, so a Crew state other than
		// ACTIVE, which reads at most up to its window_end (AD-41), reads none of them; a bid grants nothing on the Job.
		// EARLY_PAGE alone was written inside the window, so the former member reads it (review iteration 4). The bid's
		// proof is the Bidder's own, which they and the Job's Customer read (Story 21.2).
		Set<MatrixCaller> neverSee = EnumSet.of(MatrixCaller.ANONYMOUS, MatrixCaller.USER, MatrixCaller.USER_UNVERIFIED,
				MatrixCaller.LOCKED, MatrixCaller.BIDDER, MatrixCaller.CREW_ASSIGNED, MatrixCaller.CREW_FORMER,
				MatrixCaller.AUTHOR);
		assertThat(canaries.all()).allSatisfy(canary -> {
			Set<MatrixCaller> hidden = EnumSet.copyOf(neverSee);
			if (canary.name().equals("EARLY_PAGE")) {
				hidden.remove(MatrixCaller.CREW_FORMER);
			}
			if (canary.name().startsWith("BID_PROOF_")) {
				hidden.remove(MatrixCaller.BIDDER);
			}
			assertThat(canary.visibleTo()).as(canary.name()).doesNotContainAnyElementsOf(hidden);
		});
		assertThat(canaries.get("EARLY_PAGE").visibleTo()).contains(MatrixCaller.CREW_FORMER);
		assertThat(canaries.get("BID_PROOF_CAPTION").visibleTo()).containsExactlyInAnyOrder(MatrixCaller.BIDDER,
				MatrixCaller.CUSTOMER);
		assertThat(canaries.get("BID_PROOF_LINK").visibleTo()).containsExactlyInAnyOrder(MatrixCaller.BIDDER,
				MatrixCaller.CUSTOMER);
	}

	@Test
	void theCustomerSeesNoCrewCanary() {
		Canaries canaries = Canaries.load();

		assertThat(names(canaries.hiddenFrom(MatrixCaller.CUSTOMER))).containsAll(CREW_CANARIES);
		assertThat(names(canaries.all()).stream().filter(name -> !canaries.get(name).hiddenFrom(MatrixCaller.CUSTOMER)))
			.as("the Job Chat, their own contact, the Customer-visible Page, and the proof of a bid on their Job")
			.containsExactlyInAnyOrder("JOB_CHAT", "CONTACT_EMAIL", "CUSTOMER_PAGE", "BID_PROOF_CAPTION", "BID_PROOF_LINK");
	}

	@Test
	void noCrewStateReadsTheJobChatOrTheCustomersContact() {
		Canaries canaries = Canaries.load();

		for (MatrixCaller caller : new MatrixCaller[] { MatrixCaller.CREW_ASSIGNED, MatrixCaller.CREW_ACTIVE,
				MatrixCaller.CREW_FORMER, MatrixCaller.LOCKED, MatrixCaller.CROSS_ROLE }) {
			assertThat(names(canaries.hiddenFrom(caller))).as(caller.name()).contains("JOB_CHAT", "CONTACT_EMAIL");
		}
		assertThat(names(canaries.hiddenFrom(MatrixCaller.CREW_ACTIVE))).containsExactlyInAnyOrder("CREW_SHARE",
				"CREW_EMAIL", "JOB_CHAT", "TEAM_ISSUE_ID", "NOTIFICATION_PARAM", "TODO_PARAM", "CONTACT_EMAIL",
				"SCHOOL_EMAIL", "PERSON_SIGNAL", "STUDENT_HIDDEN_NAME", "PRIVATE_FEEDBACK", "SKILL_SUGGESTION",
				"CROSS_CREW_NAME", "CROSS_CREW_EMAIL", "CROSS_LINKEDIN", "CROSS_CREW_CHAT", "CROSS_WORK_ITEM_NOTE",
				"CROSS_PAGE", "BID_PROOF_CAPTION", "BID_PROOF_LINK");
		// The Pages and the Story were written after the former member's window ended; EARLY_PAGE inside it.
		assertThat(names(canaries.hiddenFrom(MatrixCaller.CREW_FORMER))).contains("CREW_PAGE", "CREW_PAGE_TITLE",
				"CUSTOMER_PAGE", "WORK_ITEM_TITLE", "CREW_NAME", "CREW_LINKEDIN")
			.doesNotContain("EARLY_PAGE");
	}

	@Test
	void theCrossRolePersonReadsWhatAnActiveMemberReadsAndNothingOfTheirOwnJobsCrew() {
		Canaries canaries = Canaries.load();

		assertThat(canaries.hiddenFrom(MatrixCaller.CROSS_ROLE)).isEqualTo(canaries.hiddenFrom(MatrixCaller.CREW_ACTIVE));
		assertThat(names(canaries.hiddenFrom(MatrixCaller.CROSS_ROLE))).containsAll(CROSS_CANARIES);
	}

	@Test
	void eachAdministratorGrantReadsOnlyWhatItsScopeCovers() {
		Canaries canaries = Canaries.load();

		assertThat(names(canaries.hiddenFrom(MatrixCaller.ADMINISTRATOR))).containsExactlyInAnyOrder("CREW_SHARE",
				"WORK_ITEM_NOTE", "ATTACHMENT", "CREW_CHAT", "CREW_CHAT_UNREPORTED", "CREW_PAGE", "JOB_CHAT",
				"TEAM_ISSUE_ID", "NOTIFICATION_PARAM", "TODO_PARAM", "SCHOOL_EMAIL", "PERSON_SIGNAL", "CROSS_CREW_CHAT",
				"CROSS_WORK_ITEM_NOTE", "CROSS_PAGE", "WORK_ITEM_TITLE", "CREW_PAGE_TITLE", "CUSTOMER_PAGE", "EARLY_PAGE",
				"BID_PROOF_CAPTION", "BID_PROOF_LINK");
		// No Administrator reads a bid's proof (Story 21.2: no read grant covers it).
		for (MatrixCaller grant : GRANT_SCOPES.keySet()) {
			assertThat(names(canaries.hiddenFrom(grant))).as(grant.name()).contains("BID_PROOF_CAPTION", "BID_PROOF_LINK");
		}
		// A Dispute grant reads no Crew Chat and no Crew Terms (AdminReadItem.forDispute).
		assertThat(names(canaries.hiddenFrom(MatrixCaller.ADMIN_DISPUTE_GRANT)))
			.contains("CREW_CHAT", "CREW_CHAT_UNREPORTED", "CREW_SHARE")
			.doesNotContain("JOB_CHAT", "WORK_ITEM_NOTE", "WORK_ITEM_TITLE", "ATTACHMENT", "CREW_PAGE", "CREW_PAGE_TITLE");
		// An escalated Team Issue's grant reads the Crew Chat, the Crew Terms and its raiser's Stories only, never the
		// Job Chat or another member's Story (WORK_ITEM_TITLE).
		assertThat(names(canaries.hiddenFrom(MatrixCaller.ADMIN_TEAM_ISSUE_GRANT))).contains("JOB_CHAT", "WORK_ITEM_NOTE",
				"WORK_ITEM_TITLE", "CREW_PAGE")
			.doesNotContain("CREW_CHAT", "CREW_CHAT_UNREPORTED", "CREW_SHARE");
		// An Abuse Report's grant reads the reported message only, never another of the Crew Chat (review iteration 3).
		assertThat(names(canaries.hiddenFrom(MatrixCaller.ADMIN_ABUSE_REPORT_GRANT)))
			.contains("JOB_CHAT", "CREW_SHARE", "CREW_CHAT_UNREPORTED")
			.doesNotContain("CREW_CHAT");
	}

	@Test
	void onlyTheCustomersCrewNamesOnPublicReputationAreExempt() {
		Canaries canaries = Canaries.load();
		MatrixRow reputation = MatrixRow
			.parse(HEADER + "GET /api/v1/students/{studentLogin}/reputation,CUSTOMER,,,200")
			.getFirst();
		MatrixRow mentorReputation = MatrixRow.parse(HEADER + "GET /api/v1/mentors/{username}/reputation,CUSTOMER,,,200")
			.getFirst();
		MatrixRow profile = MatrixRow.parse(HEADER + "GET /api/v1/students/{studentLogin},CUSTOMER,,,200").getFirst();
		MatrixRow user = MatrixRow.parse(HEADER + "GET /api/v1/students/{studentLogin}/reputation,USER,,,200").getFirst();

		assertThat(MatrixRowRunner.exempt(reputation, canaries.get("CREW_NAME"))).isTrue();
		assertThat(MatrixRowRunner.exempt(mentorReputation, canaries.get("CROSS_CREW_NAME"))).isTrue();
		assertThat(MatrixRowRunner.exempt(reputation, canaries.get("CREW_EMAIL"))).isFalse();
		assertThat(MatrixRowRunner.exempt(profile, canaries.get("CREW_NAME"))).isFalse();
		assertThat(MatrixRowRunner.exempt(user, canaries.get("CREW_NAME"))).isFalse();
	}

	@Test
	void rejectsDuplicateOrShortCanaries() {
		String header = "canary,value,visible_to,seeded_as\n";
		assertThatThrownBy(() -> Canaries.parse(header + "A,abcdefgh,,x\nA,ijklmnop,,y\n"))
			.hasMessageContaining("Duplicate canary A");
		assertThatThrownBy(() -> Canaries.parse(header + "A,abcdefgh,,x\nB,abcdefgh,,y\n"))
			.hasMessageContaining("Duplicate canary value");
		assertThatThrownBy(() -> Canaries.parse(header + "A,abc,,x\n")).hasMessageContaining("too short");
		assertThatThrownBy(() -> Canaries.parse(header + "A,abcdefgh,NOBODY,x\n"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theCallersAreAd20s() {
		assertThat(Arrays.stream(MatrixCaller.values()).map(Enum::name)).containsExactly("ANONYMOUS", "USER_UNVERIFIED",
				"USER", "LOCKED", "CUSTOMER", "MENTOR", "BIDDER", "CREW_ASSIGNED", "CREW_ACTIVE", "CREW_FORMER",
				"CROSS_ROLE", "SUBJECT", "ADMINISTRATOR", "ADMIN_DISPUTE_GRANT", "ADMIN_TEAM_ISSUE_GRANT",
				"ADMIN_ABUSE_REPORT_GRANT", "AUTHOR");
	}

	/**
	 * The coverage rule: every endpoint has the {@link #GLOBAL} callers' rows, every Job-scoped one also the
	 * {@link #PER_JOB} callers', a retired Team endpoint exactly {@link #RETIRED_TEAM}, and no caller twice on one
	 * Job (a row's world-binding keys name its Job).
	 */
	static List<String> coverageViolations(List<MatrixRow> rows) {
		Map<String, List<MatrixRow>> byEndpoint = byEndpoint(rows);
		List<String> violations = new ArrayList<>();
		byEndpoint.forEach((endpoint, endpointRows) -> {
			Set<MatrixCaller> callers = EnumSet.noneOf(MatrixCaller.class);
			Set<Map.Entry<MatrixCaller, Map<String, String>>> seen = new HashSet<>();
			for (MatrixRow row : endpointRows) {
				callers.add(row.caller());
				// Equal states are equal maps, whatever order Map.copyOf iterates them in (review iteration 3, E10).
				if (!seen.add(Map.entry(row.caller(), row.state()))) {
					violations.add(endpoint + " has two rows for " + row.caller());
				}
			}
			MatrixRow first = endpointRows.getFirst();
			if (first.retiredTeam()) {
				if (!callers.equals(RETIRED_TEAM)) {
					violations.add(endpoint + " is a retired Team endpoint, whose callers are " + RETIRED_TEAM
							+ " until Story 21.7, not " + callers);
				}
				return;
			}
			Set<MatrixCaller> wanted = EnumSet.copyOf(GLOBAL);
			if (endpointRows.stream().anyMatch(MatrixRow::jobScoped)) {
				wanted.addAll(PER_JOB);
			}
			wanted.removeAll(callers);
			if (!wanted.isEmpty()) {
				violations.add(endpoint + " has no row for " + wanted);
			}
		});
		return violations;
	}

	/**
	 * Which Job a row's {@code {jobId}} names (review iterations 1 and 2): the callers {@link #ON_THE_CREW_JOB} and the
	 * {@code ADMINISTRATOR} hold or read their role on the crew Job only, so their rows carry {@code job=crew}; an
	 * endpoint naming a bid runs every row on the post ({@code job=post}), and one naming an object of the crew Job
	 * ({@link #CREW_JOB_OBJECTS}) every row on the crew Job; the Customer and the Bidder of {@link #ON_THE_POST} run
	 * on the post; every endpoint runs the Customer on the crew Job, and a {@code GET} or a command on a Draft or a
	 * post ({@link #ON_A_DRAFT_OR_POST}) also on that Job, while every other command of the Customer runs on the crew
	 * Job only; the unrelated {@code USER}, {@code USER_UNVERIFIED} and {@code BIDDER} run on the crew Job too, the
	 * Bidder on the post's endpoints excepted (review iteration 4, G6); and a row binds a Job only where it names a
	 * {@code {jobId}}.
	 */
	static List<String> jobBindingViolations(List<MatrixRow> rows) {
		List<String> violations = new ArrayList<>();
		for (MatrixRow row : rows) {
			Set<String> names = row.placeholders();
			boolean bound = row.binds(Binding.CREW_JOB) || row.binds(Binding.POST_JOB);
			if (!names.contains("jobId")) {
				if (bound) {
					violations.add(row + " binds a Job but names no {jobId}");
				}
				continue;
			}
			if (names.contains("applicationId")) {
				if (!row.binds(Binding.POST_JOB)) {
					violations.add(row + " names the post's bid: every row is " + Binding.POST_JOB.state());
				}
				continue;
			}
			if (names.stream().anyMatch(CREW_JOB_OBJECTS::contains)) {
				if (!row.binds(Binding.CREW_JOB)) {
					violations.add(row + " names an object of the crew Job: every row is " + Binding.CREW_JOB.state());
				}
				continue;
			}
			boolean onThePost = ON_THE_POST.contains(row.endpoint());
			if (row.binds(Binding.POST_JOB) && !(onThePost && (row.caller() == MatrixCaller.CUSTOMER
					|| row.caller() == MatrixCaller.BIDDER))) {
				violations.add(row + " binds " + Binding.POST_JOB.state() + " where no bid of theirs lives");
			}
			if ((ON_THE_CREW_JOB.contains(row.caller()) || row.caller() == MatrixCaller.ADMINISTRATOR)
					&& !row.binds(Binding.CREW_JOB)) {
				violations.add(row + " names the Draft: the caller's Job is " + Binding.CREW_JOB.state());
			}
			boolean unrelated = row.caller() == MatrixCaller.USER || row.caller() == MatrixCaller.USER_UNVERIFIED
					|| (row.caller() == MatrixCaller.BIDDER && !onThePost);
			if (unrelated && !row.binds(Binding.CREW_JOB)) {
				violations.add(row + " is not on the crew Job, whose objects it must not learn of: an unrelated caller runs"
						+ " on " + Binding.CREW_JOB.state());
			}
			if (onThePost && (row.caller() == MatrixCaller.CUSTOMER || row.caller() == MatrixCaller.BIDDER) && !bound) {
				violations.add(row + " names the Draft: the post's bids live on " + Binding.POST_JOB.state());
			}
			if (onThePost && row.caller() == MatrixCaller.BIDDER && row.binds(Binding.CREW_JOB)) {
				violations.add(row + " is the post's Bidder: it runs on " + Binding.POST_JOB.state());
			}
		}
		byEndpoint(rows).forEach((endpoint, endpointRows) -> {
			Set<String> names = endpointRows.getFirst().placeholders();
			if (!names.contains("jobId") || names.contains("applicationId")) {
				return;
			}
			List<MatrixRow> customer = endpointRows.stream().filter(row -> row.caller() == MatrixCaller.CUSTOMER).toList();
			boolean crew = customer.stream().anyMatch(row -> row.binds(Binding.CREW_JOB));
			boolean elsewhere = customer.stream().anyMatch(row -> !row.binds(Binding.CREW_JOB));
			if (!crew) {
				violations.add(endpoint + " has no CUSTOMER row with " + Binding.CREW_JOB.state());
			}
			boolean twoJobs = endpointRows.getFirst().method() == HttpMethod.GET || ON_A_DRAFT_OR_POST.containsKey(endpoint);
			if (twoJobs && !elsewhere && names.stream().noneMatch(CREW_JOB_OBJECTS::contains)) {
				violations.add(endpoint + " has no CUSTOMER row on the Draft or the post beside the crew Job's");
			}
			if (!twoJobs && elsewhere) {
				violations.add(endpoint + " runs the Customer's command on the Draft: it acts on the crew Job");
			}
		});
		return violations;
	}

	/**
	 * Liveness (review iterations 2 and 3): every Job-scoped endpoint has a row {@link #pastThePolicy past its policy},
	 * so some caller in the world holds the role or grant it asks for, but the {@code unreachable} ones
	 * ({@link #UNREACHABLE}) and the retired Team endpoints; and an endpoint listed unreachable has no such row, so the
	 * list stays true.
	 */
	static List<String> livenessViolations(List<MatrixRow> rows, Map<String, String> unreachable) {
		List<String> violations = new ArrayList<>();
		byEndpoint(rows).forEach((endpoint, endpointRows) -> {
			if (endpointRows.getFirst().retiredTeam() || endpointRows.stream().noneMatch(MatrixRow::jobScoped)) {
				return;
			}
			boolean live = endpointRows.stream().anyMatch(row -> pastThePolicy(row.expect()));
			if (!live && !unreachable.containsKey(endpoint)) {
				violations.add(endpoint + " has no row past its policy: none answers 2xx, 409 or 422");
			}
			if (live && unreachable.containsKey(endpoint)) {
				violations.add(endpoint + " is listed unreachable, but a row passes its policy");
			}
		});
		return violations;
	}

	/**
	 * Whether an answer shows its caller got past the endpoint's policy (review iteration 3): a {@code 2xx}, or a
	 * {@code 409} or {@code 422} about the object's state. A {@code 403}, from a gate or to a role holder, never
	 * does, and neither does {@code 409 NOT_ASSIGNED}, an Administrator who did not take the case.
	 */
	static boolean pastThePolicy(Expectation expect) {
		int status = expect.status();
		if (status == 409) {
			return !"NOT_ASSIGNED".equals(expect.reason());
		}
		return (status >= 200 && status < 300) || status == 422;
	}

	/**
	 * The escalated Team Issues' Administrator endpoints run their Administrators on the issue they may read:
	 * {@code teamIssue=escalated}; and a row binds it only where it names a {@code {teamIssueId}}.
	 */
	static List<String> teamIssueBindingViolations(List<MatrixRow> rows) {
		List<String> violations = new ArrayList<>();
		for (MatrixRow row : rows) {
			boolean escalatedPath = row.path().equals(ESCALATED_TEAM_ISSUE)
					|| row.path().startsWith(ESCALATED_TEAM_ISSUE + "/");
			if (escalatedPath && ON_THE_ESCALATED_ISSUE.contains(row.caller())
					&& !row.binds(Binding.ESCALATED_TEAM_ISSUE)) {
				violations.add(row + " names the waiting issue: the Administrators' is " + Binding.ESCALATED_TEAM_ISSUE.state());
			}
			if (!row.placeholders().contains("teamIssueId") && row.binds(Binding.ESCALATED_TEAM_ISSUE)) {
				violations.add(row + " binds " + Binding.ESCALATED_TEAM_ISSUE.state() + " but names no {teamIssueId}");
			}
		}
		return violations;
	}

	/**
	 * Every grant caller has a row on each endpoint of its case and on every read of Job content
	 * ({@code contentReads}, the four Crew Chat reads among them); each read inside its scope answers it {@code 2xx}
	 * (review iteration 4, G8); and a case path, a Crew Chat read or a content read naming no endpoint of the matrix is
	 * a typo (E5).
	 */
	static List<String> grantViolations(List<MatrixRow> rows, Map<MatrixCaller, GrantScope> scopes,
			Set<ContentRead> contentReads) {
		Map<String, List<MatrixRow>> byEndpoint = byEndpoint(rows);
		List<String> violations = new ArrayList<>();
		new TreeSet<>(CREW_CHAT_READS).stream()
			.filter(endpoint -> !byEndpoint.containsKey(endpoint))
			.forEach(endpoint -> violations.add("the Crew Chat read " + endpoint + " is no endpoint of the matrix"));
		Set<ContentRead> reads = new LinkedHashSet<>(contentReads);
		scopes.values().forEach(scope -> reads.addAll(scope.reads()));
		reads.stream()
			.filter(read -> !byEndpoint.containsKey(read.endpoint()))
			.map(ContentRead::endpoint)
			.distinct()
			.sorted()
			.forEach(endpoint -> violations.add("the content read " + endpoint + " is no endpoint of the matrix"));
		scopes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			MatrixCaller grant = entry.getKey();
			GrantScope scope = entry.getValue();
			Set<String> cases = new TreeSet<>();
			byEndpoint.forEach((endpoint, endpointRows) -> {
				String path = endpointRows.getFirst().path();
				if (path.equals(scope.casePath()) || path.startsWith(scope.casePath() + "/")) {
					cases.add(endpoint);
				}
			});
			if (cases.isEmpty()) {
				violations.add(grant + "'s case path " + scope.casePath() + " names no endpoint of the matrix");
			}
			for (String endpoint : cases) {
				if (byEndpoint.get(endpoint).stream().noneMatch(row -> row.caller() == grant)) {
					violations.add(endpoint + " has no row for " + grant + ", whose case it is");
				}
			}
			for (ContentRead read : reads) {
				if (!byEndpoint.containsKey(read.endpoint())) {
					continue;
				}
				List<MatrixRow> grantRows = byEndpoint.get(read.endpoint())
					.stream()
					.filter(row -> row.caller() == grant && read.names(row))
					.toList();
				if (grantRows.isEmpty()) {
					violations.add(read + " has no row for " + grant + ": every grant reads every content read");
				}
				else if (scope.reads().contains(read) && grantRows.stream().noneMatch(row -> row.expect().status() / 100 == 2)) {
					violations.add(read + " is inside " + grant + "'s scope, but its row answers " + grantRows.getFirst().expect()
							+ ", not 2xx");
				}
			}
		});
		return violations;
	}

	/** The row's {@code subject_state} as the matrix writes it: {@code key=value} pairs, sorted, joined by {@code ;}. */
	static String stateText(MatrixRow row) {
		return row.state()
			.entrySet()
			.stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.sorted()
			.collect(Collectors.joining(";"));
	}

	/**
	 * Every row of an unrelated caller on the crew Job ({@link #UNRELATED_TO_THE_CREW_JOB}, review iteration 4, G6) is
	 * told nothing: {@code 401}, {@code 404}, or a global gate's {@code 403}, never another {@code 403}, a
	 * {@code 409} or a {@code 422} on the object it may not know of. A row is on the crew Job when it binds it, binds
	 * one of its files or Work Items, or names one of its objects.
	 */
	static List<String> unrelatedViolations(List<MatrixRow> rows) {
		List<String> violations = new ArrayList<>();
		for (MatrixRow row : rows) {
			if (!UNRELATED_TO_THE_CREW_JOB.contains(row.caller()) || row.retiredTeam() || !onTheCrewJob(row)) {
				continue;
			}
			Expectation expect = row.expect();
			boolean told = expect.status() == 401 || expect.status() == 404
					|| (expect.status() == 403 && GATE_REASONS.contains(expect.reason()));
			if (!told) {
				violations.add(row + " tells an unrelated caller about the crew Job's object: answer NOT_FOUND");
			}
		}
		return violations;
	}

	private static boolean onTheCrewJob(MatrixRow row) {
		if (row.binds(Binding.POST_JOB)) {
			return false;
		}
		if (row.binds(Binding.CREW_JOB) || row.binds(Binding.WORK_ITEM_FILE) || row.binds(Binding.CHAT_FILE)
				|| row.binds(Binding.HANDOVER_FILE) || row.binds(Binding.HANDOVER_UPLOAD) || row.binds(Binding.RAISER_ITEM)) {
			return true;
		}
		return MatrixRow.placeholders(row.path())
			.stream()
			.anyMatch(name -> MatrixRow.JOB_SCOPED.contains(name) && !OFF_THE_CREW_JOB.contains(name));
	}

	/**
	 * On the crew Job's reads, {@code CROSS_ROLE}, an {@code ACTIVE} member, gets what {@code CREW_ACTIVE} gets (review
	 * iteration 4, B8): their Customer role elsewhere changes nothing here. Every {@code CREW_ACTIVE} read has a
	 * {@code CROSS_ROLE} row of the same state to compare.
	 */
	static List<String> crossRoleViolations(List<MatrixRow> rows) {
		List<String> violations = new ArrayList<>();
		byEndpoint(rows).forEach((endpoint, endpointRows) -> {
			if (endpointRows.getFirst().method() != HttpMethod.GET) {
				return;
			}
			for (MatrixRow cross : endpointRows) {
				if (cross.caller() != MatrixCaller.CROSS_ROLE) {
					continue;
				}
				endpointRows.stream()
					.filter(active -> active.caller() == MatrixCaller.CREW_ACTIVE && active.state().equals(cross.state()))
					.filter(active -> !active.expect().toString().equals(cross.expect().toString()))
					.forEach(active -> violations.add(cross + " differs from CREW_ACTIVE's " + active.expect()));
			}
			endpointRows.stream()
				.filter(active -> active.caller() == MatrixCaller.CREW_ACTIVE)
				.filter(active -> endpointRows.stream()
					.noneMatch(cross -> cross.caller() == MatrixCaller.CROSS_ROLE && cross.state().equals(active.state())))
				.forEach(active -> violations.add(active + " has no CROSS_ROLE row with its state"));
		});
		return violations;
	}

	/**
	 * Every file read policy ({@code policies}, by class) runs in the matrix through its binding ({@link #FILE_POLICIES})
	 * or is listed unreachable with why, and each crew-Job file binding has its {@code /files/{fileId}} row for every
	 * caller {@link #ON_A_JOB_FILE} (review iteration 4, G7).
	 */
	static List<String> fileViolations(List<MatrixRow> rows, Set<String> policies, Map<String, String> bindings,
			Map<String, String> unreachable) {
		List<String> violations = new ArrayList<>();
		new TreeSet<>(policies).stream()
			.filter(policy -> !bindings.containsKey(policy) && !unreachable.containsKey(policy))
			.forEach(policy -> violations.add(policy + " reads files no row runs: bind {fileId} to one, or list it"
					+ " unreachable with the reason"));
		new TreeSet<String>(union(bindings.keySet(), unreachable.keySet())).stream()
			.filter(policy -> !policies.contains(policy))
			.forEach(policy -> violations.add(policy + " is no file read policy"));
		new TreeMap<>(bindings).forEach((policy, state) -> {
			if (state.isEmpty()) {
				return;
			}
			Set<MatrixCaller> wanted = EnumSet.copyOf(ON_A_JOB_FILE);
			rows.stream()
				.filter(row -> row.endpoint().equals("GET /api/v1/files/{fileId}") && stateText(row).equals(state))
				.forEach(row -> wanted.remove(row.caller()));
			if (!wanted.isEmpty()) {
				violations.add("GET /api/v1/files/{fileId} {" + state + "} (" + policy + ") has no row for " + wanted);
			}
		});
		return violations;
	}

	private static Set<String> union(Set<String> one, Set<String> other) {
		Set<String> union = new TreeSet<>(one);
		union.addAll(other);
		return union;
	}

	/** The classes in {@code vn.vibefix} that implement {@code FileReadPolicy}, by simple name. */
	static Set<String> filePolicies() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(FileReadPolicy.class));
		return scanner.findCandidateComponents("vn.vibefix")
			.stream()
			.map(candidate -> ClassUtils.getShortName(Objects.requireNonNull(candidate.getBeanClassName())))
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/** A row binds a file or a Work Item only where it names the placeholder the binding rebinds. */
	static List<String> bindingPlaceholderViolations(List<MatrixRow> rows) {
		List<String> violations = new ArrayList<>();
		for (MatrixRow row : rows) {
			for (Binding binding : row.bindings()) {
				if (!row.placeholders().contains(binding.placeholder())) {
					violations.add(row + " binds " + binding.state() + " but names no {" + binding.placeholder() + "}");
				}
			}
		}
		return violations;
	}

	/** Every endpoint {@code applies} picks has a row for each of {@code callers}. */
	static List<String> callerViolations(List<MatrixRow> rows, Predicate<String> applies,
			Set<MatrixCaller> callers) {
		List<String> violations = new ArrayList<>();
		byEndpoint(rows).forEach((endpoint, endpointRows) -> {
			if (!applies.test(endpoint)) {
				return;
			}
			Set<MatrixCaller> wanted = EnumSet.copyOf(callers);
			endpointRows.forEach(row -> wanted.remove(row.caller()));
			if (!wanted.isEmpty()) {
				violations.add(endpoint + " has no row for " + wanted);
			}
		});
		return violations;
	}

	/** Whether the endpoint names the Product or one of its Findings. */
	static boolean onTheProduct(String endpoint) {
		return MatrixRow.placeholders(endpoint).stream().anyMatch(PRODUCT_PLACEHOLDERS::contains);
	}

	/**
	 * SG-31: a caller that holds no relation to the row's objects is never refused with {@code FORBIDDEN}, which would
	 * tell it that the object exists, except by a global gate ({@link #GATE_REASONS}).
	 */
	static List<String> sg31Violations(List<MatrixRow> rows) {
		return rows.stream()
			.filter(row -> UNRELATED.contains(row.caller()) && row.expect().status() == 403
					&& (row.expect().reason() == null || !GATE_REASONS.contains(row.expect().reason())))
			.map(row -> row + ": answer NOT_FOUND (SG-31)")
			.toList();
	}

	private static Map<String, List<MatrixRow>> byEndpoint(List<MatrixRow> rows) {
		Map<String, List<MatrixRow>> byEndpoint = new LinkedHashMap<>();
		rows.forEach(row -> byEndpoint.computeIfAbsent(row.endpoint(), key -> new ArrayList<>()).add(row));
		return byEndpoint;
	}

	private static Set<String> names(List<Canaries.Canary> canaries) {
		return canaries.stream().map(Canaries.Canary::name).collect(Collectors.toSet());
	}

}
