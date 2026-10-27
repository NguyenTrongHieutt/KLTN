package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestFiles;

/**
 * A stored file ({@code {fileId}}, AD-20, AD-21): another Customer's screenshot of an Open Job Post,
 * stored through {@code files}, which its read policy shows to that Customer and to any unlocked Mentor
 * while the post is open, and to nobody else, Administrators included. Since Story 19.7 no student card or
 * Mentor document is stored.
 */
public class StoredFileFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final PlatformTransactionManager transactionManager;

	public StoredFileFixture(JdbcClient jdbc, StoredFiles files, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.files = files;
		this.transactionManager = transactionManager;
	}

	@Override
	public void seed(MatrixWorld world) {
		UUID owner = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sản phẩm có ảnh', 'Mô tả', ARRAY['LOVABLE'], :repository, 'matrix/screenshot', 'User',
				    'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", product)
			.param("owner", owner)
			.param("repository", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		UUID post = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Tin có ảnh', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", post)
			.param("product", product)
			.param("owner", owner)
			.param("now", now)
			.param("lapses", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(14))))
			.update();
		var prepared = this.files.prepare(new FileUpload("job.screenshot", owner, "man-hinh-loi.pdf", TestFiles.pdf(),
				Set.of(FileKind.PDF)));
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> {
			StoredFile stored = this.files.store(prepared);
			this.jdbc.sql("""
					INSERT INTO job.job_screenshot (job_id, file_id, file_name, size_bytes, added_at)
					VALUES (:job, :file, :name, :size, :now)
					""")
				.param("job", post)
				.param("file", stored.id())
				.param("name", stored.fileName())
				.param("size", stored.size())
				.param("now", now)
				.update();
			world.put("fileId", stored.id());
		});
	}

}
