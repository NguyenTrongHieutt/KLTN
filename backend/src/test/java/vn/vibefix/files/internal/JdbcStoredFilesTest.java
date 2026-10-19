package vn.vibefix.files.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import vn.vibefix.shared.ApiException;

/** AD-21: what is stored is at most 10 MB, an image encoded again included. */
class JdbcStoredFilesTest {

	@Test
	void theLimitHoldsForEveryStoredByte() {
		assertThatCode(() -> JdbcStoredFiles.requireSize(new byte[10], 10)).doesNotThrowAnyException();
		assertThatThrownBy(() -> JdbcStoredFiles.requireSize(new byte[11], 10)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.reason()).hasValue("FILE_TOO_LARGE"));
	}

}
