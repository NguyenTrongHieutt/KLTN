package vn.vibefix.scanning.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tables of a spreadsheet or a database file, extracted for the Personal-Data Check (FR-65, SG-12, Story
 * 16.3): an {@code .xlsx} workbook's sheets ({@link XlsxTables}) or a SQLite file's tables ({@link SqliteTables}).
 * It runs in the scan job, in memory, under the scan's caps; nothing is written to disk and no pointer outside
 * the file is followed.
 */
final class DataFileTables {

	/**
	 * One sheet or table.
	 * @param header the first row of a sheet, or a table's column names
	 */
	record Table(String name, List<String> header, List<List<String>> rows) {

		Table {
			header = List.copyOf(header);
			rows = List.copyOf(rows);
		}

	}

	/** A file that cannot be read within its caps: broken, of another format, or past a structural cap. */
	static final class Unreadable extends Exception {

		Unreadable(String message) {
			super(message, null, false, false);
		}

	}

	/**
	 * How much text one file may yield: extraction stops collecting once {@code remaining} reaches zero, so a
	 * large file is checked on its first part.
	 */
	static final class Budget {

		private long remaining;

		Budget(long chars) {
			this.remaining = chars;
		}

		/** Whether {@code text} still fits; it is counted either way. */
		boolean take(String text) {
			this.remaining -= text.length();
			return this.remaining >= 0;
		}

		boolean spent() {
			return this.remaining < 0;
		}

	}

	private DataFileTables() {
	}

	/**
	 * @param textChars the most text to extract, the scan's per-file cap
	 * @param inflatedBytes the most bytes an {@code .xlsx} may inflate to
	 */
	static List<Table> read(String path, byte[] bytes, long textChars, long inflatedBytes) throws Unreadable {
		Budget budget = new Budget(textChars);
		if (path.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
			return XlsxTables.read(bytes, budget, inflatedBytes);
		}
		return SqliteTables.read(bytes, budget);
	}

	/** A row cut or padded to {@code width} cells, so each cell lines up with its column. */
	static List<String> fit(List<String> row, int width) {
		List<String> fitted = new ArrayList<>(row.subList(0, Math.min(row.size(), width)));
		while (fitted.size() < width) {
			fitted.add("");
		}
		return fitted;
	}

}
