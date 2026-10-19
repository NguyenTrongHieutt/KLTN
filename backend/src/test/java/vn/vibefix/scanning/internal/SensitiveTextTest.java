package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.scanning.SensitiveText.Kind;
import vn.vibefix.scanning.SensitiveText.Result;
import vn.vibefix.scanning.internal.PlantedSecrets.Planted;

/** FR-47: chat text checked with the Secret Scan's rules, masked in place, within the length cap. */
class SensitiveTextTest {

	private final SensitiveText check = new SensitiveTextCheck();

	@Test
	void aCredentialIsFoundAndMaskedWhereItSits() {
		Planted token = PlantedSecrets.all().get("github-pat");

		Result result = this.check.check("Khóa của tôi là " + token.value() + " nhé");

		assertThat(result.found()).isTrue();
		assertThat(result.count(Kind.CREDENTIAL)).isOne();
		assertThat(result.maskedText()).startsWith("Khóa của tôi là ").endsWith(" nhé")
			.doesNotContain(token.value())
			.contains(result.matches().getFirst().masked());
		assertThat(result.matches().getFirst().masked()).contains(SecretMatcher.BULLETS);
	}

	@Test
	void personalDataCountsOnlyInBulk() {
		String few = "Gửi cho lan@quanmay.vn hoặc 0912345678";
		String bulk = IntStream.range(0, 10)
			.mapToObj(i -> "khach" + i + "@gmail.com")
			.collect(Collectors.joining(", ", "Danh sách khách: ", "."));

		assertThat(this.check.check(few).found()).isFalse();
		Result result = this.check.check(bulk);
		assertThat(result.count(Kind.PERSONAL_DATA)).isEqualTo(10);
		assertThat(result.maskedText()).doesNotContain("khach3@gmail.com").contains("kh••••@gmail.com");
	}

	@Test
	void onlyTheFirstFourThousandCharactersAreChecked() {
		Planted token = PlantedSecrets.all().get("github-pat");

		Result result = this.check.check("a".repeat(SensitiveText.MAX_LENGTH) + " " + token.value());

		assertThat(result.found()).isFalse();
		assertThat(result.maskedText()).hasSize(SensitiveText.MAX_LENGTH);
	}

	@Test
	void plainTalkPassesUnchanged() {
		Result result = this.check.check("Giai đoạn 1 xong rồi chị, tối nay chị thử giúp em nhé.");

		assertThat(result.found()).isFalse();
		assertThat(result.maskedText()).isEqualTo("Giai đoạn 1 xong rồi chị, tối nay chị thử giúp em nhé.");
	}

}
