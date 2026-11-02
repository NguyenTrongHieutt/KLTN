package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.internal.SplitStore.Party;
import vn.vibefix.job.internal.SplitStore.Split;
import vn.vibefix.shared.IdGenerator;

/**
 * The one way a split becomes final (AD-9, AD-15, AD-29): whichever path got here, {@link #makeFinal} writes the status,
 * the final version, when and how, and publishes {@link MilestoneSplitFinalized} in the transaction, which {@code payment}
 * alone executes. Its caller holds the Job row lock. Anything but the Mentor being alone closes the split's to-dos and
 * tells the Mentor and the parties how.
 */
@Component
class SplitFinalization {

	private final JdbcClient jdbc;

	private final SplitStore store;

	private final SplitNotices notices;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	SplitFinalization(JdbcClient jdbc, SplitStore store, SplitNotices notices, AuditLog auditLog,
			ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.store = store;
		this.notices = notices;
		this.auditLog = auditLog;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * @param versionNo the version that becomes final
	 * @param finality how: {@code CONFIRMED}, {@code DEADLINE}, {@code DECISION} or {@code MENTOR_ONLY}
	 */
	void makeFinal(Split split, int versionNo, Finality finality) {
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				UPDATE job.milestone_split SET status = 'FINAL', final_version_no = :version, final_at = :now, finality = :finality
				WHERE id = :id
				""")
			.param("version", versionNo)
			.param("now", Timestamp.from(now))
			.param("finality", finality.name())
			.param("id", split.id())
			.update();
		List<Party> parties = this.store.parties(split.id());
		Map<UUID, Integer> percents = this.store.percents(split.id(), versionNo);
		Map<UUID, Integer> percentages = new LinkedHashMap<>();
		for (Party party : parties) {
			percentages.put(party.accountId(), percents.getOrDefault(party.id(), 0));
		}
		String title = this.store.title(split.jobId()).orElse("");
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action(finality), AuditObject.of("job.milestone_split", split.id()))
			.forJob(split.jobId()));
		this.events.publishEvent(new MilestoneSplitFinalized(this.ids.newId(), split.id(), split.jobId(), split.productId(),
				split.milestoneId(), split.number(), title, Objects.requireNonNull(split.mentorId(), "the split's Mentor"), versionNo,
				percentages, finality));
		if (finality != Finality.MENTOR_ONLY) {
			this.notices.finalized(split, title, parties, finality);
		}
	}

	private static String action(Finality finality) {
		return finality == Finality.MENTOR_ONLY ? "job.split_mentor_only"
				: "job.split_final_" + finality.name().toLowerCase(Locale.ROOT);
	}

}
