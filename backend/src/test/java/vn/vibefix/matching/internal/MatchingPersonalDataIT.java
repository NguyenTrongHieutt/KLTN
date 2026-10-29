package vn.vibefix.matching.internal;

import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.PersonalDataParticipantContract;

/** AD-24: a bidder's bids and their proof (Story 21.2) are exported for them only and erased with them. */
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
		insert(SUBJECT, "Cách làm của người được xuất", "Bằng chứng của người được xuất");
		String marker = "Cách làm của người khác 7f3a";
		insert(OTHER, marker, "Bằng chứng của người khác 7f3a");
		return marker;
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("""
				SELECT (SELECT count(*) FROM matching.application WHERE mentor_account_id = :id OR customer_account_id = :id)
				     + (SELECT count(*) FROM matching.bid_proof WHERE bidder_account_id = :id)
				""").param("id", accountId).query(Long.class).single() > 0;
	}

	private void insert(UUID mentor, String approach, String caption) {
		UUID application = UUID.randomUUID();
		UUID job = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :customer, :mentor, 'SUBMITTED', 800000, 7, :approach, '1.0', :now)
				""")
			.param("id", application)
			.param("job", job)
			.param("product", product)
			.param("customer", UUID.randomUUID())
			.param("mentor", mentor)
			.param("approach", approach)
			.param("now", Timestamp.from(MutableClock.DEFAULT_INSTANT))
			.update();
		this.jdbc.sql("""
				INSERT INTO matching.bid_proof (id, application_id, job_id, product_id, bidder_account_id, position, kind, url,
				    caption, added_at)
				VALUES (:id, :application, :job, :product, :mentor, 1, 'LINK', 'https://a.vn/x', :caption, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("application", application)
			.param("job", job)
			.param("product", product)
			.param("mentor", mentor)
			.param("caption", caption)
			.param("now", Timestamp.from(MutableClock.DEFAULT_INSTANT))
			.update();
	}

}
