package vn.vibefix.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Products as other modules read them (AD-3): who owns one, its name, AI tools, and repository. */
public interface ProductDirectory {

	/**
	 * A live Product.
	 * @param tools the AI tools the Customer chose at registration, as {@code LOVABLE}, {@code V0}, …
	 */
	record ProductInfo(UUID id, UUID customerId, String name, List<String> tools, String repositoryFullName) {

		public ProductInfo {
			tools = List.copyOf(tools);
		}

	}

	Optional<ProductInfo> find(UUID productId);

}
