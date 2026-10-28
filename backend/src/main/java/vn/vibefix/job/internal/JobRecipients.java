package vn.vibefix.job.internal;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.RecipientLookup;

/**
 * {@code job}'s answer to {@code notification} (AD-3 recipient lookup, AD-20): who the Customer of a
 * Job is, who its Mentor is from J3 on, the Crew classes (AD-41): {@code CREW_ASSIGNED} for a row
 * awaiting confirmation, {@code CREW_ACTIVE} for an active row, {@code CREW_EVER_ACTIVE} for any row
 * that was ever active, and {@code SPLIT_PARTY} for a party of the Crew Terms version pinned to the
 * Milestone (until Story 22.1 brings each Milestone its own split).
 */
@Component
class JobRecipients implements RecipientLookup {

	private final JdbcClient jdbc;

	JobRecipients(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<Audience> audiences() {
		return Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_ASSIGNED, Audience.CREW_ACTIVE,
				Audience.CREW_EVER_ACTIVE, Audience.SPLIT_PARTY);
	}

	@Override
	public boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId) {
		return holds(accountId, audience, jobId, null);
	}

	@Override
	public boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId, @Nullable UUID milestoneId) {
		if (jobId == null) {
			return false;
		}
		if (audience == Audience.SPLIT_PARTY) {
			return milestoneId != null && this.jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM job.milestone m
					    JOIN job.crew_terms_share s ON s.job_id = m.job_id AND s.version_no = m.crew_terms_version_no
					    WHERE m.id = :milestone AND m.job_id = :job AND s.party_account_id = :account)
					""").param("milestone", milestoneId).param("job", jobId).param("account", accountId)
				.query(Boolean.class)
				.single();
		}
		String crew = switch (audience) {
			case CREW_ASSIGNED -> "status = 'ASSIGNED'";
			case CREW_ACTIVE -> "status = 'ACTIVE'";
			case CREW_EVER_ACTIVE -> "activated_at IS NOT NULL";
			default -> null;
		};
		if (crew != null) {
			return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.crew_row WHERE job_id = :job AND member_account_id = :account AND "
					+ crew + ")").param("job", jobId).param("account", accountId).query(Boolean.class).single();
		}
		if (audience != Audience.CUSTOMER && audience != Audience.MENTOR) {
			return false;
		}
		String column = audience == Audience.CUSTOMER ? "customer_account_id" : "mentor_account_id";
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.job WHERE id = :job AND " + column + " = :account)")
			.param("job", jobId)
			.param("account", accountId)
			.query(Boolean.class)
			.single();
	}

}
