package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import vn.vibefix.scanning.internal.PersonalDataMatcher.Hit;
import vn.vibefix.scanning.internal.ScanRules.PersonalData;
import vn.vibefix.scanning.internal.ScanRules.PersonalKind;

/** FR-65, addendum §B6: the Personal-Data Check's patterns, thresholds, and masks. */
class PersonalDataMatcherTest {

	private static final PersonalData RULES = ScanRules.load().personalData();

	private static final PersonalDataMatcher MATCHER = new PersonalDataMatcher(RULES);

	@Test
	void theThresholdsAreConfigurationAndThePatternsCompile() {
		assertThat(ScanRules.load().version()).isEqualTo(3);
		assertThat(RULES.phones()).isEqualTo(10);
		assertThat(RULES.emails()).isEqualTo(10);
		assertThat(RULES.dataFileValues()).isEqualTo(2);
	}

	@Test
	void aSeedFileOfCustomersIsOneHitWithMaskedSamplesOnly() {
		Optional<Hit> hit = MATCHER.find("seed/customers.json", PlantedPersonalData.customersJson(12));

		assertThat(hit).hasValueSatisfying(found -> {
			assertThat(found.kinds()).containsExactlyInAnyOrder(PersonalKind.EMAIL, PersonalKind.PHONE,
					PersonalKind.DATA_COLUMNS);
			assertThat(found.lines()).isEqualTo(12);
			assertThat(found.samples()).containsExactly("kh••••@gmail.com", "09•• ••• •00");
			assertThat(found.hash()).isEqualTo(SecretMatcher.hash("personal-data", "seed/customers.json"));
		});
		assertThat(RULES.label(hit.orElseThrow().kinds())).isEqualTo("Email và số điện thoại khách hàng");
	}

	@Test
	void tenDistinctPhonesOrEmailsAnywhereAreAHitAndFewerAreNot() {
		StringBuilder phones = new StringBuilder("# Danh bạ\n");
		for (int i = 0; i < 10; i++) {
			phones.append("- Gọi ").append(PlantedPersonalData.phone(i)).append('\n');
		}
		assertThat(MATCHER.find("docs/contacts.md", phones.toString())).hasValueSatisfying(
				found -> assertThat(found.kinds()).containsExactly(PersonalKind.PHONE));
		assertThat(MATCHER.find("README.md", "Gọi 0912345678, 0987654321 hoặc 0355512345; email lan@shopgom.vn, "
				+ "minh@shopgom.vn\n")).isEmpty();
		StringBuilder emails = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			emails.append("khach").append(i).append("@gmail.com\n");
		}
		assertThat(MATCHER.find("notes.txt", emails.toString())).hasValueSatisfying(
				found -> assertThat(found.kinds()).containsExactly(PersonalKind.EMAIL));
		assertThat(MATCHER.find("notes.txt", emails.toString().replace("@gmail.com", "@example.com"))).isEmpty();
	}

	@Test
	void theSameNumberWrittenWithPlus84IsCountedOnce() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 5; i++) {
			String phone = PlantedPersonalData.phone(i);
			text.append(phone).append('\n').append("+84 ").append(phone.substring(1, 4)).append(' ')
				.append(phone.substring(4, 7)).append(' ').append(phone.substring(7)).append('\n');
		}
		assertThat(MATCHER.find("contacts.txt", text.toString())).isEmpty();
		assertThat(PersonalDataMatcher.normalize("+84 912.345-678")).isEqualTo("0912345678");
	}

	@Test
	void aCitizenIdNeedsItsDataContext() {
		assertThat(MATCHER.find("src/form.ts", "const cccd = '012345678901'\n")).hasValueSatisfying(found -> {
			assertThat(found.kinds()).containsExactly(PersonalKind.CITIZEN_ID);
			assertThat(found.samples()).containsExactly("0•••••••••01");
		});
		assertThat(MATCHER.find("src/order.ts", "const orderId = '012345678901'\n")).isEmpty();
		assertThat(MATCHER.find("data/khach.csv", "ho_ten,so_cmnd\nNguyen Van A,123456789\nTran Thi B,987654321\n"))
			.hasValueSatisfying(found -> assertThat(found.kinds()).containsExactlyInAnyOrder(PersonalKind.CITIZEN_ID,
					PersonalKind.DATA_COLUMNS));
	}

	@Test
	void aDumpWithRowsIsAHitAndASchemaIsNot() {
		String dump = """
				INSERT INTO public.users (id, full_name, email) VALUES
				  (1, 'Nguyễn Lan', 'lan.nguyen@gmail.com'),
				  (2, 'Trần Minh', 'minh.tran@yahoo.com'),
				  (3, 'Lê Hoa', 'hoa.le@gmail.com');
				""";
		assertThat(MATCHER.find("supabase/seed.sql", dump)).hasValueSatisfying(found -> {
			assertThat(found.kinds()).containsExactlyInAnyOrder(PersonalKind.EMAIL, PersonalKind.DATA_COLUMNS);
			assertThat(found.lines()).isEqualTo(3);
		});
		assertThat(MATCHER.find("supabase/migrations/001_users.sql",
				"CREATE TABLE public.users (id bigint primary key, email text not null, phone text);\n")).isEmpty();
		assertThat(MATCHER.find("supabase/seed.sql", "INSERT INTO public.users (id, email) VALUES (1, 'lan@gmail.com');\n"))
			.as("one value is under the data-file threshold")
			.isEmpty();
	}

	@Test
	void manifestsAndLockfilesAreNeverPersonalData() {
		assertThat(MATCHER.find("package.json", PlantedPersonalData.customersJson(12))).isEmpty();
		assertThat(MATCHER.find("web/package-lock.json", PlantedPersonalData.customersJson(12))).isEmpty();
	}

	@Test
	void theMasksShowOnlyTheEnds() {
		assertThat(PersonalDataMatcher.maskPhone("0912345678")).isEqualTo("09•• ••• •78");
		assertThat(PersonalDataMatcher.maskEmail("nguyen.lan", "gmail.com")).isEqualTo("ng••••@gmail.com");
		assertThat(PersonalDataMatcher.maskEmail("an", "gmail.com")).isEqualTo("a••••@gmail.com");
		assertThat(PersonalDataMatcher.maskId("123456789")).isEqualTo("1••••••89");
	}

	@Test
	void theTypeNamesTheKindsInOrder() {
		assertThat(RULES.label(Set.of(PersonalKind.CITIZEN_ID))).isEqualTo("Số căn cước khách hàng");
		assertThat(RULES.label(Set.of(PersonalKind.PHONE, PersonalKind.DATA_COLUMNS))).isEqualTo("Số điện thoại khách hàng");
		assertThat(RULES.label(Set.of(PersonalKind.DATA_COLUMNS))).isEqualTo("Tệp dữ liệu khách hàng");
		assertThat(RULES.label(EnumSet.allOf(PersonalKind.class)))
			.isEqualTo("Số căn cước, email và số điện thoại khách hàng");
	}

}
