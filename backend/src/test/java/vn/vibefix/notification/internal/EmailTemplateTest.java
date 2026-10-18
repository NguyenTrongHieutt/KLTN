package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import vn.vibefix.notification.internal.EmailTemplate.RenderedEmail;

/** AD-21, AD-27: templates escape every value, add no header or line, and need every value. */
class EmailTemplateTest {

	private static final EmailTemplate GREETING = EmailTemplate.parse("test.greeting", """
			subject: Chào {name}
			link: /ho-so
			---
			Xin chào {name},

			Kho {repo} đã sẵn sàng & đang chờ bạn.
			""");

	@Test
	void everyValueIsEscapedInTheHtmlPart() {
		RenderedEmail email = GREETING.render(
				Map.of("name", "<script>alert(1)</script>", "repo", "\"><img src=x onerror=alert(1)>"),
				"https://vibefix.vn/");

		assertThat(email.html()).doesNotContain("<script>")
			.doesNotContain("<img")
			.contains("&lt;script&gt;alert(1)&lt;/script&gt;")
			.contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;")
			.contains("đã sẵn sàng &amp; đang chờ bạn")
			.contains("<a href=\"https://vibefix.vn/ho-so\"");
		assertThat(email.text()).contains("Mở vibefix: https://vibefix.vn/ho-so").contains(EmailTemplate.FOOTER);
	}

	@Test
	void aValueAddsNoHeaderNoLineAndNoDirectionOverride() {
		RenderedEmail email = GREETING.render(
				Map.of("name", "Lan\r\nBcc: attacker@example.com", "repo", "abc‮gpj.exe\u0007"), "https://vibefix.vn");

		assertThat(email.subject()).isEqualTo("Chào LanBcc: attacker@example.com").doesNotContain("\n").doesNotContain("\r");
		assertThat(email.text()).contains("Kho abcgpj.exe đã").doesNotContain("‮").doesNotContain("\u0007");
	}

	@Test
	void everyPlaceholderNeedsAValue() {
		assertThat(GREETING.placeholders()).containsExactlyInAnyOrder("name", "repo");
		assertThatThrownBy(() -> GREETING.render(Map.of("name", "Lan"), "https://vibefix.vn"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("{repo}");
	}

	@Test
	void aTemplateIsCheckedWhenItIsRead() {
		assertThatThrownBy(() -> EmailTemplate.parse("t.a", "subject: x\nBody without separator"))
			.hasMessageContaining("---");
		assertThatThrownBy(() -> EmailTemplate.parse("t.b", "---\nbody")).hasMessageContaining("no subject");
		assertThatThrownBy(() -> EmailTemplate.parse("t.c", "subject: x\nbcc: y\n---\nbody"))
			.hasMessageContaining("unknown");
		assertThatThrownBy(() -> EmailTemplate.parse("t.d", "subject: x\nlink: https://evil.example\n---\nbody"))
			.hasMessageContaining("not a platform path");
		assertThatThrownBy(() -> EmailTemplate.parse("t.e", "subject: x\n---\n")).hasMessageContaining("no body");
	}

	@Test
	void paragraphsBecomeParagraphsAndLinesBreaks() {
		RenderedEmail email = EmailTemplate.parse("t.f", "subject: S\n---\nMột\nhai\n\nBa").render(Map.of(), "x");

		assertThat(email.html()).contains("<p>Một<br>\nhai</p>").contains("<p>Ba</p>").contains("lang=\"vi\"");
	}

}
