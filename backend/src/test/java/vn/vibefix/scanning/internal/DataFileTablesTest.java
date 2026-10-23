package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import vn.vibefix.scanning.internal.DataFileTables.Table;
import vn.vibefix.scanning.internal.DataFileTables.Unreadable;
import vn.vibefix.scanning.internal.ScanRules.PersonalKind;

/**
 * Story 16.3 (FR-65, SG-12, AD-32): {@code .xlsx} sheets and SQLite tables are read from their bytes alone, under
 * caps, and checked as data files; a broken file, another format, a ZIP bomb, or a pointer past the file is
 * unreadable.
 */
class DataFileTablesTest {

	private static final long TEXT = 1024 * 1024;

	private static final long INFLATE = 8 * 1024 * 1024;

	@Test
	void anXlsxSheetReadsItsHeaderAndItsRowsInTheirColumns() throws Unreadable {
		byte[] workbook = TestWorkbooks.xlsx(List.of(List.of("ho_ten", "so_dien_thoai", "email"),
				List.of("Nguyễn Lan", "0912345601", "lan.nguyen@gmail.com"), List.of("Trần Minh", "", "minh.tran@gmail.com")));

		List<Table> tables = DataFileTables.read("exports/khach-hang.xlsx", workbook, TEXT, INFLATE);

		assertThat(tables).singleElement().satisfies(table -> {
			assertThat(table.header()).containsExactly("ho_ten", "so_dien_thoai", "email");
			assertThat(table.rows()).containsExactly(List.of("Nguyễn Lan", "0912345601", "lan.nguyen@gmail.com"),
					List.of("Trần Minh", "", "minh.tran@gmail.com"));
		});
		assertThat(matcher().findInTables("exports/khach-hang.xlsx", tables)).hasValueSatisfying(hit -> assertThat(
				hit.kinds()).containsExactlyInAnyOrder(PersonalKind.PHONE, PersonalKind.EMAIL, PersonalKind.DATA_COLUMNS));
	}

	@Test
	void aSqliteExportIsReadThroughInteriorAndOverflowPages() throws Unreadable {
		List<Table> tables = DataFileTables.read("data/khach-hang.sqlite", TestWorkbooks.planted("khach-hang.sqlite"), TEXT,
				INFLATE);

		Map<String, Table> byName = tables.stream().collect(Collectors.toMap(Table::name, Function.identity()));
		assertThat(byName).containsOnlyKeys("khach_hang", "settings");
		Table customers = byName.get("khach_hang");
		assertThat(customers.header()).containsExactly("id", "ho_ten", "email", "so_dien_thoai", "ghi_chu");
		assertThat(customers.rows()).hasSize(301);
		assertThat(customers.rows().getFirst()).containsExactly("", "Khách 0", "khach.so0@gmail.com", "0912345600",
				"Đặt bàn tối thứ bảy");
		assertThat(customers.rows().getLast().get(4)).hasSizeGreaterThan(5000).endsWith("nguoi.goi199@yahoo.com");
		assertThat(byName.get("settings").rows()).containsExactly(List.of("theme", "light"));
		assertThat(matcher().findInTables("data/khach-hang.sqlite", tables)).hasValueSatisfying(hit -> assertThat(
				hit.kinds()).containsExactlyInAnyOrder(PersonalKind.PHONE, PersonalKind.EMAIL, PersonalKind.DATA_COLUMNS));
	}

	@Test
	void aCleanDatabaseIsReadAndNotReported() throws Unreadable {
		List<Table> tables = DataFileTables.read("app/cau-hinh.db", TestWorkbooks.planted("cau-hinh.db"), TEXT, INFLATE);

		assertThat(tables).singleElement().satisfies(table -> {
			assertThat(table.name()).as("WITHOUT ROWID tables are left out").isEqualTo("flags");
			assertThat(table.rows()).containsExactly(List.of("booking", "1", "0.5"));
		});
		assertThat(matcher().findInTables("app/cau-hinh.db", tables)).isEmpty();
	}

	@Test
	void utf16TextIsDecoded() throws Unreadable {
		List<Table> tables = DataFileTables.read("data/utf16.sqlite", TestWorkbooks.planted("utf16.sqlite"), TEXT, INFLATE);

		assertThat(tables).singleElement()
			.satisfies(table -> assertThat(table.rows()).contains(List.of("lan.utf16@gmail.com", "0912000111")));
	}

	@Test
	void anythingElseIsUnreadable() {
		byte[] sqlite = TestWorkbooks.planted("khach-hang.sqlite");
		assertThatThrownBy(() -> DataFileTables.read("cache.db", "not a database".getBytes(), TEXT, INFLATE))
			.isInstanceOf(Unreadable.class);
		assertThatThrownBy(() -> DataFileTables.read("cut.sqlite", Arrays.copyOf(sqlite, 4096), TEXT, INFLATE))
			.as("a page past the file")
			.isInstanceOf(Unreadable.class);
		assertThatThrownBy(() -> DataFileTables.read("broken.xlsx", "PK not really a spreadsheet".getBytes(), TEXT, INFLATE))
			.isInstanceOf(Unreadable.class);
		assertThatThrownBy(() -> DataFileTables.read("empty.xlsx", TestWorkbooks.zip(List.of()), TEXT, INFLATE))
			.isInstanceOf(Unreadable.class);
	}

	@Test
	void aZipBombStopsAtTheInflateCap() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
			byte[] zeros = new byte[64 * 1024];
			for (int i = 0; i < 64; i++) {
				zip.write(zeros);
			}
			zip.closeEntry();
		}
		assertThat(bytes.size()).as("small on disk").isLessThan(64 * 1024);

		assertThatThrownBy(() -> DataFileTables.read("bomb.xlsx", bytes.toByteArray(), TEXT, 1024 * 1024))
			.isInstanceOf(Unreadable.class)
			.hasMessageContaining("cap");
	}

	@Test
	void anExternalEntityIsNeverResolved() {
		String sheet = "<?xml version=\"1.0\"?><!DOCTYPE w [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
				+ "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&e;</t></is></c></row></sheetData></worksheet>";
		byte[] workbook = TestWorkbooks.zip(List.of(new String[][] { { "xl/worksheets/sheet1.xml", sheet } }));

		try {
			List<Table> tables = DataFileTables.read("entity.xlsx", workbook, TEXT, INFLATE);
			assertThat(tables.toString()).doesNotContain("root:");
		}
		catch (Unreadable refused) {
			assertThat(refused).hasMessageContaining("broken");
		}
	}

	@Test
	void columnsAreReadFromCreateTableWithoutItsConstraints() {
		assertThat(SqliteTables.columns("""
				CREATE TABLE "orders" ([order id] INTEGER PRIMARY KEY, `customer_email` TEXT, total NUMERIC(10, 2),
				  CONSTRAINT fk FOREIGN KEY (customer_email) REFERENCES c(email), UNIQUE (total))
				""")).containsExactly("order id", "customer_email", "total");
		assertThat(SqliteTables.columns("CREATE TABLE copy AS SELECT * FROM orders")).isEmpty();
	}

	private static PersonalDataMatcher matcher() {
		return new PersonalDataMatcher(ScanRules.load().personalData());
	}

}
