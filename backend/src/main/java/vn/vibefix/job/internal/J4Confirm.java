package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.ContactVisibility;
import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J4 (FR-15): the selected Mentor signed the NDA, which {@code matching} recorded and vouches for, and
 * the Job enters Agreement. The facts carry the signature's version and time; {@code matching} closes
 * the held Applications and {@code chat} opens the Job Chat. In the same transaction the Mentor, and
 * never a Crew member, may read the Customer's contact details for this Job (FR-3, AD-3), the
 * Agreement deadline is set 14 days on (FR-40), and the draft starts with the Mentor's to-do (FR-16).
 */
@Component
class J4Confirm implements JobLifecycle.Step {

	private final ContactVisibility contacts;

	private final Agreements agreements;

	private final CrewService crews;

	private final JdbcClient jdbc;

	J4Confirm(ContactVisibility contacts, Agreements agreements, CrewService crews, JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.contacts = contacts;
		this.agreements = agreements;
		this.crews = crews;
	}

	@Override
	public Transition transition() {
		return Transition.J4;
	}

	/** @throws ApiException {@code NOT_FOUND} for anyone but the Job's Mentor */
	@Override
	public void check(Applying applying) {
		if (!applying.actor().is(applying.job().mentorId())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.fact("mentorId", String.valueOf(applying.job().mentorId()));
		applying.fact("ndaVersion", (String) applying.input("ndaVersion"));
		applying.fact("signedAt", ((Instant) applying.input("signedAt")).toString());
		applying.set("agreement_due_at", applying.now().plus(StallDeadlines.period(this.jdbc, applying.job().id(),
				JobState.AGREEMENT)));
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		this.contacts.grant(job.customerId(), Objects.requireNonNull(job.mentorId()), job.id());
		Instant due = applying.now().plus(StallDeadlines.period(this.jdbc, job.id(), JobState.AGREEMENT));
		this.agreements.start(job, due);
		this.crews.start(job, due);
	}

}
