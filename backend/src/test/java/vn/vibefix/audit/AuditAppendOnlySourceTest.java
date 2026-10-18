package vn.vibefix.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * NFR-7, AD-8: no production code, purge job, or retention job changes the audit tables, so entries
 * live at least as long as the Job data they describe. The triggers would refuse such code at run
 * time; this check refuses it at build time.
 */
class AuditAppendOnlySourceTest {

	static final Pattern CHANGE = Pattern.compile("\\b(UPDATE|DELETE\\s+FROM|TRUNCATE(\\s+TABLE)?)\\s+(ONLY\\s+)?audit\\s*\\.",
			Pattern.CASE_INSENSITIVE);

	@Test
	void noProductionSourceChangesTheAuditTables() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Path.of("src", "main"))) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				if (CHANGE.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
					offenders.add(file.toString());
				}
			}
		}

		assertThat(offenders).isEmpty();
	}

	@Test
	void theCheckRecognizesEveryKindOfChange() {
		for (String sql : List.of("UPDATE audit.audit_entry SET", "delete from audit.audit_entry_object",
				"TRUNCATE audit.audit_entry", "TRUNCATE TABLE ONLY audit.x", "UPDATE ONLY audit .audit_entry")) {
			assertThat(CHANGE.matcher(sql).find()).as(sql).isTrue();
		}
		assertThat(CHANGE.matcher("INSERT INTO audit.audit_entry").find()).isFalse();
		assertThat(CHANGE.matcher("BEFORE UPDATE OR DELETE ON audit.audit_entry").find()).isFalse();
	}

}
