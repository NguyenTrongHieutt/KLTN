package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.support.IntegrationTest;

/**
 * Story 22.5 (FR-44, AD-20, SG-31) on the matrix's own world: {@link MatrixRowRunner} only proves that what a caller may
 * not see is absent, so this proves, as {@code CrossRoleIT} does, that what a split's party and Mentor may see is there to
 * find. On MEN-7 a party reads the status of their own open splits ({@code SPLIT_OPEN}) with each pool, and their own part
 * of the decided one, never another party's line ({@code SPLIT_PART}); the Mentor reads the Crew's parts
 * ({@code CREW_SHARE}) and each split's status on JOB-7; a member of no split reads neither.
 */
class PartyPaymentReadsIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApplicationContext context;

	private final Canaries canaries = Canaries.load();

	private List<MatrixFixture> fixtures;

	private MatrixWorld world;

	@BeforeEach
	void theMatrixWorld() {
		this.fixtures = MatrixRowRunner.discoverFixtures(this.context.getAutowireCapableBeanFactory());
		this.world = new MatrixWorld();
		this.fixtures.forEach(fixture -> fixture.seed(this.world));
	}

	@Test
	void eachPartyReadsTheirOwnSplitsOnTheirBalance() throws Exception {
		for (MatrixCaller party : List.of(MatrixCaller.CREW_ACTIVE_PARTY, MatrixCaller.CREW_FORMER_PARTY)) {
			String balance = body(party, "/api/v1/balance");
			assertThat(balance).as(party + ": the open splits' status and pools")
				.contains("\"status\":\"" + value("SPLIT_OPEN") + "\"")
				.contains("\"status\":\"PROPOSED\"")
				.contains("\"status\":\"IN_RESOLUTION\"")
				.contains("\"pending\":true")
				.contains("\"baseAmountVnd\":1250000")
				.contains("\"baseAmountVnd\":3086420");
			assertThat(balance).as(party + ": the decided split, paid").contains("\"finality\":\"DECISION\"");
			assertThat(balance).as(party + ": a pending line holds the pool and no part").doesNotContain(value("SPLIT_PART"));
			assertThat(balance).as(party + ": nobody's line but their own").contains("\"crew\":[]").doesNotContain("\"name\"");
		}
		assertThat(body(MatrixCaller.CREW_ACTIVE_PARTY, "/api/v1/balance")).contains("\"yourPartVnd\":800000");
		assertThat(body(MatrixCaller.CREW_FORMER_PARTY, "/api/v1/balance")).contains("\"yourPartVnd\":600000");
	}

	@Test
	void theMentorReadsTheCrewsPartsAndEachSplitsStatus() throws Exception {
		assertThat(body(MatrixCaller.MENTOR, "/api/v1/balance")).contains(value("CREW_SHARE"))
			.contains("\"status\":\"" + value("SPLIT_OPEN") + "\"")
			.contains("\"role\":\"MENTOR\"");
		assertThat(body(MatrixCaller.MENTOR, "/api/v1/mentor/jobs/" + this.world.get("crewJobId") + "/funding"))
			.contains("\"splitStatus\":\"" + value("SPLIT_OPEN") + "\"")
			.contains("\"splitStatus\":\"IN_RESOLUTION\"")
			.contains("\"paid\":true")
			.contains("\"pooledVnd\":1250000");
	}

	@Test
	void aMemberOfNoSplitReadsNoSplit() throws Exception {
		for (MatrixCaller member : List.of(MatrixCaller.CREW_ACTIVE, MatrixCaller.CREW_FORMER, MatrixCaller.CROSS_ROLE)) {
			String balance = body(member, "/api/v1/balance");
			assertThat(balance).as(member + ": their part of the first release").contains("\"yourPartVnd\":3658214");
			assertThat(balance).as(member.name()).contains("\"status\":null").doesNotContain("\"status\":\"")
				.doesNotContain("\"pending\":true");
		}
		assertThat(body(MatrixCaller.CUSTOMER, "/api/v1/balance")).doesNotContain("\"status\":\"");
	}

	private String body(MatrixCaller caller, String path) throws Exception {
		MockHttpServletResponse response = this.mvc.perform(get(path).with(realize(caller))).andReturn().getResponse();
		assertThat(response.getStatus()).as(caller + " " + path).isEqualTo(200);
		return response.getContentAsString(StandardCharsets.UTF_8);
	}

	private RequestPostProcessor realize(MatrixCaller caller) {
		return this.fixtures.stream()
			.map(fixture -> fixture.realize(caller, Map.of(), this.world))
			.flatMap(Optional::stream)
			.findFirst()
			.orElseThrow(() -> new AssertionError("No fixture realizes " + caller));
	}

	private String value(String canary) {
		return this.canaries.get(canary).value();
	}

}
