package vn.vibefix.matching.internal;

import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.PersonalDataParticipantContract;

/** AD-24: a Mentor's Applications are exported for them only and erased with them. */
class MatchingPersonalDataIT extends IntegrationTest implements PersonalDataParticipantContract {

	@Autowired
	private MatchingPersonalData participant;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public PersonalDataParticipant participant() {
		return this.participant;
	}

	@Override
	public String seedSubjectAndOther() {
		insert(SUBJECT, "Cách làm của người được xuất");
		String marker = "Cách làm của người khác 7f3a";
		insert(OTHER, marker);
		return marker;
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM matching.application WHERE mentor_account_id = :id OR customer_account_id = :id
				""").param("id", accountId).query(Long.class).single() > 0;
	}

	private void insert(UUID mentor, String approach) {
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :customer, :mentor, 'SUBMITTED', 800000, 7, :approach, '1.0', :now)
				""")
			.param("id", UUID.randomUUID())
			.param("job", UUID.randomUUID())
			.param("product", UUID.randomUUID())
			.param("customer", UUID.randomUUID())
			.param("mentor", mentor)
			.param("approach", approach)
			.param("now", Timestamp.from(MutableClock.DEFAULT_INSTANT))
			.update();
	}

}
