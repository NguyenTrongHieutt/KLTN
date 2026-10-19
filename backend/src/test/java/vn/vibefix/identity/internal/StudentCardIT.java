package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-5, AD-21, AD-20: a student card from upload to decision to deletion. Only Administrators read
 * it, each read is audited, and a card's school and number verify one account.
 */
class StudentCardIT extends IntegrationTest {

	private static final String SCHOOL = "Trường Đại học Thử nghiệm";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private StoredFiles storedFiles;

	@Autowired
	private StudentVerification studentVerification;

	@Autowired
	private Todos todos;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private UUID student;

	private UUID administrator;

	@BeforeEach
	void aStudentAndAnAdministrator() {
		this.student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		this.administrator = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
	}

	@Test
	void anUploadIsStoredReencodedAndWaitsForAnAdministrator() throws Exception {
		upload(this.student, "C:\\fakepath\\thẻ của Lan.jpg", TestFiles.jpegWithExif()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.studentCard.status").value("PENDING"))
			.andExpect(jsonPath("$.studentCard.fileName").value("thẻ của Lan.jpg"));

		Map<String, Object> card = this.jdbc.sql("SELECT * FROM identity.student_card").query().singleRow();
		assertThat(card).containsEntry("status", "PENDING").containsEntry("media_type", "image/jpeg");
		UUID fileId = (UUID) card.get("file_id");
		assertThat(this.storedFiles.find(fileId)).hasValueSatisfying(file -> {
			assertThat(file.purpose()).isEqualTo(StudentCards.PURPOSE);
			assertThat(file.accountId()).isEqualTo(this.student);
		});
		assertThat(audited("identity.student_card_uploaded")).isOne();

		MockHttpServletResponse download = this.mvc.perform(get("/api/v1/files/" + fileId).with(admin()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/jpeg"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Content-Security-Policy", "sandbox"))
			.andExpect(header().string("Content-Disposition",
					"attachment; filename=\"the cua Lan.jpg\"; filename*=UTF-8''th%E1%BA%BB%20c%E1%BB%A7a%20Lan.jpg"))
			.andReturn()
			.getResponse();
		assertThat(download.getHeaders("Content-Security-Policy")).containsExactly("sandbox");
		assertThat(TestFiles.holdsMarker(download.getContentAsByteArray())).as("EXIF stripped").isFalse();
		assertThat(audited("identity.student_card_viewed")).isOne();
	}

	@Test
	void onlyAdministratorsReadACard() throws Exception {
		upload(this.student, "the.png", TestFiles.png(20, 12)).andExpect(status().isCreated());
		UUID fileId = this.jdbc.sql("SELECT file_id FROM identity.student_card").query(UUID.class).single();
		UUID otherStudent = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);

		for (RequestPostProcessor caller : List.of(as(this.student, Role.STUDENT), as(otherStudent, Role.STUDENT),
				as(customer, Role.CUSTOMER))) {
			this.mvc.perform(get("/api/v1/files/" + fileId).with(caller))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		this.mvc.perform(get("/api/v1/files/" + fileId)).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/v1/files/" + UUID.randomUUID()).with(admin())).andExpect(status().isNotFound());
		assertThat(audited("identity.student_card_viewed")).isZero();
	}

	@Test
	void uploadsOutsideTheRulesAreRefused() throws Exception {
		refused(upload(this.student, "big.png", new byte[10 * 1024 * 1024 + 1]), 400, "FILE_TOO_LARGE");
		refused(upload(this.student, "the.svg", TestFiles.svg()), 400, "FILE_TYPE_NOT_ALLOWED");
		refused(upload(this.student, "the.html", TestFiles.html()), 400, "FILE_TYPE_NOT_ALLOWED");
		refused(upload(this.student, "the.webp", TestFiles.webp()), 400, "FILE_TYPE_NOT_ALLOWED");
		refused(upload(this.student, "the.txt", TestFiles.text()), 400, "FILE_TYPE_NOT_ALLOWED");
		refused(upload(this.student, "the.zip", TestFiles.zip()), 400, "FILE_TYPE_NOT_ALLOWED");
		refused(upload(this.student, "huge.png", TestFiles.pngHeaderOnly(8000, 6000)), 400, "IMAGE_TOO_LARGE");

		assertThat(this.jdbc.sql("SELECT count(*) FROM files.stored_file").query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.student_card").query(Long.class).single()).isZero();
	}

	@Test
	void aPdfIsKeptAsItIs() throws Exception {
		upload(this.student, "the-sinh-vien.pdf", TestFiles.pdf()).andExpect(status().isCreated());

		UUID fileId = this.jdbc.sql("SELECT file_id FROM identity.student_card").query(UUID.class).single();
		byte[] served = this.mvc.perform(get("/api/v1/files/" + fileId).with(admin()))
			.andExpect(header().string("Content-Type", "application/pdf"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(served).isEqualTo(TestFiles.pdf());
	}

	@Test
	void aSecondCardWaitsUntilTheFirstIsDecided() throws Exception {
		upload(this.student, "a.png", TestFiles.png(4, 4)).andExpect(status().isCreated());

		refused(upload(this.student, "b.png", TestFiles.png(4, 4)), 409, null);
	}

	@Test
	void thirtyUploadsAnHourIsTheQuota() throws Exception {
		Timestamp now = Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(10)));
		for (int i = 0; i < 30; i++) {
			this.jdbc.sql("""
					INSERT INTO files.stored_file (id, purpose, account_id, file_name, media_type, kind, size_bytes, sha256, stored_at)
					VALUES (:id, 'test.upload', :account, 'a.txt', 'text/plain', 'TEXT', 1, repeat('a', 64), :now)
					""").param("id", UUID.randomUUID()).param("account", this.student).param("now", now).update();
		}

		upload(this.student, "the.png", TestFiles.png(4, 4)).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(jsonPath("$.reason").value("UPLOAD_QUOTA"))
			.andExpect(header().string("Retry-After", String.valueOf(Duration.ofMinutes(50).toSeconds())));
	}

	@Test
	void theQueueListsPendingCardsOldestFirst() throws Exception {
		UUID second = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		upload(this.student, "first.png", TestFiles.png(4, 4)).andExpect(status().isCreated());
		this.clock.advance(Duration.ofMinutes(5));
		upload(second, "second.png", TestFiles.png(4, 4)).andExpect(status().isCreated());

		this.mvc.perform(get("/api/v1/admin/student-cards").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.items[0].fileName").value("first.png"))
			.andExpect(jsonPath("$.items[0].status").value("PENDING"))
			.andExpect(jsonPath("$.items[0].fileUrl").exists());
		this.mvc.perform(get("/api/v1/admin/student-cards?sort=uploadedAt,desc&size=1").with(admin()))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].fileName").value("second.png"));
		this.mvc.perform(get("/api/v1/admin/student-cards?sort=fileName,asc").with(admin()))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/admin/overview").with(admin()))
			.andExpect(jsonPath("$.studentCards.count").value(2))
			.andExpect(jsonPath("$.studentCards.oldestAt").exists());
	}

	@Test
	void anApprovalVerifiesTheStudentAndSchedulesTheDeletion() throws Exception {
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> this.todos.open(
				new TodoRequest(SignIns.VERIFY_STUDENT, this.student, Audience.SUBJECT, this.student, Map.of(), "/ho-so",
						null, null)));
		upload(this.student, "the.png", TestFiles.png(4, 4)).andExpect(status().isCreated());
		UUID card = cardOf(this.student);

		approve(card, "  2020-1234 ", SCHOOL).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.studentNumber").value("2020-1234"))
			.andExpect(jsonPath("$.school").value(SCHOOL));

		assertThat(this.studentVerification.isVerified(this.student)).isTrue();
		this.mvc.perform(get("/api/v1/verification").with(as(this.student, Role.STUDENT)))
			.andExpect(jsonPath("$.status").value("VERIFIED"))
			.andExpect(jsonPath("$.method").value("STUDENT_CARD"))
			.andExpect(jsonPath("$.studentCard").doesNotExist());
		assertThat(this.jdbc.sql("SELECT closed_at IS NOT NULL FROM notification.todo WHERE account_id = :id")
			.param("id", this.student)
			.query(Boolean.class)
			.single()).isTrue();
		assertThat(notifications(this.student)).containsExactly(StudentCards.APPROVED_NOTIFICATION);
		assertThat(this.jdbc.sql("SELECT kind FROM timers.deadline ORDER BY kind").query(String.class).list())
			.contains(StudentCardDeletion.KEY, VerificationDeadlines.EXPIRY.key());
		assertThat(audited("identity.student_card_approved")).isOne();
		approve(card, "2020-1234", SCHOOL).andExpect(status().isConflict());
	}

	@Test
	void aCardVerifiesOneAccount() throws Exception {
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		upload(this.student, "a.png", TestFiles.png(4, 4)).andExpect(status().isCreated());
		upload(other, "b.png", TestFiles.png(4, 4)).andExpect(status().isCreated());
		approve(cardOf(this.student), "SV-2020 1234", SCHOOL).andExpect(status().isOk());

		approve(cardOf(other), "sv20201234", "truong dai hoc  thu nghiem").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_USED_FOR_VERIFICATION"));

		assertThat(this.studentVerification.isVerified(other)).isFalse();
		assertThat(this.jdbc.sql("SELECT status FROM identity.student_card WHERE account_id = :id")
			.param("id", other)
			.query(String.class)
			.single()).isEqualTo("PENDING");
	}

	@Test
	void aRejectionShowsItsReasonAndAnotherCardMayFollow() throws Exception {
		upload(this.student, "mo.png", TestFiles.png(4, 4)).andExpect(status().isCreated());

		this.mvc.perform(post("/api/v1/admin/student-cards/" + cardOf(this.student) + "/reject").with(admin())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Ảnh mờ, không đọc được\"}")).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/verification").with(as(this.student, Role.STUDENT)))
			.andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.studentCard.status").value("REJECTED"))
			.andExpect(jsonPath("$.studentCard.rejectionReason").value("Ảnh mờ, không đọc được"));
		assertThat(this.jdbc.sql("SELECT params->>'reason' FROM notification.notification WHERE account_id = :id")
			.param("id", this.student)
			.query(String.class)
			.single()).isEqualTo("Ảnh mờ, không đọc được");
		assertThat(audited("identity.student_card_rejected")).isOne();

		upload(this.student, "ro.png", TestFiles.png(4, 4)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.studentCard.status").value("PENDING"))
			.andExpect(jsonPath("$.studentCard.fileName").value("ro.png"));
	}

	@Test
	void thirtyDaysAfterTheDecisionTheFileIsDeletedAndTheDeletionChecked() throws Exception {
		upload(this.student, "the.png", TestFiles.png(4, 4)).andExpect(status().isCreated());
		UUID card = cardOf(this.student);
		UUID fileId = this.jdbc.sql("SELECT file_id FROM identity.student_card").query(UUID.class).single();
		approve(card, "20201234", SCHOOL).andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(30).minusMinutes(1));
		this.poller.runDue();
		assertThat(this.storedFiles.exists(fileId)).isTrue();

		this.clock.advance(Duration.ofMinutes(2));
		this.poller.runDue();

		assertThat(this.storedFiles.exists(fileId)).isFalse();
		assertThat(this.storedFiles.find(fileId)).isEmpty();
		assertThat(this.jdbc.sql("SELECT file_deleted_at IS NOT NULL FROM identity.student_card")
			.query(Boolean.class)
			.single()).isTrue();
		assertThat(this.jdbc.sql("SELECT status FROM timers.deadline WHERE kind = :kind")
			.param("kind", StudentCardDeletion.KEY)
			.query(String.class)
			.single()).isEqualTo("FIRED");
		assertThat(audited("identity.student_card_file_deleted")).isOne();
		this.mvc.perform(get("/api/v1/admin/student-cards?status=APPROVED").with(admin()))
			.andExpect(jsonPath("$.items[0].fileUrl").doesNotExist())
			.andExpect(jsonPath("$.items[0].fileName").value("the.png"));
		this.mvc.perform(get("/api/v1/files/" + fileId).with(admin())).andExpect(status().isNotFound());
	}

