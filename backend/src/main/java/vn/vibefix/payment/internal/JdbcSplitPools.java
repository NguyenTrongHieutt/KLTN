package vn.vibefix.payment.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.payment.SplitPools;
import vn.vibefix.shared.CrewSplit;

/** {@link SplitPools} over the Milestone's one {@code RELEASE_POOLED} entry; every part comes from {@link CrewSplit}. */
@Component
class JdbcSplitPools implements SplitPools {

	private final JdbcClient jdbc;

	JdbcSplitPools(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public long pooledVnd(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT amount_vnd FROM payment.ledger_entry WHERE milestone_id = :id AND entry_type = 'RELEASE_POOLED'
				""").param("id", milestoneId).query(Long.class).optional().orElse(0L);
	}

	@Override
	@Transactional(readOnly = true)
	public Parts parts(UUID milestoneId, UUID mentorPartyId, Map<UUID, Integer> percents) {
		long pooled = pooledVnd(milestoneId);
		Map<UUID, Long> parts = CrewSplit.split(pooled, percents, mentorPartyId);
		Map<UUID, Long> amounts = new LinkedHashMap<>();
		percents.keySet().forEach(party -> amounts.put(party, parts.getOrDefault(party, 0L)));
		return new Parts(pooled, amounts);
	}

}
