package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.catalog.SkillRenamed;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-12, AD-36, AD-38, SG-39, SG-42 to SG-44: keyword search over Open Job Posts. */
class JobPostSearchIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private PlatformTransactionManager transactions;

	private UUID lan;

	private UUID product;

	private UUID hung;

	@BeforeEach
	void aCustomerWithAProductAndAnApprovedMentor() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.product = product(this.lan);
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null, null);
	}

	@Test
	void accentsAndLetterCaseDoNotMatter() throws Exception {
		job("OPEN", "Sửa trang bán hàng", "Khách không thanh toán được.", "BUG_FIX", "");
		job("OPEN", "Thêm thực đơn đồ ăn", "Quán cần thực đơn online.", "NEW_FEATURE", "");

		assertTitles("ban hang", "Sửa trang bán hàng");
		assertTitles("BAN HANG", "Sửa trang bán hàng");
		assertTitles("do an", "Thêm thực đơn đồ ăn");
		assertTitles("thuc do", "Thêm thực đơn đồ ăn");
		assertTitles("sua loi", "Sửa trang bán hàng");
		assertTitles("tinh nang moi", "Thêm thực đơn đồ ăn");
		assertTitles("!!!");
		assertTitles("\"thanh toan\"", "Sửa trang bán hàng");
	}

	@Test
	void resultsRankByRelevanceThenIdAndCombineWithFilters() throws Exception {
		UUID first = job("OPEN", "Ứng dụng đặt bàn", "Đặt bàn bị trùng.", "BUG_FIX", "");
		UUID second = job("OPEN", "Ứng dụng đặt bàn", "Đặt bàn bị trùng.", "BUG_FIX", "");
		UUID strong = job("OPEN", "Đặt bàn, đặt bàn và đặt bàn", "Đặt bàn nhanh hơn khi đặt bàn.", "NEW_FEATURE", "");
		job("DRAFT", "Đặt bàn nháp", "Đặt bàn.", "BUG_FIX", "");
		UUID hidden = job("OPEN", "Đặt bàn bị ẩn", "Đặt bàn.", "BUG_FIX", "");
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm' WHERE id = :id")
			.param("id", hidden)
			.update();
		List<UUID> ties = first.toString().compareTo(second.toString()) < 0 ? List.of(first, second) : List.of(second, first);

		assertIds("dat ban", "", strong, ties.get(0), ties.get(1));
		assertIds("dat ban", "BUG_FIX", ties.get(0), ties.get(1));
	}

	@Test
	void aRenamedSkillIsFoundByItsNewNameOnly() throws Exception {
		job("OPEN", "Sửa giao diện", "Nút bị lệch.", "BUG_FIX", TeamTestSupport.REACT);
		assertTitles("react", "Sửa giao diện");

		SkillRenamed renamed = new SkillRenamed(UUID.randomUUID(), UUID.fromString(TeamTestSupport.REACT), "Khung Giao Diện X");
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(renamed));
		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM job.job WHERE 'Khung Giao Diện X' = ANY (skill_names)")
				.query(Long.class)
				.single() == 1);

		assertTitles("khung giao dien x", "Sửa giao diện");
		assertTitles("react");
	}

	@Test
	void sixtyKeywordSearchesAMinuteAndNoMore() throws Exception {
		job("OPEN", "Sửa trang bán hàng", "Khách không thanh toán được.", "BUG_FIX", "");
		for (int i = 0; i < SearchWindow.LIMIT; i++) {
			search("ban hang").andExpect(status().isOk());
		}
		this.mvc.perform(get("/api/v1/mentor/job-posts").with(as(this.hung)))
			.andExpect(status().isOk());

		search("ban hang").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(header().string("Retry-After", "60"));
		this.clock.advance(Duration.ofSeconds(61));
		search("ban hang").andExpect(status().isOk());
	}

	private ResultActions search(String q, String... types) throws Exception {
		return this.mvc.perform(get("/api/v1/mentor/job-posts").param("q", q).param("type", types).with(as(this.hung)));
	}

	private void assertTitles(String q, String... titles) throws Exception {
		String body = search(q).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> found = com.jayway.jsonpath.JsonPath.read(body, "$.items[*].title");
		assertThat(found).as(q).containsExactly(titles);
	}

	private void assertIds(String q, String type, UUID... ids) throws Exception {
		String body = (type.isEmpty() ? search(q) : search(q, type)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> found = com.jayway.jsonpath.JsonPath.read(body, "$.items[*].id");
		assertThat(found).as(q).containsExactly(java.util.Arrays.stream(ids).map(UUID::toString).toArray(String[]::new));
	}

	private UUID job(String state, String title, String problem, String type, String skillId) {
		UUID id = UUID.randomUUID();
		boolean open = state.equals("OPEN");
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, job_types, skill_ids,
				    skill_names, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, :state, :title, :problem, CAST(:types AS TEXT[]), CAST(:skills AS UUID[]),
				    CAST(:names AS TEXT[]), CAST(:rules AS JSONB), :days, :opened, :lapses, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", this.product)
			.param("owner", this.lan)
			.param("state", state)
			.param("title", title)
			.param("problem", problem)
			.param("types", "{" + type + "}")
			.param("skills", skillId.isEmpty() ? "{}" : "{" + skillId + "}")
			.param("names", skillId.isEmpty() ? new String[0] : new String[] { "React" })
			.param("rules", open ? "{}" : null)
			.param("days", open ? 14 : null)
			.param("opened", open ? now : null)
			.param("lapses", open ? Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(14))) : null)
			.param("now", now)
			.update();
		return id;
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.MENTOR, this.clock.instant());
	}

}
