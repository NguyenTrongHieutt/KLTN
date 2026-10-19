package vn.vibefix.scanning.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code scanning}'s part in exports, erasure, and Product deletion (AD-24): the Customer's scan runs
 * and the Findings they opened, which hold masked previews only. Erasure and Product deletion delete
 * them; a Finding goes with the run that opened it.
 */
@Component
class ScanningPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	ScanningPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("scanning.scan_run", "scanning.finding");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> scans = this.jdbc.sql("""
				SELECT id, product_name, repository_full_name, purpose, status, requested_at, finished_at, found_count
				FROM scanning.scan_run WHERE customer_account_id = :id ORDER BY requested_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> scan = new LinkedHashMap<>();
			scan.put("product", rs.getString("product_name"));
			scan.put("sourceRepository", rs.getString("repository_full_name"));
			scan.put("purpose", rs.getString("purpose"));
			scan.put("status", rs.getString("status"));
			scan.put("requestedAt", rs.getTimestamp("requested_at").toInstant().toString());
			if (rs.getTimestamp("finished_at") != null) {
				scan.put("finishedAt", rs.getTimestamp("finished_at").toInstant().toString());
			}
			scan.put("findings", this.jdbc.sql("""
					SELECT category, rule_type, file_path, commit_sha, masked_preview, status, created_at
					FROM scanning.finding WHERE scan_run_id = :run ORDER BY created_at, id
					""").param("run", rs.getObject("id", UUID.class)).query((f, m) -> {
				Map<String, Object> finding = new LinkedHashMap<>();
				finding.put("category", f.getString("category"));
				finding.put("type", f.getString("rule_type"));
				finding.put("file", f.getString("file_path"));
				finding.put("commit", f.getString("commit_sha"));
				finding.put("maskedPreview", f.getString("masked_preview"));
				finding.put("status", f.getString("status"));
				finding.put("foundAt", f.getTimestamp("created_at").toInstant().toString());
				return finding;
			}).list());
			return scan;
		}).list();
		return scans.isEmpty() ? Optional.empty()
				: Optional.of(new PersonalDataSection("scanning", Map.of("scans", scans)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM scanning.scan_run WHERE customer_account_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM scanning.finding WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM scanning.scan_run WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
