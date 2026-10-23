package vn.vibefix.scanning.internal;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.re2j.Pattern;

import vn.vibefix.scanning.internal.DataFileTables.Budget;
import vn.vibefix.scanning.internal.DataFileTables.Table;
import vn.vibefix.scanning.internal.DataFileTables.Unreadable;

/**
 * A SQLite file's tables (Story 16.3), read from its bytes in memory, never opened as a database: the schema
 * table on page 1 names each ordinary table, its root page, and its {@code CREATE TABLE}, whose column list is
 * the header; each table b-tree is walked through interior pages to its leaf cells, whose records are decoded,
 * overflow pages followed. Every page is visited at most once and must lie inside the file, so a cycle or a
 * pointer past the end makes the file unreadable. Text is UTF-8 or UTF-16 as the header says; blobs are skipped;
 * {@code WITHOUT ROWID} tables (index b-trees) are left out.
 */
final class SqliteTables {

	private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

	private static final Pattern WITHOUT_ROWID = Pattern.compile("(?i)\\bwithout\\s+rowid\\b");

	private static final Set<String> CONSTRAINTS = Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN");

	private static final int TABLE_INTERIOR = 0x05;

	private static final int TABLE_LEAF = 0x0D;

	private SqliteTables() {
	}

	static List<Table> read(byte[] db, Budget budget) throws Unreadable {
		Reader reader = new Reader(db);
		List<Table> tables = new ArrayList<>();
		for (List<String> entry : reader.table(1, new Budget(Long.MAX_VALUE))) {
			if (entry.size() < 5 || !"table".equals(entry.get(0)) || entry.get(1).startsWith("sqlite_")
					|| WITHOUT_ROWID.matcher(entry.get(4)).find()) {
				continue;
			}
			int root;
			try {
				root = Integer.parseInt(entry.get(3));
			}
			catch (NumberFormatException ex) {
				continue;
			}
			if (root < 1) {
				continue;
			}
			List<String> columns = columns(entry.get(4));
			List<List<String>> rows = reader.table(root, budget);
			tables.add(new Table(entry.get(1), columns,
					rows.stream().map(row -> columns.isEmpty() ? row : DataFileTables.fit(row, columns.size())).toList()));
			if (budget.spent()) {
				break;
			}
		}
		return tables;
	}

