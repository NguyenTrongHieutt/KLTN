package vn.vibefix.scanning.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import org.jspecify.annotations.Nullable;

import vn.vibefix.scanning.internal.DataFileTables.Budget;
import vn.vibefix.scanning.internal.DataFileTables.Table;
import vn.vibefix.scanning.internal.DataFileTables.Unreadable;

/**
 * An {@code .xlsx} workbook's sheets (Story 16.3): the ZIP is read in memory with {@code java.util.zip}, only the
 * shared strings and the worksheets kept, and their XML read with StAX, which refuses DTDs and external entities.
 * Each sheet's first row is its header. The ZIP may inflate to at most the given bytes, a bomb guard.
 */
final class XlsxTables {

	private static final String SHARED = "xl/sharedStrings.xml";

	private static final Pattern SHEET = Pattern.compile("xl/worksheets/sheet([0-9]{1,4})\\.xml");

	private static final Pattern COLUMN = Pattern.compile("^([A-Z]{1,3})[0-9]*$");

	/** More entries than any workbook needs. */
	private static final int MAX_ENTRIES = 10_000;

	private static final XMLInputFactory XML = safeFactory();

	private XlsxTables() {
	}

	static List<Table> read(byte[] bytes, Budget budget, long inflatedBytes) throws Unreadable {
		byte[] shared = null;
		Map<Integer, byte[]> sheets = new TreeMap<>();
		long inflated = 0;
		int entries = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = zip.getNextEntry()) != null) {
				if (++entries > MAX_ENTRIES) {
					throw new Unreadable("too many entries");
				}
				Matcher sheet = SHEET.matcher(entry.getName());
				boolean kept = SHARED.equals(entry.getName()) || sheet.matches();
				ByteArrayOutputStream content = kept ? new ByteArrayOutputStream() : null;
				int read;
				while ((read = zip.read(buffer)) > 0) {
					inflated += read;
					if (inflated > inflatedBytes) {
						throw new Unreadable("inflates past the cap");
					}
					if (content != null) {
						content.write(buffer, 0, read);
					}
				}
				if (content != null) {
					if (sheet.matches()) {
						sheets.put(Integer.parseInt(sheet.group(1)), content.toByteArray());
					}
					else {
						shared = content.toByteArray();
					}
				}
			}
		}
		catch (IOException | IllegalArgumentException ex) {
			throw new Unreadable("not a ZIP");
		}
		if (sheets.isEmpty()) {
			throw new Unreadable("no worksheet");
		}
		List<String> strings = shared == null ? List.of() : sharedStrings(shared);
		List<Table> tables = new ArrayList<>();
		for (Map.Entry<Integer, byte[]> sheet : sheets.entrySet()) {
			List<List<String>> rows = rows(sheet.getValue(), strings, budget);
			if (!rows.isEmpty()) {
				List<String> header = rows.getFirst();
				tables.add(new Table("sheet" + sheet.getKey(), header,
						rows.subList(1, rows.size()).stream().map(row -> DataFileTables.fit(row, header.size())).toList()));
			}
			if (budget.spent()) {
				break;
			}
		}
		return tables;
	}

	/** Each {@code <si>}'s text, its {@code <t>} runs joined; phonetic runs ({@code <rPh>}) are left out. */
	private static List<String> sharedStrings(byte[] xml) throws Unreadable {
		List<String> strings = new ArrayList<>();
		XMLStreamReader reader = open(xml);
		try {
			StringBuilder current = null;
			int phonetic = 0;
			boolean text = false;
			while (reader.hasNext()) {
				switch (reader.next()) {
					case XMLStreamConstants.START_ELEMENT -> {
						switch (reader.getLocalName()) {
							case "si" -> current = new StringBuilder();
							case "rPh" -> phonetic++;
							case "t" -> text = phonetic == 0;
							default -> {
							}
						}
					}
					case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
						if (text && current != null) {
							current.append(reader.getText());
						}
					}
					case XMLStreamConstants.END_ELEMENT -> {
						switch (reader.getLocalName()) {
							case "si" -> {
								strings.add(current == null ? "" : current.toString());
								current = null;
							}
							case "rPh" -> phonetic--;
							case "t" -> text = false;
							default -> {
							}
						}
					}
					default -> {
					}
				}
			}
			return strings;
		}
		catch (XMLStreamException ex) {
			throw new Unreadable("broken shared strings");
		}
		finally {
			close(reader);
		}
	}

	/** A sheet's rows, each cell in its column; a shared string by its index, else its inline text or value. */
	private static List<List<String>> rows(byte[] xml, List<String> strings, Budget budget) throws Unreadable {
		List<List<String>> rows = new ArrayList<>();
		XMLStreamReader reader = open(xml);
		try {
			TreeMap<Integer, String> row = null;
			String type = null;
			int column = -1;
			StringBuilder value = null;
			boolean collecting = false;
			while (reader.hasNext() && !budget.spent()) {
				switch (reader.next()) {
					case XMLStreamConstants.START_ELEMENT -> {
						switch (reader.getLocalName()) {
							case "row" -> row = new TreeMap<>();
							case "c" -> {
								type = reader.getAttributeValue(null, "t");
								Integer at = column(reader.getAttributeValue(null, "r"));
								column = at != null ? at : column + 1;
								value = new StringBuilder();
							}
							case "v", "t" -> collecting = value != null;
							default -> {
							}
						}
					}
					case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
						if (collecting) {
							value.append(reader.getText());
						}
					}
					case XMLStreamConstants.END_ELEMENT -> {
						switch (reader.getLocalName()) {
							case "v", "t" -> collecting = false;
							case "c" -> {
								if (row != null && value != null) {
									String cell = cell(type, value.toString(), strings);
									if (!cell.isEmpty() && budget.take(cell)) {
										row.put(column, cell);
									}
								}
								value = null;
							}
							case "row" -> {
								if (row != null) {
									List<String> cells = new ArrayList<>();
									int last = row.isEmpty() ? -1 : row.lastKey();
									for (int i = 0; i <= last; i++) {
										cells.add(row.getOrDefault(i, ""));
									}
									rows.add(cells);
								}
								row = null;
								column = -1;
							}
							default -> {
							}
						}
					}
					default -> {
					}
				}
			}
			return rows;
		}
		catch (XMLStreamException ex) {
			throw new Unreadable("broken worksheet");
		}
		finally {
			close(reader);
		}
	}

	private static String cell(@Nullable String type, String raw, List<String> strings) {
		if ("s".equals(type)) {
			try {
				int index = Integer.parseInt(raw.strip());
				return index >= 0 && index < strings.size() ? strings.get(index) : "";
			}
			catch (NumberFormatException ex) {
				return "";
			}
		}
		return raw;
	}

	/** The column index of a cell reference such as {@code B12}, from 0; {@code null} without one. */
	private static @Nullable Integer column(@Nullable String reference) {
		if (reference == null) {
			return null;
		}
		Matcher letters = COLUMN.matcher(reference);
		if (!letters.matches()) {
			return null;
		}
		int index = 0;
		for (char letter : letters.group(1).toCharArray()) {
			index = index * 26 + (letter - 'A' + 1);
		}
		return index - 1;
	}

	private static XMLStreamReader open(byte[] xml) throws Unreadable {
		try {
			return XML.createXMLStreamReader(new ByteArrayInputStream(xml));
		}
		catch (XMLStreamException ex) {
			throw new Unreadable("broken XML");
		}
	}

	private static void close(XMLStreamReader reader) {
		try {
			reader.close();
		}
		catch (XMLStreamException ex) {
			// Nothing held: the input is a byte array.
		}
	}

	/** No DTD and no external entity, so an entity can neither read a file nor expand without bound. */
	private static XMLInputFactory safeFactory() {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		return factory;
	}

}
