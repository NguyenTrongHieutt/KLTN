package vn.vibefix.authz;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import vn.vibefix.support.IntegrationTest;

/**
 * The authorization matrix suite (AD-20, NFR-1, SM-5): every row of {@code authz/matrix.csv} runs
 * against the whole application on a freshly restored database, and every row checks that no
 * canary hidden from its caller leaks (SG-55). Stories add rows and fixtures; this class does not
 * change.
 */
class AuthorizationMatrixIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApplicationContext context;

	private MatrixRowRunner runner;

	static List<MatrixRow> rows() {
		return MatrixRow.loadMatrix();
	}

	@BeforeEach
	void createRunner() {
		this.runner = new MatrixRowRunner(this.mvc,
				MatrixRowRunner.discoverFixtures(this.context.getAutowireCapableBeanFactory()), Canaries.load());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	void matrixRow(MatrixRow row) throws Exception {
		this.runner.run(row);
	}

}