	/** The column names of a {@code CREATE TABLE}, table constraints left out; none for {@code AS SELECT}. */
	static List<String> columns(String sql) {
		int open = sql.indexOf('(');
		List<String> columns = new ArrayList<>();
		if (open < 0) {
			return columns;
		}
		List<String> items = new ArrayList<>();
		StringBuilder item = new StringBuilder();
		char quote = 0;
		int depth = 0;
		for (int i = open + 1; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (quote != 0) {
				item.append(c);
				if (c == quote) {
					quote = 0;
				}
				continue;
			}
			if (c == '"' || c == '`' || c == '\'') {
				quote = c;
			}
			else if (c == '[') {
				quote = ']';
			}
			else if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				if (depth == 0) {
					break;
				}
				depth--;
			}
			else if (c == ',' && depth == 0) {
				items.add(item.toString());
				item.setLength(0);
				continue;
			}
			item.append(c);
		}
		items.add(item.toString());
		for (String raw : items) {
			String name = firstToken(raw.strip());
			if (!name.isEmpty() && !CONSTRAINTS.contains(name.toUpperCase(Locale.ROOT))) {
				columns.add(name);
			}
		}
		return columns;
	}

	private static String firstToken(String item) {
		if (item.isEmpty()) {
			return "";
		}
		char first = item.charAt(0);
		char close = first == '[' ? ']' : first;
		if (first == '"' || first == '`' || first == '\'' || first == '[') {
			int end = item.indexOf(close, 1);
			return end < 0 ? item.substring(1) : item.substring(1, end);
		}
		int end = 0;
		while (end < item.length() && !Character.isWhitespace(item.charAt(end)) && item.charAt(end) != '(') {
			end++;
		}
		return item.substring(0, end);
	}

	/** The file's pages, each visited at most once. */
	private static final class Reader {

		private final byte[] db;

		private final int pageSize;

		private final int usable;

		private final Charset charset;

		private final int pages;

		private final Set<Integer> visited = new HashSet<>();

		Reader(byte[] db) throws Unreadable {
			if (db.length < 100 || !Arrays.equals(db, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
				throw new Unreadable("not SQLite");
			}
			this.db = db;
			int size = u16(db, 16);
			this.pageSize = size == 1 ? 65536 : size;
			if (this.pageSize < 512 || Integer.bitCount(this.pageSize) != 1) {
				throw new Unreadable("bad page size");
			}
			this.usable = this.pageSize - (db[20] & 0xFF);
			if (this.usable < 480) {
				throw new Unreadable("bad reserved space");
			}
			long encoding = u32(db, 56);
			this.charset = encoding == 2 ? StandardCharsets.UTF_16LE
					: encoding == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8;
			this.pages = db.length / this.pageSize;
			if (this.pages < 1) {
				throw new Unreadable("shorter than a page");
			}
		}

		/** Each row's values, in rowid order, of the table b-tree rooted at {@code root}. */
		List<List<String>> table(int root, Budget budget) throws Unreadable {
			List<List<String>> rows = new ArrayList<>();
			Deque<Integer> stack = new ArrayDeque<>();
			stack.push(root);
			while (!stack.isEmpty() && !budget.spent()) {
				int page = stack.pop();
				int base = visit(page);
				int header = page == 1 ? base + 100 : base;
				int type = this.db[header] & 0xFF;
				if (type != TABLE_INTERIOR && type != TABLE_LEAF) {
					throw new Unreadable("not a table page");
				}
				int cells = u16(this.db, header + 3);
				int pointers = header + (type == TABLE_INTERIOR ? 12 : 8);
				if (pointers + 2L * cells > base + this.pageSize) {
					throw new Unreadable("cell pointers past the page");
				}
				if (type == TABLE_INTERIOR) {
					List<Integer> children = new ArrayList<>();
					for (int i = 0; i < cells; i++) {
						children.add((int) u32(this.db, cell(base, pointers, i)));
					}
					children.add((int) u32(this.db, header + 8));
					for (int i = children.size() - 1; i >= 0; i--) {
						stack.push(children.get(i));
					}
					continue;
				}
				for (int i = 0; i < cells && !budget.spent(); i++) {
					int at = cell(base, pointers, i);
					long[] payload = varint(this.db, at);
					at += (int) payload[1];
					at += (int) varint(this.db, at)[1];
					rows.add(values(payload(base, at, payload[0]), budget));
				}
			}
			return rows;
		}

		private int cell(int base, int pointers, int index) throws Unreadable {
			int offset = u16(this.db, pointers + 2 * index);
			if (offset < 8 || offset >= this.pageSize) {
				throw new Unreadable("cell past the page");
			}
			return base + offset;
		}

		/** The byte offset of {@code page}, marking it visited; a page out of the file or seen before is refused. */
		private int visit(int page) throws Unreadable {
			if (page < 1 || page > this.pages || !this.visited.add(page)) {
				throw new Unreadable("page out of the file or visited twice");
			}
			return (page - 1) * this.pageSize;
		}

		/** A table leaf cell's whole payload: its local part, then its overflow pages. */
		private byte[] payload(int base, int at, long size) throws Unreadable {
			if (size < 0 || size > (long) this.db.length * 2) {
				throw new Unreadable("payload larger than the file");
			}
			int total = (int) size;
			int most = this.usable - 35;
			int least = ((this.usable - 12) * 32 / 255) - 23;
			int local = total;
			if (total > most) {
				int spill = least + ((total - least) % (this.usable - 4));
				local = spill <= most ? spill : least;
			}
			if (at + local > base + this.pageSize || at + local > this.db.length) {
				throw new Unreadable("payload past the page");
			}
			byte[] payload = new byte[total];
			System.arraycopy(this.db, at, payload, 0, local);
			int filled = local;
			if (filled < total) {
				int next = (int) u32(this.db, at + local);
				while (filled < total) {
					if (next == 0) {
						throw new Unreadable("overflow chain ends early");
					}
					int page = visit(next);
					next = (int) u32(this.db, page);
					int length = Math.min(total - filled, this.usable - 4);
					System.arraycopy(this.db, page + 4, payload, filled, length);
					filled += length;
				}
			}
			return payload;
		}

		/** A record's values as text: integers and reals in decimal, NULL and blobs as empty. */
		private List<String> values(byte[] record, Budget budget) throws Unreadable {
			long[] headerSize = varint(record, 0);
			if (headerSize[0] > record.length) {
				throw new Unreadable("record header past the record");
			}
			List<Long> types = new ArrayList<>();
			int at = (int) headerSize[1];
			while (at < headerSize[0]) {
				long[] type = varint(record, at);
				types.add(type[0]);
				at += (int) type[1];
			}
			int body = (int) headerSize[0];
			List<String> values = new ArrayList<>();
			for (long type : types) {
				int length = length(type);
				if (body + length > record.length) {
					throw new Unreadable("value past the record");
				}
				String value = value(type, record, body, length);
				body += length;
				values.add(budget.take(value) ? value : "");
			}
			return values;
		}

		private String value(long type, byte[] record, int at, int length) {
			if (type >= 1 && type <= 6) {
				long number = record[at];
				for (int i = 1; i < length; i++) {
					number = (number << 8) | (record[at + i] & 0xFF);
				}
				return Long.toString(number);
			}
			if (type == 7) {
				long bits = 0;
				for (int i = 0; i < 8; i++) {
					bits = (bits << 8) | (record[at + i] & 0xFF);
				}
				double real = Double.longBitsToDouble(bits);
				return real == Math.rint(real) && !Double.isInfinite(real) ? Long.toString((long) real) : Double.toString(real);
			}
			if (type == 8) {
				return "0";
			}
			if (type == 9) {
				return "1";
			}
			if (type >= 13 && type % 2 == 1) {
				return new String(record, at, length, this.charset);
			}
			return "";
		}

		private static int length(long type) throws Unreadable {
			if (type == 0 || type == 8 || type == 9) {
				return 0;
			}
			if (type >= 1 && type <= 4) {
				return (int) type;
			}
			if (type == 5) {
				return 6;
			}
			if (type == 6 || type == 7) {
				return 8;
			}
			if (type >= 12 && type <= Integer.MAX_VALUE) {
				return (int) ((type - (type % 2 == 0 ? 12 : 13)) / 2);
			}
			throw new Unreadable("bad serial type");
		}

	}

	/** A SQLite varint at {@code at}: its value and its length in bytes. */
	private static long[] varint(byte[] bytes, int at) throws Unreadable {
		long value = 0;
		for (int i = 0; i < 9; i++) {
			if (at + i >= bytes.length) {
				throw new Unreadable("varint past the end");
			}
			int b = bytes[at + i] & 0xFF;
			if (i == 8) {
				return new long[] { (value << 8) | b, 9 };
			}
			value = (value << 7) | (b & 0x7F);
			if ((b & 0x80) == 0) {
				return new long[] { value, i + 1 };
			}
		}
		throw new Unreadable("bad varint");
	}

	private static int u16(byte[] bytes, int at) throws Unreadable {
		if (at < 0 || at + 2 > bytes.length) {
			throw new Unreadable("read past the end");
		}
		return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
	}

	private static long u32(byte[] bytes, int at) throws Unreadable {
		if (at < 0 || at + 4 > bytes.length) {
			throw new Unreadable("read past the end");
		}
		return ((long) (bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16) | ((bytes[at + 2] & 0xFF) << 8)
				| (bytes[at + 3] & 0xFF);
	}

}
