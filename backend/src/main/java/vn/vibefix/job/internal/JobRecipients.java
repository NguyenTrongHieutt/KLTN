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
 * Job is, and who its Mentor is from J3 on. The Crew classes join with the Crew (Epic 4).
 */
@Component
class JobRecipients implements RecipientLookup {

	private final JdbcClient jdbc;

	JobRecipients(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<Audience> audiences() {
		return Set.of(Audience.CUSTOMER, Audience.MENTOR);
	}

	@Override
	public boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId) {
		if (jobId == null || (audience != Audience.CUSTOMER && audience != Audience.MENTOR)) {
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
