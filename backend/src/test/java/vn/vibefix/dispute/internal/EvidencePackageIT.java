package vn.vibefix.dispute.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.Role;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 15.2 (FR-53, FR-63, AD-8, AD-20, AD-31): taking a Dispute pushes its read grant down to {@code chat}
 * and {@code work}; the assignee reads the Evidence Package's nine tabs, each view audited, never the Crew
 * Chat; a takeover ends the old grant at once, and the decision ends it 30 days later. Story 15.3 (SG-27,
 * AD-24, AD-31): the decision keeps an evidence snapshot of IDs, versions, counts, and totals that outlives the
 * NFR-10 retirement of the Job's data and the erasure of the people.
 */
class EvidencePackageIT extends IntegrationTest {

	private static final String SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private DisputePersonalData disputePersonalData;

	private HeldCrewJob world;

	private Administrator ha;

	private UUID page;

	private UUID item;

	/** A second Administrator, without a step-up secret: only the first is enrolled through the bootstrap. */
	private UUID quan;

	@BeforeEach
	void aDisputedJobWithItsRecordsAndTwoAdministrators() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.quan = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, 'quan.le@vibefix.test', 'Lê Quân', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", this.quan).update();
		Timestamp at = Timestamp.from(this.clock.instant().minus(Duration.ofDays(2)));
		message(chat("JOB"), "Bản sửa đã gửi hôm qua, khách kiểm tra giúp mình.", at);
		message(chat("CREW"), "Tin nhắn riêng của nhóm thực hiện.", at);
		UUID criterion = this.jdbc
			.sql("SELECT (content->'milestones'->0->'criteria'->0->>'id')::uuid FROM job.confirmed_agreement WHERE job_id = :job")
			.param("job", this.world.job())
			.query(UUID.class)
			.single();
		UUID handover = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by)
				VALUES (:id, :job, :product, :milestone, 1, 'Giai đoạn 1', 1, :mentor, 'REVISION_REQUESTED', 5, :transition, :at,
				    :at, :at, :lan)
				""")
			.param("id", handover).param("job", this.world.job()).param("product", this.world.product())
			.param("milestone", this.world.milestone()).param("mentor", this.world.hung()).param("transition", UUID.randomUUID())
			.param("at", at).param("lan", this.world.lan()).update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover_criterion (handover_id, criterion_id, number, statement, check_steps, met, note)
				VALUES (:id, :criterion, 1, 'Tiêu chí 1', 'Cách kiểm tra 1', true, 'Đã kiểm tra trên điện thoại')
				""").param("id", handover).param("criterion", criterion).update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover_check (handover_id, criterion_id, met, comment)
				VALUES (:id, :criterion, false, 'Vẫn đặt trùng bàn')
				""").param("id", handover).param("criterion", criterion).update();
		this.jdbc.sql("""
				INSERT INTO repo.drift_warning (id, job_id, product_id, kind, head_sha, commits, detected_at)
				VALUES (:id, :job, :product, 'COMMITS', :sha, ARRAY[:sha], :at)
				""").param("id", UUID.randomUUID()).param("job", this.world.job()).param("product", this.world.product())
			.param("sha", SHA).param("at", at).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 1, :at)
				ON CONFLICT DO NOTHING
				""").param("job", this.world.job()).param("product", this.world.product()).param("at", at).update();
		this.page = UUID.randomUUID();
		UUID page = this.page;
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_by_account_id, created_at, updated_at)
				VALUES (:id, :job, 'Ghi chú kỹ thuật', 1, :mentor, :at, :at)
				""").param("id", page).param("job", this.world.job()).param("mentor", this.world.hung()).param("at", at).update();
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, created_at)
				VALUES (:id, 1, 'Ghi chú kỹ thuật', '# Cách chạy', :mentor, :at)
				""").param("id", page).param("mentor", this.world.hung()).param("at", at).update();
		this.item = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, created_at,
				    updated_at)
				VALUES (:id, :job, 1, 'STORY', 'Tiêu chí 1', 'Cách kiểm tra 1', :criterion, :milestone, :at, :at)
				""").param("id", this.item).param("job", this.world.job()).param("criterion", criterion)
			.param("milestone", this.world.milestone()).param("at", at).update();
	}

	@Test
	void theAssigneeReadsEveryTabUnderTheGrantAndEveryViewIsAudited() throws Exception {
		UUID dispute = open();
		evidence(this.ha.accountId(), dispute, "agreement").andExpect(status().isNotFound());
		this.mvc.perform(get(jobChat()).with(admin(this.ha.accountId()))).andExpect(status().isNotFound());

		take(this.ha.accountId(), dispute).andExpect(jsonPath("$.evidenceReadable").value(true));
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin(this.quan)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.evidenceReadable").value(false));
		evidence(this.quan, dispute, "agreement").andExpect(status().isNotFound());

		evidence(this.ha.accountId(), dispute, "agreement").andExpect(status().isOk())
			.andExpect(jsonPath("$[0].versionNo").value(1))
			.andExpect(jsonPath("$[0].milestones[0].criteria[0].statement").value("Tiêu chí 1"));
		evidence(this.ha.accountId(), dispute, "criteria").andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("REVISION_REQUESTED"))
			.andExpect(jsonPath("$[0].criteria[0].reportedMet").value(true))
			.andExpect(jsonPath("$[0].criteria[0].customerMet").value(false))
			.andExpect(jsonPath("$[0].criteria[0].customerComment").value("Vẫn đặt trùng bàn"));
		evidence(this.ha.accountId(), dispute, "decisions").andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.kind == 'AGREEMENT_APPROVED')].versionNo").value(1))
			.andExpect(jsonPath("$[?(@.kind == 'HANDOVER_REVISION_REQUESTED')].milestoneNumber").value(1));
		evidence(this.ha.accountId(), dispute, "payments").andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].moneyIn[0].type").value("FUND"))
			.andExpect(jsonPath("$[0].moneyIn[0].amountVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$[0].releasedTotalVnd").value(0))
			.andExpect(jsonPath("$[0].releasableVnd").value(HeldCrewJob.PRICE));
		evidence(this.ha.accountId(), dispute, "drift").andExpect(status().isOk())
			.andExpect(jsonPath("$[0].kind").value("COMMITS"))
			.andExpect(jsonPath("$[0].commits[0]").value(SHA));
		evidence(this.ha.accountId(), dispute, "audit").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.action == 'dispute.dispute_taken')].actorName").value("Hà Phương"));

		this.mvc.perform(get(jobChat()).with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Bản sửa đã gửi hôm qua")));
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/crew-chat/messages").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/pages").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Ghi chú kỹ thuật")));
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/work-items").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(this.item.toString()));
		this.mvc.perform(get("/api/v1/work-items/" + this.item).with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.item.title").value("Tiêu chí 1"));
		this.mvc.perform(get("/api/v1/pages/" + this.page + "/versions").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].versionNo").value(1));
		this.mvc.perform(get("/api/v1/pages/" + this.page + "/versions/1").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("# Cách chạy"));
		this.mvc.perform(get("/api/v1/admin/jobs/" + this.world.job() + "/crew-terms").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());

		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action LIKE 'dispute.evidence_viewed.%' AND actor_account_id = '"
				+ this.ha.accountId() + "'")).isEqualTo(6);
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'chat.admin_read' AND actor_account_id = '"
				+ this.ha.accountId() + "'")).isOne();
	}

	@Test
	void aTakeoverEndsTheOldGrantAndTheDecisionEndsItThirtyDaysLater() throws Exception {
		UUID dispute = open();
		take(this.ha.accountId(), dispute);
		take(this.quan, dispute).andExpect(jsonPath("$.evidenceReadable").value(true));
		evidence(this.ha.accountId(), dispute, "agreement").andExpect(status().isNotFound());
		this.mvc.perform(get(jobChat()).with(admin(this.ha.accountId()))).andExpect(status().isNotFound());
		evidence(this.quan, dispute, "agreement").andExpect(status().isOk());
		this.mvc.perform(get(jobChat()).with(admin(this.quan))).andExpect(status().isOk());

		take(this.ha.accountId(), dispute);
		take(this.ha.accountId(), dispute);
		assertThat(count("SELECT count(*) FROM chat.admin_read_grant WHERE reason_id = '" + dispute + "'")).isEqualTo(3);
		this.mvc.perform(get(jobChat()).with(admin(this.quan))).andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/dispute/statement").with(as(this.world.hung(), Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"Khách đổi yêu cầu giữa chừng.\"}")).andExpect(status().isOk());
		Instant decidedAt = this.clock.instant();
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/decision").with(admin(this.ha.accountId())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"releaseVnd\":500000,\"refundVnd\":500000,\"nextState\":\"END\",\"reasoning\":\"Hai bên cùng có lỗi\","
					+ "\"stepUpCode\":\"" + AdministratorTestSupport.code(this.ha, decidedAt) + "\"}"))
			.andExpect(status().isOk());
		assertThat(this.jdbc.sql("""
				SELECT ends_at FROM work.admin_read_grant g JOIN dispute.dispute d ON d.grant_id = g.grant_id WHERE d.id = :id
				""").param("id", dispute).query(Timestamp.class).single().toInstant()).isEqualTo(decidedAt.plus(Duration.ofDays(30)));

		this.clock.advance(Duration.ofDays(29));
		evidence(this.ha.accountId(), dispute, "payments").andExpect(status().isOk())
			.andExpect(jsonPath("$[0].moneyIn.length()").value(1))
			.andExpect(jsonPath("$[0].refunds[0].reason").value("DISPUTE_DECISION"))
			.andExpect(jsonPath("$[0].releasedTotalVnd").value(500000))
			.andExpect(jsonPath("$[0].releasableVnd").value(0))
			.andExpect(content().string(not(containsString("party"))));
		this.mvc.perform(get(jobChat()).with(admin(this.ha.accountId()))).andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(2));
		evidence(this.ha.accountId(), dispute, "agreement").andExpect(status().isNotFound());
		this.mvc.perform(get(jobChat()).with(admin(this.ha.accountId()))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.evidenceReadable").value(false));
	}

	@Test
	void theDecisionKeepsItsEvidenceSnapshotAfterRetentionAndErasure() throws Exception {
		UUID dispute = open();
		take(this.ha.accountId(), dispute);
		this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/dispute/statement").with(as(this.world.hung(), Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"Khách đổi yêu cầu giữa chừng.\"}")).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/decision").with(admin(this.ha.accountId())).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"releaseVnd\":500000,\"refundVnd\":500000,\"nextState\":\"END\",\"reasoning\":\"Hai bên cùng có lỗi\","
					+ "\"stepUpCode\":\"" + AdministratorTestSupport.code(this.ha, this.clock.instant()) + "\"}"))
			.andExpect(status().isOk());

		String snapshot = this.jdbc.sql("SELECT evidence_snapshot::text FROM dispute.decision WHERE dispute_id = :id")
			.param("id", dispute)
			.query(String.class)
			.single();
		assertThat(snapshot).doesNotContain("Bản sửa đã gửi", "Cách chạy", "Vẫn đặt trùng bàn", "Khách đổi yêu cầu",
				this.world.lan().toString(), this.world.hung().toString(), this.ha.accountId().toString());
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.evidenceSnapshot.agreementVersions[0]").value(1))
			.andExpect(jsonPath("$.evidenceSnapshot.reports[0].status").value("REVISION_REQUESTED"))
			.andExpect(jsonPath("$.evidenceSnapshot.reports[0].reportedMet").value(1))
			.andExpect(jsonPath("$.evidenceSnapshot.reports[0].checkedMet").value(0))
			.andExpect(jsonPath("$.evidenceSnapshot.workItems[0].workItemId").value(this.item.toString()))
			.andExpect(jsonPath("$.evidenceSnapshot.pages[0].versionNo").value(1))
			.andExpect(jsonPath("$.evidenceSnapshot.jobChat.messageCount").value(1))
			.andExpect(jsonPath("$.evidenceSnapshot.payments[0].moneyInVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.evidenceSnapshot.payments[0].releasableVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$.evidenceSnapshot.driftWarnings[0].commits").value(1))
			.andExpect(jsonPath("$.evidenceSnapshot.statements[0].side").value("MENTOR"));

		this.personalData.retireJobData(this.world.product());
		assertThat(count("SELECT count(*) FROM work.page WHERE job_id = '" + this.world.job() + "'")).isZero();
		assertThat(count("SELECT count(*) FROM chat.message m JOIN chat.chat c ON c.id = m.chat_id WHERE c.job_id = '"
				+ this.world.job() + "'")).isZero();
		this.disputePersonalData.eraseAccount(this.world.lan());
		this.disputePersonalData.eraseAccount(this.world.hung());
		this.disputePersonalData.eraseAccount(this.ha.accountId());

		assertThat(count("SELECT count(*) FROM dispute.dispute WHERE id = '" + dispute + "' AND opener_account_id IS NULL"))
			.isOne();
		assertThat(count("SELECT count(*) FROM dispute.decision WHERE dispute_id = '" + dispute + "' AND admin_account_id IS NULL"))
			.isOne();
		this.mvc.perform(get("/api/v1/admin/disputes/" + dispute).with(admin(this.quan)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.decision.reasoning").value("Hai bên cùng có lỗi"))
			.andExpect(jsonPath("$.decision.releaseVnd").value(500000))
			.andExpect(jsonPath("$.evidenceSnapshot.pages[0].versionNo").value(1))
			.andExpect(jsonPath("$.evidenceSnapshot.jobChat.messageCount").value(1))
			.andExpect(jsonPath("$.statements[0].text").value("Khách đổi yêu cầu giữa chừng."));
	}

	private UUID open() throws Exception {
		this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/disputes").with(as(this.world.lan(), Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Giai đoạn 1 chưa đạt tiêu chí\"}"))
			.andExpect(status().isCreated());
		return this.jdbc.sql("SELECT id FROM dispute.dispute WHERE job_id = :job").param("job", this.world.job()).query(UUID.class)
			.single();
	}

	private ResultActions take(UUID admin, UUID dispute) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/disputes/" + dispute + "/take").with(admin(admin)).with(csrf()))
			.andExpect(status().isOk());
	}

	private ResultActions evidence(UUID admin, UUID dispute, String tab) throws Exception {
		return this.mvc.perform(get("/api/v1/admin/disputes/" + dispute + "/evidence/" + tab).with(admin(admin)));
	}

	private String jobChat() {
		return "/api/v1/jobs/" + this.world.job() + "/chat/messages";
	}

	private UUID chat(String kind) {
		UUID chat = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at) VALUES (:id, :job, :product, :kind, now())")
			.param("id", chat).param("job", this.world.job()).param("product", this.world.product()).param("kind", kind).update();
		return chat;
	}

	private void message(UUID chat, String body, Timestamp at) {
		this.jdbc.sql("INSERT INTO chat.message (id, chat_id, author_account_id, body, sent_at) VALUES (:id, :chat, :author, :body, :at)")
			.param("id", UUID.randomUUID()).param("chat", chat).param("author", this.world.hung()).param("body", body)
			.param("at", at).update();
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private RequestPostProcessor admin(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
