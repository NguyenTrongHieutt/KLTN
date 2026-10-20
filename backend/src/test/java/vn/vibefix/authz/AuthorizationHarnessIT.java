package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

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
				GET /api/v1/fixture/signed-in,STUDENT,,,200
				GET /api/v1/fixture/admin,ANONYMOUS,,,401
				GET /api/v1/fixture/admin,CUSTOMER,,,404 NOT_FOUND
				GET /api/v1/fixture/admin,MENTOR,,,404 NOT_FOUND
				GET /api/v1/fixture/admin,STUDENT,,,404 NOT_FOUND
				GET /api/v1/fixture/admin,ADMINISTRATOR,,,200
				GET /api/v1/fixture/notes/{noteId},AUTHOR,,,200
				GET /api/v1/fixture/notes/{noteId},STUDENT,,,404 NOT_FOUND
				GET /api/v1/fixture/notes/{noteId},ADMINISTRATOR,,,404 NOT_FOUND
				GET /api/v1/fixture/notes/{noteId},ANONYMOUS,,,401
				POST /api/v1/fixture/notes,AUTHOR,,fixture-note.json,200
				POST /api/v1/fixture/notes,CUSTOMER,,fixture-note.json,404 NOT_FOUND
				GET /api/v1/fixture/forbidden,STUDENT,,,403 FORBIDDEN MENTOR_NOT_APPROVED
				GET /api/v1/fixture/leak-body,CREW_ACTIVE,,,200
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
		assertThatThrownBy(() -> run("GET /api/v1/fixture/leak-header,STUDENT,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Canary CREW_CHAT is hidden from STUDENT but appears in the response header X-Fixture");
	}

	@Test
	void aWrongAnswerFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/public,ANONYMOUS,,,404 NOT_FOUND"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Expected 404 but got 200");
		assertThatThrownBy(() -> run("GET /api/v1/fixture/notes/{noteId},STUDENT,,,404 FORBIDDEN"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Expected code FORBIDDEN but got NOT_FOUND");
		assertThatThrownBy(() -> run("GET /api/v1/fixture/forbidden,STUDENT,,,403 FORBIDDEN OTHER"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Expected reason OTHER but got MENTOR_NOT_APPROVED");
	}

	@Test
	void aCallerNoFixtureRealizesFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/public,CREW_FORMER,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("realizes the caller CREW_FORMER");
	}

	@Test
	void aSubjectStateNoFixtureUnderstandsFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/public,CUSTOMER,nobody-knows=true,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("does not understand subject_state [nobody-knows]");
	}

	@Test
	void aPlaceholderNoFixtureSeedsFailsTheRow() {
		assertThatThrownBy(() -> run("GET /api/v1/fixture/notes/{handoverId},AUTHOR,,,200"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("No fixture seeds {handoverId}");
	}

	private void run(String line) throws Exception {
		this.runner.run(MatrixRow.parse(HEADER + line).getFirst());
	}

}
