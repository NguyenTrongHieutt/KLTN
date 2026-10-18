package vn.vibefix.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** AD-23: the one list shape, {@code {items, page, size, total}}. */
class PageResponseTest {

	@Test
	void copiesThePageMetadata() {
		PageResponse<String> response = PageResponse
			.of(new PageImpl<>(List.of("c", "d"), PageRequest.of(1, 2), 5));

		assertThat(response).isEqualTo(new PageResponse<>(List.of("c", "d"), 1, 2, 5));
	}

	@Test
	void mapsTheItems() {
		PageResponse<Integer> response = PageResponse
			.of(new PageImpl<>(List.of("a", "bb"), PageRequest.of(0, 10), 2), String::length);

		assertThat(response.items()).containsExactly(1, 2);
		assertThat(response.total()).isEqualTo(2);
	}

	@Test
	void itemsAreAnImmutableCopy() {
		List<String> items = new ArrayList<>(List.of("a"));
		PageResponse<String> response = new PageResponse<>(items, 0, 10, 1);
		items.add("b");

		assertThat(response.items()).containsExactly("a");
		assertThatThrownBy(() -> response.items().add("c")).isInstanceOf(UnsupportedOperationException.class);
	}

}
