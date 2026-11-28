package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.IdGenerator;

/**
 * The members' answers to a proposal of a Self-mentored Job's Customer (AD-47 exceptions 3 and 6, R56, Story 25.7): a
 * cancellation with a Held Milestone, or a Change Request that is not only a later due date. The Customer is the Mentor
 * there, so "the other side" is the Crew: the members who are {@code ACTIVE} and unlocked when the proposal is made
 * each get a row and answer once. A member who leaves or is locked before answering no longer counts; one who joins
 * afterwards has no row and is not asked. Every caller holds the Job row lock.
 */
@Component
class ProposalAnswers {

	enum Kind {
		CANCELLATION, CHANGE_REQUEST
	}

	/**
	 * Where a proposal stands with the members who still count.
	 * @param asked the members asked who still count: {@code ACTIVE} and unlocked now
	 * @param agreed those of them who agreed
	 * @param refused a member refused, whether or not they still count: the proposal is over
	 */
	record Tally(int asked, int agreed, boolean refused) {

		/** Every member who still counts agreed, and at least one does: nobody's agreement is taken by default (R56). */
		boolean agreedByAll() {
			return !this.refused && this.asked > 0 && this.agreed == this.asked;
		}

	}

	/** One member's row. */
	record Answer(UUID memberId, @Nullable String answer) {
	}

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final IdGenerator ids;

	private final Clock clock;

	ProposalAnswers(JdbcClient jdbc, CrewService crews, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Asks the members who count now, one row each.
	 * @return their accounts, in Crew row order
	 */
	List<UUID> ask(UUID jobId, Kind kind, UUID proposalId) {
		List<UUID> members = this.crews.countedActive(jobId).stream().map(CrewService.Row::studentId).toList();
		Timestamp now = Timestamp.from(this.clock.instant());
		for (UUID member : members) {
			this.jdbc.sql("""
					INSERT INTO job.proposal_answer (id, job_id, proposal_kind, proposal_id, member_account_id, created_at)
					VALUES (:id, :job, :kind, :proposal, :member, :now)
					""")
				.param("id", this.ids.newId())
				.param("job", jobId)
				.param("kind", kind.name())
				.param("proposal", proposalId)
				.param("member", member)
				.param("now", now)
				.update();
		}
		return members;
	}

	/** Whether the account was asked about the proposal and still counts. */
	boolean asked(UUID jobId, UUID proposalId, UUID accountId) {
		return counted(jobId).contains(accountId) && of(proposalId).stream().anyMatch(row -> accountId.equals(row.memberId()));
	}

	/** The account's answer, if it gave one. */
	@Nullable String answerOf(UUID proposalId, UUID accountId) {
		return of(proposalId).stream().filter(row -> accountId.equals(row.memberId())).map(Answer::answer).findFirst().orElse(null);
	}

	/**
	 * Records the member's one answer.
	 * @return {@code false} when they had answered already, which changes nothing
	 */
	boolean record(UUID proposalId, UUID accountId, boolean agree) {
		return this.jdbc.sql("""
				UPDATE job.proposal_answer SET answer = :answer, answered_at = :now
				WHERE proposal_id = :proposal AND member_account_id = :member AND answer IS NULL
				""")
			.param("answer", agree ? "AGREED" : "REFUSED")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("proposal", proposalId)
			.param("member", accountId)
			.update() > 0;
	}

	Tally tally(UUID jobId, UUID proposalId) {
		Set<UUID> counted = counted(jobId);
		List<Answer> rows = of(proposalId);
		List<Answer> counting = rows.stream().filter(row -> row.memberId() != null && counted.contains(row.memberId())).toList();
		return new Tally(counting.size(), (int) counting.stream().filter(row -> "AGREED".equals(row.answer())).count(),
				rows.stream().anyMatch(row -> "REFUSED".equals(row.answer())));
	}

	/** The members asked who still count and have not answered. */
	List<UUID> waiting(UUID jobId, UUID proposalId) {
		Set<UUID> counted = counted(jobId);
		return of(proposalId).stream()
			.filter(row -> row.answer() == null && row.memberId() != null && counted.contains(row.memberId()))
			.map(Answer::memberId)
			.toList();
	}

	private List<Answer> of(UUID proposalId) {
		return this.jdbc.sql("""
				SELECT member_account_id, answer FROM job.proposal_answer WHERE proposal_id = :proposal ORDER BY created_at, id
				""")
			.param("proposal", proposalId)
			.query((rs, n) -> new Answer(rs.getObject("member_account_id", UUID.class), rs.getString("answer")))
			.list();
	}

	private Set<UUID> counted(UUID jobId) {
		return this.crews.countedActive(jobId).stream().map(CrewService.Row::studentId).collect(Collectors.toSet());
	}

}
