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
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductDeleted;
import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code product}'s part in exports and erasure (AD-24): the Customer's Products with their
 * acknowledgments, and their Source App bindings (Story 23.5), drafts included. Erasure deletes them as a Product
 * deletion does (Story 16.2), and then the drafts' bindings: every participant's data for
 * each, audited as the System, and {@link ProductDeleted} published, so {@code repo} deletes their platform
 * repositories (FR-21); {@code job} blocks the erasure first while a Job runs.
 * <p>
 * It also answers for the GitHub link (FR-80, R14, AD-10): a live Product keeps its Customer's link.
 */
@Component
@EnableConfigurationProperties(Products.ProductProperties.class)
class ProductPersonalData implements PersonalDataParticipant, GitHubLinkParticipant {

	private final JdbcClient jdbc;

	private final ObjectProvider<PersonalData> personalData;

	private final ApplicationEventPublisher events;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Todos todos;

	ProductPersonalData(JdbcClient jdbc, ObjectProvider<PersonalData> personalData, ApplicationEventPublisher events,
			AuditLog auditLog, IdGenerator ids, Todos todos) {
		this.jdbc = jdbc;
		this.personalData = personalData;
		this.events = events;
		this.auditLog = auditLog;
		this.ids = ids;
		this.todos = todos;
	}

	@Override
	public Set<String> tables() {
		return Set.of("product.product", "product.job_pin", "product.source_app_binding");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		// Story 23.5: each Source App binding, with the installation's account, never the install link's state.
		List<Map<String, Object>> bindings = this.jdbc.sql("""
				SELECT repository_full_name, account_login, account_type, status, created_at, bound_at, ended_at
				FROM product.source_app_binding WHERE customer_account_id = :id ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> binding = new LinkedHashMap<>();
			binding.put("repository", rs.getString("repository_full_name"));
			binding.put("status", rs.getString("status"));
			if (rs.getString("account_login") != null) {
				binding.put("installedOn", rs.getString("account_login"));
				binding.put("accountType", rs.getString("account_type"));
			}
			binding.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
			if (rs.getTimestamp("bound_at") != null) {
				binding.put("boundAt", rs.getTimestamp("bound_at").toInstant().toString());
			}
			if (rs.getTimestamp("ended_at") != null) {
				binding.put("endedAt", rs.getTimestamp("ended_at").toInstant().toString());
			}
			return binding;
		}).list();
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
		if (products.isEmpty() && bindings.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("product", bindings.isEmpty() ? Map.of("products", products)
				: Map.of("products", products, "sourceAppBindings", bindings)));
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
		// The Source App bindings of drafts never registered (Story 23.5).
		this.jdbc.sql("DELETE FROM product.source_app_binding WHERE customer_account_id = :id").param("id", accountId).update();
	}

	/**
	 * The Product row goes, and with it its pins and its Source App binding, so the binding ends (Story 23.5); its
	 * repository ID is free again. Its Source App and workflow to-dos close in the same transaction, since CUS-4 no
	 * longer answers for it (Story 23.5c).
	 */
	@Override
	public void deleteProduct(UUID productId) {
		this.todos.closeAll(SourceStatuses.INSTALL_SOURCE_APP, productId);
		this.todos.closeAll(SourceStatuses.ADD_WORKFLOW, productId);
		this.jdbc.sql("DELETE FROM product.source_app_binding WHERE product_id = :id").param("id", productId).update();
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

}
