package vn.vibefix.scanning.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Story 16.3's planted files: {@code .xlsx} workbooks built here, part by part, and the SQLite files generated
 * once with Python's {@code sqlite3} (every person and number invented) under {@code scanning/planted/}.
 */
final class TestWorkbooks {

	private TestWorkbooks() {
	}

	/**
	 * A one-sheet workbook: the first row in shared strings, the others as inline strings, every cell referenced
	 * by its column.
	 */
	static byte[] xlsx(List<List<String>> rows) {
		List<String> shared = new ArrayList<>();
		StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
		for (int r = 0; r < rows.size(); r++) {
			sheet.append("<row r=\"").append(r + 1).append("\">");
			for (int c = 0; c < rows.get(r).size(); c++) {
				String value = rows.get(r).get(c);
				String reference = (char) ('A' + c) + Integer.toString(r + 1);
				if (r == 0) {
					sheet.append("<c r=\"").append(reference).append("\" t=\"s\"><v>").append(shared.size()).append("</v></c>");
					shared.add(value);
				}
				else {
					sheet.append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t>").append(escape(value))
						.append("</t></is></c>");
				}
			}
			sheet.append("</row>");
		}
		sheet.append("</sheetData></worksheet>");
		StringBuilder strings = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
		shared.forEach(value -> strings.append("<si><t>").append(escape(value)).append("</t></si>"));
		strings.append("</sst>");
		return zip(List.of(new String[] { "[Content_Types].xml", "<Types/>" },
				new String[] { "xl/workbook.xml", "<workbook/>" }, new String[] { "xl/sharedStrings.xml", strings.toString() },
				new String[] { "xl/worksheets/sheet1.xml", sheet.toString() }));
	}

	/** A ZIP of these {@code {name, text}} parts. */
	static byte[] zip(List<String[]> parts) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (String[] part : parts) {
				zip.putNextEntry(new ZipEntry(part[0]));
				zip.write(part[1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	/** A planted SQLite file from {@code scanning/planted/}. */
	static byte[] planted(String name) {
		try (InputStream in = TestWorkbooks.class.getResourceAsStream("/scanning/planted/" + name)) {
			if (in == null) {
				throw new IllegalStateException("No planted file " + name);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
