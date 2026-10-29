package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.JobContent;
import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * The recruiting Job (FR-81, AD-20, AD-43, Story 21.4): an Agreement Job of the Customer's Product whose Mentor is
 * {@link MatrixCaller#MENTOR}, with no Crew row and its Crew recruitment on for one place ({@code features.largeCrews}
 * is off in the matrix's application), seeking React. {@link MatrixCaller#CREW_APPLICANT}, a user with GitHub linked
 * and no role anywhere, has its pending Crew Application ({@code {crewApplicationId}}), whose note is the
 * {@code CREW_APPLICATION_NOTE} canary and whose proof is a stored file ({@code {crewProofFileId}}) captioned with the
 * {@code CREW_APPLICATION_PROOF_CAPTION} canary and the link {@code CREW_APPLICATION_PROOF_LINK}; only the applicant and
 * the Job's Mentor read them, and no Administrator grant covers them. The applicant also has a proof upload no
 * application took yet ({@code {crewProofUploadId}}), theirs alone. A row's {@code job=recruit} makes {@code {jobId}}
 * this Job ({@code {recruitJobId}}), {@code post=recruit} makes {@code {postId}} it, and {@code file=crew-proof} and
 * {@code file=crew-proof-upload} make {@code {fileId}} the application's proof file or the waiting upload.
 */
final class RecruitingJobWorld {

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private final StoredFiles files;

	private final PlatformTransactionManager transactionManager;

	private final Canaries canaries = Canaries.load();

	private UUID applicant;

	RecruitingJobWorld(JdbcClient jdbc, SessionRepository<? extends Session> sessions, StoredFiles files,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.files = files;
		this.transactionManager = transactionManager;
	}

	void seed(MatrixWorld world, UUID owner, UUID product, UUID mentor) {
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		UUID job = JobContent.job(this.jdbc, product, owner, mentor, "Việc tuyển thành viên ma trận", JobContent.SIGNED,
				"AGREEMENT");
		JobContent.selectedBid(this.jdbc, job, product, owner, mentor, JobContent.SIGNED);
		this.jdbc.sql("""
				UPDATE job.job SET recruiting = true, recruiting_since = :now, open_slots = 1,
				    recruit_skill_ids = ARRAY[CAST(:react AS UUID)], recruit_skill_names = ARRAY['React'],
				    recruit_note = 'Cần một bạn kiểm thử phân quyền'
				WHERE id = :id
				""").param("id", job).param("now", now).param("react", TeamTestSupport.REACT).update();
		world.put("recruitJobId", job);
		this.applicant = TeamTestSupport.mentor(this.jdbc, "Người xin vào nhóm ma trận", List.of(TeamTestSupport.REACT),
				"React");
		UUID application = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.crew_application (id, job_id, product_id, customer_account_id, mentor_account_id,
				    applicant_account_id, status, note, skill_ids, skill_names, public_code_notice_version, submitted_at,
				    lapses_at)
				VALUES (:id, :job, :product, :owner, :mentor, :applicant, 'PENDING', :note, ARRAY[CAST(:react AS UUID)],
				    ARRAY['React'], '1.0', :now, :lapses)
				""")
			.param("id", application)
			.param("job", job)
			.param("product", product)
			.param("owner", owner)
			.param("mentor", mentor)
			.param("applicant", this.applicant)
			.param("note", value("CREW_APPLICATION_NOTE"))
			.param("react", TeamTestSupport.REACT)
			.param("now", now)
			.param("lapses", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(14))))
			.update();
		world.put("crewApplicationId", application);
		UUID proofFile = proofFile("bang-chung-xin-vao-nhom.pdf");
		insertProof(application, job, product, 1, "FILE", proofFile, null, value("CREW_APPLICATION_PROOF_CAPTION"), now);
		insertProof(application, job, product, 2, "LINK", null, value("CREW_APPLICATION_PROOF_LINK"), "Liên kết", now);
		world.put("crewProofFileId", proofFile);
		UUID upload = proofFile("cho-gui-don.pdf");
		insertProof(null, job, product, null, "FILE", upload, null, null, now);
		world.put("crewProofUploadId", upload);
	}

	Optional<RequestPostProcessor> realize(MatrixCaller caller) {
		if (caller != MatrixCaller.CREW_APPLICANT) {
			return Optional.empty();
		}
		return Optional.of(IdentityTestSupport.signedIn(this.sessions, this.applicant, Role.MENTOR,
				MutableClock.DEFAULT_INSTANT));
	}

	/** A proof file of the applicant, stored through {@code files} as an upload of {@code matching.crew_application_proof}. */
	private UUID proofFile(String fileName) {
		var prepared = this.files.prepare(new FileUpload("matching.crew_application_proof", this.applicant, fileName,
				TestFiles.pdf(), Set.of(FileKind.PDF)));
		return Objects.requireNonNull(new TransactionTemplate(this.transactionManager)
			.execute(status -> this.files.store(prepared).id()));
	}

	private void insertProof(@Nullable UUID application, UUID job, UUID product, @Nullable Integer position, String kind,
			@Nullable UUID file, @Nullable String url, @Nullable String caption, Timestamp now) {
		this.jdbc.sql("""
				INSERT INTO matching.crew_application_proof (id, crew_application_id, job_id, product_id,
				    applicant_account_id, position, kind, file_id, file_name, size_bytes, url, caption, added_at)
				VALUES (:id, :application, :job, :product, :applicant, :position, :kind, :file,
				    CASE WHEN :kind = 'FILE' THEN 'bang-chung.pdf' END, CASE WHEN :kind = 'FILE' THEN 100 END, :url, :caption, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("application", application)
			.param("job", job)
			.param("product", product)
			.param("applicant", this.applicant)
			.param("position", position)
			.param("kind", kind)
			.param("file", file)
			.param("url", url)
			.param("caption", caption)
			.param("now", now)
			.update();
	}

	private String value(String canary) {
		return this.canaries.get(canary).value();
	}

}
