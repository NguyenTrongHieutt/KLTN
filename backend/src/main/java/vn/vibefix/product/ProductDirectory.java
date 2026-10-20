package vn.vibefix.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Products as other modules read them (AD-3): who owns one, its name, description, AI tools, and repository. */
public interface ProductDirectory {

	/**
	 * A live Product.
	 * @param description what the Product does, in the Customer's words (FR-7), shown on Job Posts (FR-12)
	 * @param tools the AI tools the Customer chose at registration, as {@code LOVABLE}, {@code V0}, …
	 */
	record ProductInfo(UUID id, UUID customerId, String name, String description, List<String> tools,
			String repositoryFullName) {

		public ProductInfo {
			tools = List.copyOf(tools);
		}

	}

	Optional<ProductInfo> find(UUID productId);

	/** Which of these Products an Administrator hid (FR-60). */
	Set<UUID> hidden(Collection<UUID> productIds);

}