	@Test
	void aVerifiedStudentUploadsOnlyOnceRenewalOpens() throws Exception {
		upload(this.student, "a.png", TestFiles.png(4, 4)).andExpect(status().isCreated());
		approve(cardOf(this.student), "20201234", SCHOOL).andExpect(status().isOk());

		refused(upload(this.student, "b.png", TestFiles.png(4, 4)), 409, null);
	}

	private ResultActions upload(UUID account, String name, byte[] content) throws Exception {
		return this.mvc.perform(multipart("/api/v1/verification/student-card")
			.file(new MockMultipartFile("file", name, "application/octet-stream", content))
			.with(as(account, Role.STUDENT))
			.with(csrf()));
	}

	private ResultActions approve(UUID card, String number, String school) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/student-cards/" + card + "/approve").with(admin())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentNumber\":\"" + number + "\",\"school\":\"" + school + "\"}"));
	}

	private static void refused(ResultActions result, int status, String reason) throws Exception {
		result.andExpect(status().is(status));
		if (reason != null) {
			result.andExpect(jsonPath("$.reason").value(reason));
		}
	}

	private UUID cardOf(UUID account) {
		return this.jdbc.sql("SELECT id FROM identity.student_card WHERE account_id = :id ORDER BY uploaded_at DESC LIMIT 1")
			.param("id", account)
			.query(UUID.class)
			.single();
	}

	private List<String> notifications(UUID account) {
		return this.jdbc.sql("SELECT type FROM notification.notification WHERE account_id = :id")
			.param("id", account)
			.query(String.class)
			.list();
	}

	private long audited(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
			.param("action", action)
			.query(Long.class)
			.single();
	}

	private RequestPostProcessor admin() {
		return IdentityTestSupport.signedIn(this.sessions, this.administrator, Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
