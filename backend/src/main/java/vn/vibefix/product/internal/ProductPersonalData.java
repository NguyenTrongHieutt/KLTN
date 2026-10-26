package vn.vibefix.product.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.GitHubLinkBlocker;
import vn.vibefix.identity.GitHubLinkParticipant;
import vn.vibefix.product.ProductDeleted;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code product}'s part in exports and erasure (AD-24): the Customer's Products with their
 * acknowledgments. Erasure deletes them as a Product deletion does (Story 16.2): every participant's data for
 * each, audited as the System, and {@link ProductDeleted} published, so {@code repo} deletes their platform
 * repositories (FR-21); {@code job} blocks the erasure first while a Job runs.
 * <p>
 * It also answers for the GitHub link (FR-80, R14, AD-10): a live Product keeps its Customer's link, and
 * the token its Source Repository is read with.
 */
@Component
@EnableConfigurationProperties(Products.ProductProperties.class)
class ProductPersonalData implements PersonalDataParticipant, GitHubLinkParticipant {

	private final JdbcClient jdbc;

	private final ObjectProvider<PersonalData> personalData;

	private final ApplicationEventPublisher events;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	ProductPersonalData(JdbcClient jdbc, ObjectProvider<PersonalData> personalData, ApplicationEventPublisher events,
			AuditLog auditLog, IdGenerator ids) {
		this.jdbc = jdbc;
		this.personalData = personalData;
		this.events = events;
		this.auditLog = auditLog;
		this.ids = ids;
	}

	@Override
	public Set<String> tables() {
		return Set.of("product.product", "product.repository_replacement", "product.job_pin");
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
		List<UUID> products = this.jdbc.sql("SELECT id FROM product.product WHERE customer_account_id = :id ORDER BY id")
			.param("id", accountId)
			.query(UUID.class)
			.list();
		for (UUID product : products) {
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "product.deleted", AuditObject.of("product.product", product)));
			// Every participant's data for the Product, this participant's row included.
			this.personalData.getObject().deleteProduct(product);
			this.events.publishEvent(new ProductDeleted(this.ids.newId(), product, accountId));
		}
	}

	/** The Product row goes, and with it its replacements and pins; its repository ID is free again. */
	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM product.product WHERE id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

	/** Each live Product of the account: its Source Repository is known by the linked GitHub account. */
	@Override
	public List<GitHubLinkBlocker> githubLinkBlockers(UUID accountId) {
		return this.jdbc.sql("""
				SELECT id, name FROM product.product WHERE customer_account_id = :id AND deleted_at IS NULL
				ORDER BY created_at, id
				""")
			.param("id", accountId)
			.query((rs, n) -> new GitHubLinkBlocker("product", "LIVE_PRODUCT", rs.getObject("id", UUID.class),
					rs.getString("name")))
			.list();
	}

	/** The owner of a live Product: AD-13's re-checks read its Source Repository with the token. */
	@Override
	public boolean keepsRepositoryToken(UUID accountId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM product.product WHERE customer_account_id = :id AND deleted_at IS NULL)
				""").param("id", accountId).query(Boolean.class).single();
	}

}
