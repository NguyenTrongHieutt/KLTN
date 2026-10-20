package vn.vibefix.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** AD-33: one definition of a Work Item key, {@code {JOBCODE}-{n}}. */
class WorkItemKeyTest {

	@Test
	void formatsAndParsesAKey() {
		assertThat(new WorkItemKey("SDB", 12)).hasToString("SDB-12");
		assertThat(WorkItemKey.parse("SDB2-7")).contains(new WorkItemKey("SDB2", 7));
		assertThat(WorkItemKey.parse("sdb-1")).isEmpty();
		assertThat(WorkItemKey.parse("S-1")).isEmpty();
		assertThat(WorkItemKey.parse("SDB-0")).isEmpty();
		assertThat(WorkItemKey.parse("SDB-01")).isEmpty();
		assertThat(WorkItemKey.parse("2DB-1")).isEmpty();
		assertThat(WorkItemKey.parse("SDB-1 ")).isEmpty();
	}

	@Test
	void findsTheKeysACommitMessageMentions() {
		assertThat(WorkItemKey.mentionedIn("SDB-3: sửa trùng giờ (xem SDB-12, ABCDEFG-1, XSDB-4x, SDB-5)"))
			.containsExactly(new WorkItemKey("SDB", 3), new WorkItemKey("SDB", 12), new WorkItemKey("SDB", 5));
	}

	@Test
	void refusesAnInvalidCodeOrNumber() {
		assertThatThrownBy(() -> new WorkItemKey("sdb", 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkItemKey("SDB", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkItemKey("SDB", 1_000_000)).isInstanceOf(IllegalArgumentException.class);
	}

}
