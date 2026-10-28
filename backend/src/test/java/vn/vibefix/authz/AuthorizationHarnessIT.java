package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import authzfixtures.HarnessController;
import authzfixtures.NoteFixture;
import vn.vibefix.support.IntegrationTest;

/**
 * Proves the authorization harness itself (AD-20, SG-31, SG-55) against the endpoints of
 * {@link HarnessController}: the gates deny by default, a policy hides what the caller may not see
 * with {@code NOT_FOUND}, and the row runner fails a row on a wrong answer, a leaked canary in the
 * body or a header, a caller no fixture realizes, or a subject state no fixture understands.
 */
@Import(HarnessController.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationHarnessIT extends IntegrationTest {

	private static final String HEADER = "endpoint,caller,subject_state,body,expect,check\n";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApplicationContext context;

	private MatrixRowRunner runner;

	@BeforeEach
	void createRunner() {
		List<MatrixFixture> fixtures = new ArrayList<>(
				MatrixRowRunner.discoverFixtures(this.context.getAutowireCapableBeanFactory()));
		fixtures.add(new NoteFixture());
		this.runner = new MatrixRowRunner(this.mvc, fixtures, Canaries.load());
	}

	@Test
	void gatesAndPoliciesAnswerAsDeclared() throws Exception {
		for (MatrixRow row : MatrixRow.parse(HEADER + """
				GET /api/v1/fixture/public,ANONYMOUS,,,200
				GET /api/v1/fixture/signed-in,ANONYMOUS,,,401
				GET /api/v1/fixture/signed-in,USER,,,200
				GET /api/v1/fixture/signed-in,LOCKED,,,401
				GET /api/v1/fixture/admin,ANONYMOUS,,,401
				GET /api/v1/fixture/admin,CUSTOMER,,,404 NOT_FOUND
				GET /api/v1/fixture/admin,MENTOR,,,404 NOT_FOUND
				GET /api/v1/fixture/admin,USER,,,404 NOT_FOUND
				GET /api/v1/fixture/admin,USER_UNVERIFIED,,,404 NOT_FOUND
				GET /api/v1/fixture/admin,ADMINISTRATOR,,,200
				GET /api/v1/fixture/notes/{noteId},AUTHOR,,,200
				GET /api/v1/fixture/notes/{noteId},USER,,,404 NOT_FOUND
				GET /api/v1/fixture/notes/{noteId},ADMINISTRATOR,,,404 NOT_FOUND
				GET /api/v1/fixture/notes/{noteId},ANONYMOUS,,,401
				POST /api/v1/fixture/notes,AUTHOR,,fixture-note.json,200
				POST /api/v1/fixture/notes,CUSTOMER,,fixture-note.json,404 NOT_FOUND
				GET /api/v1/fixture/forbidden,USER,,,403 FORBIDDEN FIXTURE_NOT_ALLOWED
				GET /api/v1/fixture/leak-body,CREW_ACTIVE,,,200
				GET /api/v1/fixture/leak-body,CROSS_ROLE,,,200
				""")) {
			this.runner.run(row);
		}
	}

	@Test
	void anUnauthenticatedAnswerIsAProblemWithoutACode() throws Exception {
		this.mvc.perform(get("/api/v1/fixture/signed-in"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").doesNotExist());
	}

	@Test
	void aHandlerWithoutAGateIsHiddenFromEveryoneAndLogged(CapturedOutput output) throws Exception {
		for (MatrixRow row : MatrixRow.parse(HEADER + """
				GET /api/v1/fixture/undeclared,ANONYMOUS,,,404 NOT_FOUND
				GET /api/v1/fixture/undeclared,ADMINISTRATOR,,,404 NOT_FOUND
				""")) {
			this.runner.run(row);
		}

		assertThat(output).contains("declares no @Access gate").contains("HarnessController#undeclared");
	}

	@Test
	void aCanaryInTheBodyFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/leak-body,CUSTOMER,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary WORK_ITEM_NOTE is hidden from CUSTOMER but appears in the response body");
		assertThatThrownBy(() -> run("GET /api/v1/fixture/leak-body,ADMINISTRATOR,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("hidden from ADMINISTRATOR");
	}

	@Test
	void aCanaryInAHeaderFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/leak-header,USER,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary CREW_CHAT is hidden from USER but appears in the response header X-Fixture");
		assertThatThrownBy(() -> run("GET /api/v1/fixture/leak-header,CREW_FORMER,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary CREW_CHAT is hidden from CREW_FORMER");
	}

	/** An amount canary is flagged in the VND form a screen shows, not only as its digits (review iteration 4). */
	@Test
	void anAmountCanaryInItsVndFormFailsTheRow() throws Exception {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/leak-amount,CUSTOMER,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary CREW_SHARE is hidden from CUSTOMER but appears in the response body");
		run("GET /api/v1/fixture/leak-amount,MENTOR,,,200");
	}

	/** The cross-role person reads nothing of the Crew of the Job they are the Customer of. */
	@Test
	void aSecondJobsCrewCanaryFailsTheCrossRolePerson() throws Exception {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/leak-cross,CROSS_ROLE,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary CROSS_CREW_CHAT is hidden from CROSS_ROLE");
		run("GET /api/v1/fixture/leak-cross,MENTOR,,,200");
	}

	/**
	 * The Customer's Crew-name scan is exempt on a public reputation endpoint only (PUB-4): for any other caller, and
	 * on any other endpoint, the Crew name fails the row.
	 */
	@Test
	void theCrewNamesAreExemptOnlyForTheCustomerOnAPublicReputation() throws Exception {
		List<MatrixFixture> fixtures = new ArrayList<>(
				MatrixRowRunner.discoverFixtures(this.context.getAutowireCapableBeanFactory()));
		MatrixRowRunner reputation = new MatrixRowRunner(this.mvc, fixtures, Canaries.load(),
				Set.of("GET /api/v1/fixture/reputation"));
		reputation.run(MatrixRow.parse(HEADER + "GET /api/v1/fixture/reputation,CUSTOMER,,,200").getFirst());
		assertThatThrownBy(
				() -> reputation.run(MatrixRow.parse(HEADER + "GET /api/v1/fixture/reputation,USER,,,200").getFirst()))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary CREW_NAME is hidden from USER");
		assertThatThrownBy(() -> run("GET /api/v1/fixture/reputation,CUSTOMER,,,200")).as("not a reputation endpoint")
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary CREW_NAME is hidden from CUSTOMER");
	}

	@Test
	void aWrongAnswerFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/public,ANONYMOUS,,,404 NOT_FOUND"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Expected 404 but got 200");
		assertThatThrownBy(() -> run("GET /api/v1/fixture/notes/{noteId},USER,,,404 FORBIDDEN"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Expected code FORBIDDEN but got NOT_FOUND");
		assertThatThrownBy(() -> run("GET /api/v1/fixture/forbidden,USER,,,403 FORBIDDEN OTHER"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Expected reason OTHER but got FIXTURE_NOT_ALLOWED");
	}

	/** Every caller is realized by now, so this runner holds only the note fixture, which realizes no grant. */
	@Test
	void aCallerNoFixtureRealizesFailsTheRow() {
		MatrixRowRunner bare = new MatrixRowRunner(this.mvc, List.of(new NoteFixture()), Canaries.load());
		assertThatThrownBy(
				() -> bare.run(MatrixRow.parse(HEADER + "GET /api/v1/fixture/public,ADMIN_ABUSE_REPORT_GRANT,,,200").getFirst()))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("realizes the caller ADMIN_ABUSE_REPORT_GRANT");
	}

	@Test
	void aSubjectStateNoFixtureUnderstandsFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/public,CUSTOMER,nobody-knows=true,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("does not understand subject_state [nobody-knows]");
	}

	/** A world-binding key rebinds the placeholder before the path is expanded, and no realizer sees it. */
	@Test
	void aWorldBindingKeyNamesTheCrewJobAndIsNoCallerState() throws Exception {
		for (MatrixRow row : MatrixRow.parse(HEADER + """
				GET /api/v1/fixture/jobs/{jobId}/active,ANONYMOUS,,,404 NOT_FOUND
				GET /api/v1/fixture/jobs/{jobId}/active,ANONYMOUS,job=crew,,200
				GET /api/v1/fixture/jobs/{jobId}/active,CUSTOMER,job=crew,,200
				GET /api/v1/fixture/jobs/{jobId}/active,MENTOR,job=crew,,200
				""")) {
			this.runner.run(row);
		}
		assertThatThrownBy(() -> run("GET /api/v1/fixture/jobs/{jobId}/active,ANONYMOUS,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Expected 200 but got 404");
	}

	/** {@code job=post} names the Open Job Post's Job, where the bid lives; the Draft and the crew Job hold none. */
	@Test
	void thePostBindingNamesTheJobWhereTheBidLives() throws Exception {
		for (MatrixRow row : MatrixRow.parse(HEADER + """
				GET /api/v1/fixture/jobs/{jobId}/bids,ANONYMOUS,job=post,,200
				GET /api/v1/fixture/jobs/{jobId}/bids,BIDDER,job=post,,200
				GET /api/v1/fixture/jobs/{jobId}/bids,ANONYMOUS,,,404 NOT_FOUND
				GET /api/v1/fixture/jobs/{jobId}/bids,ANONYMOUS,job=crew,,404 NOT_FOUND
				""")) {
			this.runner.run(row);
		}
		assertThatThrownBy(() -> run("GET /api/v1/fixture/jobs/{jobId}/bids,ANONYMOUS,job=draft,,200"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("binds the world as");
	}

	@Test
	void aPlaceholderNoFixtureSeedsFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/notes/{evidenceId},AUTHOR,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("No fixture seeds {evidenceId}");
	}

	private void run(String line) throws Exception {
		this.runner.run(MatrixRow.parse(HEADER + line).getFirst());
	}

}
