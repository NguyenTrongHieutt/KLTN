package vn.vibefix.scanning.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import vn.vibefix.scanning.internal.ScanRules.PersonalKind;

/**
 * SM-7's planted personal data (addendum §B6): one text file for each pattern, keyed by its path,
 * with the kinds the Personal-Data Check must report. Every person and number is invented.
 */
final class PlantedPersonalData {

	private PlantedPersonalData() {
	}

	/** A Vietnamese mobile number, distinct for each {@code i}. */
	static String phone(int i) {
		return "09" + String.format("%08d", 12_345_600 + i);
	}

	/** A Lovable-style seed file: {@code count} customers with a name, an email, and a phone. */
	static String customersJson(int count) {
		StringBuilder json = new StringBuilder("[\n");
		for (int i = 0; i < count; i++) {
			json.append("  { \"name\": \"Khách ").append(i).append("\", \"email\": \"khach.hang").append(i)
				.append("@gmail.com\", \"phone\": \"").append(phone(i)).append("\" }")
				.append(i + 1 < count ? ",\n" : "\n");
		}
		return json.append("]\n").toString();
	}

	/** Each planted file and the kinds it holds. */
	static Map<String, Map.Entry<String, java.util.Set<PersonalKind>>> all() {
		Map<String, Map.Entry<String, java.util.Set<PersonalKind>>> planted = new LinkedHashMap<>();
		planted.put("seed/customers.json", Map.entry(customersJson(12),
				java.util.Set.of(PersonalKind.EMAIL, PersonalKind.PHONE, PersonalKind.DATA_COLUMNS)));
		StringBuilder phones = new StringBuilder("Danh sách gọi lại\n");
		for (int i = 20; i < 31; i++) {
			phones.append(phone(i)).append(" - đã gọi\n");
		}
		planted.put("notes/goi-lai.txt", Map.entry(phones.toString(), java.util.Set.of(PersonalKind.PHONE)));
		StringBuilder emails = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			emails.append("Gửi khuyến mãi tới nguoi.mua").append(i).append("@yahoo.com\n");
		}
		planted.put("marketing/emails.md", Map.entry(emails.toString(), java.util.Set.of(PersonalKind.EMAIL)));
		planted.put("src/kyc.ts", Map.entry("export const demo = { soCccd: '079203001234' }\n",
				java.util.Set.of(PersonalKind.CITIZEN_ID)));
		planted.put("exports/khach-hang.csv", Map.entry("ho_ten,so_dien_thoai,dia_chi\nNguyễn Lan," + phone(40)
				+ ",12 Hàng Bạc\nTrần Minh," + phone(41) + ",3 Lê Lợi\n",
				java.util.Set.of(PersonalKind.PHONE, PersonalKind.DATA_COLUMNS)));
		planted.put("supabase/seed.sql", Map.entry("""
				INSERT INTO public.profiles (id, email, cmnd) VALUES
				  (1, 'lan.nguyen@gmail.com', '123456789'),
				  (2, 'minh.tran@gmail.com', '987654321');
				""", java.util.Set.of(PersonalKind.EMAIL, PersonalKind.CITIZEN_ID, PersonalKind.DATA_COLUMNS)));
		return planted;
	}

}
