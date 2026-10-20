package vn.vibefix.product.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.product.ProductDirectory;

/** {@link ProductDirectory} over {@link Products}. */
@Component
class JdbcProductDirectory implements ProductDirectory {

	private final Products products;

	private final JdbcClient jdbc;

	JdbcProductDirectory(Products products, JdbcClient jdbc) {
		this.products = products;
		this.jdbc = jdbc;
	}

	@Override
	public Set<UUID> hidden(Collection<UUID> productIds) {
		if (productIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(this.jdbc.sql("SELECT id FROM product.product WHERE id IN (:ids) AND hidden_at IS NOT NULL")
			.param("ids", List.copyOf(productIds))
			.query(UUID.class)
			.list());
	}

	@Override
	public Optional<ProductInfo> find(UUID productId) {
		return this.products.find(productId)
			.map(product -> new ProductInfo(product.id(), product.customerId(), product.name(),
					product.description(), product.tools().stream().map(Enum::name).toList(), product.repositoryFullName()));
	}

}
