package vn.vibefix.product.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code product}'s part in exports and erasure (AD-24): the Customer's Products with their
 * acknowledgments. Erasure deletes them; {@code job} blocks it first while a Job runs (Epic 3).
 */
@Component
@EnableConfigurationProperties(Products.ProductProperties.class)
class ProductPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	ProductPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("product.product");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> products = this.jdbc.sql("""
				SELECT name, description, demo_url, array_to_string(ai_tools, ',') AS tools, repository_full_name,
				       copy_permission_version, acknowledged_at, created_at, deleted_at
				FROM product.product WHERE customer_account_id = :id ORDER BY created_at
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> product = new LinkedHashMap<>();
			product.put("name", rs.getString("name"));
			product.put("description", rs.getString("description"));
			product.put("demoUrl", rs.getString("demo_url"));
			product.put("aiTools", rs.getString("tools"));
			product.put("sourceRepository", rs.getString("repository_full_name"));
			product.put("acknowledgmentsVersion", rs.getString("copy_permission_version"));
			product.put("acknowledgedAt", rs.getTimestamp("acknowledged_at").toInstant().toString());
			product.put("registeredAt", rs.getTimestamp("created_at").toInstant().toString());
			if (rs.getTimestamp("deleted_at") != null) {
				product.put("deletedAt", rs.getTimestamp("deleted_at").toInstant().toString());
			}
			return product;
		}).list();
		return products.isEmpty() ? Optional.empty()
				: Optional.of(new PersonalDataSection("product", Map.of("products", products)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM product.product WHERE customer_account_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
