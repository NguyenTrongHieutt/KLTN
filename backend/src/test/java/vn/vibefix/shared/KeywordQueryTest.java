package vn.vibefix.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** AD-36, SG-39: how a keyword becomes a text-search condition. */
class KeywordQueryTest {

	@Test
	void aBlankKeywordIsNoSearch() {
		assertThat(KeywordQuery.of("   ", "v")).isEmpty();
	}

	@Test
	void theLastTermIsAPrefix() {
		KeywordQuery one = KeywordQuery.of("ban", "v").orElseThrow();
		assertThat(one.condition()).isEqualTo("v @@ to_tsquery('platform.vi_search', :keywordLast || ':*')");
		assertThat(one.params()).isEqualTo(Map.of("keywordLast", "ban"));

		KeywordQuery two = KeywordQuery.of("  ban   hang ", "v").orElseThrow();
		assertThat(two.condition()).contains("websearch_to_tsquery('platform.vi_search', :keyword) && to_tsquery");
		assertThat(two.params()).isEqualTo(Map.of("keyword", "ban", "keywordLast", "hang"));
		assertThat(two.rank("v")).startsWith("ts_rank(v, (websearch_to_tsquery");
	}

	@Test
	void theLastTermIsReducedToLettersAndDigits() {
		assertThat(KeywordQuery.of("bán hàng,", "v").orElseThrow().params()).containsEntry("keywordLast", "hàng");
	}

	@Test
	void aQuoteANegationOrAnOperatorEndsWithoutAPrefix() {
		for (String raw : new String[] { "\"bán hàng\"", "react -vue", "react or", "!!!" }) {
			KeywordQuery query = KeywordQuery.of(raw, "v").orElseThrow();
			assertThat(query.condition()).as(raw).isEqualTo("v @@ websearch_to_tsquery('platform.vi_search', :keyword)");
			assertThat(query.params()).as(raw).isEqualTo(Map.of("keyword", raw));
		}
	}

	@Test
	void inputStopsAtOneHundredCharacters() {
		String raw = "a".repeat(150);
		assertThat(KeywordQuery.of(raw, "v").orElseThrow().params().get("keywordLast")).isEqualTo("a".repeat(100));
	}

}
