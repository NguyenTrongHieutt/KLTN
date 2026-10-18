package vn.vibefix.shared;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * The one list shape of the API (AD-23): list endpoints take {@code page}, {@code size} and
 * {@code sort} and return {@code {items, page, size, total}}. {@code page} is zero-based, and
 * {@code size} is capped by {@code spring.data.web.pageable.max-page-size}.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

	public PageResponse {
		items = List.copyOf(items);
	}

	public static <T> PageResponse<T> of(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
	}

	public static <S, T> PageResponse<T> of(Page<S> page, Function<? super S, ? extends T> mapper) {
		return of(page.map(mapper));
	}

}
