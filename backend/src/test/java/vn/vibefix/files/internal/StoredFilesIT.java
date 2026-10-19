package vn.vibefix.files.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PersonalDataParticipantContract;
import vn.vibefix.support.TestFiles;

/**
 * AD-21, AD-24: what {@code files} keeps, where, and for how long. The dev profile runs the image
 * re-encoding in-process, as the dev stack does.
 */
class StoredFilesIT extends IntegrationTest implements PersonalDataParticipantContract {

	@Autowired
	private StoredFiles files;

	@Autowired
	private FilesPersonalData participant;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void anImageIsStoredReencodedUnderTheAllowedKinds() {
		PreparedFile prepared = this.files.prepare(upload(SUBJECT, "ảnh.gif", TestFiles.gif(5, 5), FileKind.IMAGE));

		assertThat(prepared.mediaType()).isEqualTo("image/png");
		assertThat(prepared.fileName()).isEqualTo("ảnh.png");
		StoredFile stored = inTransaction(() -> this.files.store(prepared));
		assertThat(this.files.exists(stored.id())).isTrue();
		assertThat(this.files.find(stored.id())).hasValue(stored);
	}

	@Test
	void aKindTheUploadDoesNotAcceptIsRefused() {
		assertThatThrownBy(() -> this.files.prepare(upload(SUBJECT, "a.zip", TestFiles.zip(), FileKind.IMAGE)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.reason()).hasValue(FilesReason.FILE_TYPE_NOT_ALLOWED.name()));
		assertThat(this.files.prepare(upload(SUBJECT, "a.zip", TestFiles.zip(), FileKind.ARCHIVE)).kind())
			.isEqualTo(FileKind.ARCHIVE);
	}

	@Test
	void storingNeedsTheCallersTransactionAndARollbackLeavesNoBytes() {
		PreparedFile prepared = this.files.prepare(upload(SUBJECT, "a.txt", TestFiles.text(), FileKind.TEXT));
		assertThatThrownBy(() -> this.files.store(prepared)).isInstanceOf(IllegalTransactionStateException.class);

		AtomicReference<UUID> id = new AtomicReference<>();
		TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);
		transaction.executeWithoutResult(status -> {
			id.set(this.files.store(prepared).id());
			assertThat(this.files.exists(id.get())).isTrue();
			status.setRollbackOnly();
		});

		assertThat(this.files.exists(id.get())).isFalse();
		assertThat(this.files.find(id.get())).isEmpty();
	}

	@Test
	void aDeletedFileHasNoBytesAndIsNotFound() {
		StoredFile stored = store(SUBJECT, "a.pdf", TestFiles.pdf());

		inTransaction(() -> {
			this.files.delete(stored.id());
			this.files.delete(stored.id());
			return null;
		});

		assertThat(this.files.exists(stored.id())).isFalse();
		assertThat(this.files.find(stored.id())).isEmpty();
	}

	@Override
	public PersonalDataParticipant participant() {
		return this.participant;
	}

	@Override
	public String seedSubjectAndOther() {
		store(SUBJECT, "subject-card.pdf", TestFiles.pdf());
		store(OTHER, "other-person-card.pdf", TestFiles.pdf());
		return "other-person-card";
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("SELECT count(*) FROM files.stored_file WHERE account_id = :id")
			.param("id", accountId)
			.query(Long.class)
			.single() > 0;
	}

	@Test
	void anErasureDeletesTheBytesToo() {
		StoredFile subjects = store(SUBJECT, "s.pdf", TestFiles.pdf());
		StoredFile others = store(OTHER, "o.pdf", TestFiles.pdf());

		inTransaction(() -> {
			this.participant.eraseAccount(SUBJECT);
			return null;
		});

		assertThat(this.files.exists(subjects.id())).isFalse();
		assertThat(this.files.exists(others.id())).isTrue();
		assertThat(this.participant.export(SUBJECT)).isEmpty();
		assertThat(this.participant.export(OTHER)).hasValueSatisfying(
				section -> assertThat(section.data().toString()).contains("o.pdf").contains("application/pdf"));
	}

	private StoredFile store(UUID account, String name, byte[] content) {
		PreparedFile prepared = this.files
			.prepare(new FileUpload("test.upload", account, name, content, Set.of(FileKind.PDF)));
		return inTransaction(() -> this.files.store(prepared));
	}

	private <T> T inTransaction(Supplier<T> work) {
		return new TransactionTemplate(this.transactionManager).execute(status -> work.get());
	}

	private static FileUpload upload(UUID account, String name, byte[] content, FileKind kind) {
		return new FileUpload("test.upload", account, name, content, Set.of(kind));
	}

}
