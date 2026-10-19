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
 * Job is. The Mentor and Crew classes join once Jobs have them (Stories 3.5 and 4.x).
 */
@Component
class JobRecipients implements RecipientLookup {

	private final JdbcClient jdbc;

	JobRecipients(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<Audience> audiences() {
		return Set.of(Audience.CUSTOMER);
	}

	@Override
	public boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId) {
		if (audience != Audience.CUSTOMER || jobId == null) {
			return false;
		}
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.job WHERE id = :job AND customer_account_id = :account)")
			.param("job", jobId)
			.param("account", accountId)
			.query(Boolean.class)
			.single();
	}

}
