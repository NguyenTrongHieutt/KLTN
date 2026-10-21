package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** The matrix and canary files parse strictly, so a malformed row never silently tests nothing. */
class MatrixFilesTest {

	private static final String HEADER = "endpoint,caller,subject_state,body,expect,check\n";

	@Test
	void parsesAFullRow() {
		MatrixRow row = MatrixRow.parse(HEADER + """
				# a comment

				POST /api/v1/jobs/{jobId}/apply , MENTOR , mentor=PENDING;locked=false , apply.json , 403 FORBIDDEN MENTOR_NOT_APPROVED
				""").getFirst();

		assertThat(row.method()).isEqualTo(HttpMethod.POST);
		assertThat(row.path()).isEqualTo("/api/v1/jobs/{jobId}/apply");
		assertThat(row.caller()).isEqualTo(MatrixCaller.MENTOR);
		assertThat(row.state()).isEqualTo(Map.of("mentor", "PENDING", "locked", "false"));
		assertThat(row.body()).isEqualTo("apply.json");
		assertThat(row.expect()).isEqualTo(new Expectation(403, "FORBIDDEN", "MENTOR_NOT_APPROVED"));
		assertThat(row.endpoint()).isEqualTo("POST /api/v1/jobs/{jobId}/apply");
	}

	@Test
	void rejectsMalformedRows() {
		for (String line : new String[] { "GET /api/v1/x,NOBODY,,,200", "GET /api/v1/x,ANONYMOUS,,,ok",
				"GET /api/v1/x,ANONYMOUS,,200", "/api/v1/x,ANONYMOUS,,,200", "HEAD /api/v1/x,ANONYMOUS,,,200",
				"GET api/v1/x,ANONYMOUS,,,200", "GET /api/v1/x,ANONYMOUS,locked,,200",
				"GET /api/v1/x,ANONYMOUS,a=1;a=2,,200", "GET /api/v1/x,ANONYMOUS,,,200 VALIDATION X Y" }) {
			assertThatThrownBy(() -> MatrixRow.parse(HEADER + line)).as(line)
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> MatrixRow.parse("GET /api/v1/x,ANONYMOUS,,,200\n"))
			.hasMessageContaining("header");
	}

	@Test
	void aMentorEndpointNamesItsCheckAndNoOtherDoes() {
		assertThat(MatrixRow.parse(HEADER + "GET /api/v1/mentor/job-posts,MENTOR,mentor=APPROVED,,200,requireApprovedMentor")
			.getFirst()
			.check()).isEqualTo("requireApprovedMentor");
		assertThat(MatrixRow.parse(HEADER + "POST /api/v1/mentor/jobs/{jobId}/rating,MENTOR,,,204,requireActiveAccount")
			.getFirst()
			.check()).isEqualTo("requireActiveAccount");
		assertThat(MatrixRow.parse(HEADER + "GET /api/v1/profile,MENTOR,,,200").getFirst().check()).isNull();
		for (String line : new String[] { "GET /api/v1/mentor/job-posts,MENTOR,,,200",
				"GET /api/v1/mentor/job-posts,MENTOR,,,200,",
				"GET /api/v1/mentor/job-posts,MENTOR,,,200,requireNothing",
				"GET /api/v1/profile,MENTOR,,,200,requireApprovedMentor" }) {
			assertThatThrownBy(() -> MatrixRow.parse(HEADER + line)).as(line).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void theMatrixFileParses() {
		assertThat(MatrixRow.loadMatrix()).isNotEmpty()
			.allSatisfy(row -> assertThat(row.path()).matches("/(api/.+|webhooks(/.+)?)"));
	}

	@Test
	void everyCanaryIsUniqueAndHiddenFromCustomersAndUnrelatedCallers() {
		Canaries canaries = Canaries.load();

		assertThat(canaries.all()).extracting(Canaries.Canary::name)
			.containsExactlyInAnyOrder("CREW_SHARE", "CREW_EMAIL", "CREW_LINKEDIN", "WORK_ITEM_NOTE", "ATTACHMENT",
					"CREW_CHAT", "TEAM_ISSUE_ID", "NOTIFICATION_PARAM", "TODO_PARAM", "CONTACT_EMAIL", "SCHOOL_EMAIL",
					"STUDENT_CARD_FILE", "STUDENT_HIDDEN_NAME", "PRIVATE_FEEDBACK");
		Set<MatrixCaller> neverSee = EnumSet.of(MatrixCaller.ANONYMOUS, MatrixCaller.CUSTOMER, MatrixCaller.OWNER,
				MatrixCaller.MENTOR, MatrixCaller.STUDENT, MatrixCaller.TEAM_MENTOR, MatrixCaller.TEAM_MEMBER);
		assertThat(canaries.all()).allSatisfy(canary -> assertThat(canary.visibleTo()).doesNotContainAnyElementsOf(neverSee));
	}

	@Test
	void crewStatesOtherThanActiveSeeNoJobContentCanary() {
		Canaries canaries = Canaries.load();

		for (MatrixCaller caller : new MatrixCaller[] { MatrixCaller.CREW_ASSIGNED, MatrixCaller.CREW_INACTIVE,
				MatrixCaller.CREW_FORMER }) {
			assertThat(canaries.hiddenFrom(caller)).as(caller.name()).hasSize(canaries.all().size());
		}
		assertThat(canaries.hiddenFrom(MatrixCaller.CREW_ACTIVE)).extracting(Canaries.Canary::name)
			.containsExactlyInAnyOrder("CREW_SHARE", "CREW_EMAIL", "CREW_LINKEDIN", "TEAM_ISSUE_ID", "NOTIFICATION_PARAM",
					"TODO_PARAM", "CONTACT_EMAIL", "SCHOOL_EMAIL", "STUDENT_CARD_FILE", "STUDENT_HIDDEN_NAME",
					"PRIVATE_FEEDBACK");
		assertThat(canaries.hiddenFrom(MatrixCaller.ADMINISTRATOR)).extracting(Canaries.Canary::name)
			.containsExactlyInAnyOrder("CREW_SHARE", "WORK_ITEM_NOTE", "ATTACHMENT", "CREW_CHAT", "TEAM_ISSUE_ID",
					"NOTIFICATION_PARAM", "TODO_PARAM");
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
	void callersCoverEveryRelationshipCrewStateAndGrant() {
		assertThat(Arrays.stream(MatrixCaller.values()).map(Enum::name)).containsExactly("ANONYMOUS", "CUSTOMER",
				"MENTOR", "STUDENT", "ADMINISTRATOR", "OWNER", "JOB_MENTOR", "CREW_ASSIGNED", "CREW_ACTIVE",
				"CREW_INACTIVE", "CREW_FORMER", "TEAM_MENTOR", "TEAM_MEMBER", "SUBJECT", "AUTHOR", "ADMIN_DISPUTE_GRANT",
				"ADMIN_TEAM_ISSUE_GRANT", "ADMIN_ABUSE_REPORT_GRANT");
	}

}
