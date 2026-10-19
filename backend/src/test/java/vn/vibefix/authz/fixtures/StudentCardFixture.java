package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.TestFiles;

/**
 * Student cards (FR-5): another Student's pending card ({@code {cardId}}) and its file
 * ({@code {fileId}}), stored through {@code files} and named with the canary
 * {@code STUDENT_CARD_FILE}, which only Administrators may receive. Each seed draws a new student
 * number ({@code {cardNumber}}), so an approval never collides with an earlier row's on one database.
 */
public class StudentCardFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final PlatformTransactionManager transactionManager;

	private final Canaries canaries = Canaries.load();

	public StudentCardFixture(JdbcClient jdbc, StoredFiles files, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.files = files;
		this.transactionManager = transactionManager;
	}

	@Override
	public void seed(MatrixWorld world) {
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		String name = this.canaries.get("STUDENT_CARD_FILE").value();
		var prepared = this.files.prepare(new FileUpload("identity.student_card", student, name, TestFiles.pdf(),
				Set.of(FileKind.PDF)));
		UUID card = UUID.randomUUID();
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> {
			StoredFile stored = this.files.store(prepared);
			this.jdbc.sql("""
					INSERT INTO identity.student_card (id, account_id, file_id, file_name, media_type, status, uploaded_at)
					VALUES (:id, :account, :file, :name, :type, 'PENDING', :now)
					""")
				.param("id", card)
				.param("account", student)
				.param("file", stored.id())
				.param("name", stored.fileName())
				.param("type", stored.mediaType())
				.param("now", Timestamp.from(stored.storedAt()))
				.update();
			world.put("fileId", stored.id());
		});
		world.put("cardId", card);
		world.put("cardNumber", "SV" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
	}

}
