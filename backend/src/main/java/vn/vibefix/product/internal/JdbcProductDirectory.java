package vn.vibefix.product.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.product.ProductDirectory;

/** {@link ProductDirectory} over {@link Products}. */
@Component
class JdbcProductDirectory implements ProductDirectory {

	private final Products products;

	JdbcProductDirectory(Products products) {
		this.products = products;
	}

	@Override
	public Optional<ProductInfo> find(UUID productId) {
		return this.products.find(productId)
			.map(product -> new ProductInfo(product.id(), product.customerId(), product.name(),
					product.tools().stream().map(Enum::name).toList(), product.repositoryFullName()));
	}

}
