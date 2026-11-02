package vn.vibefix.job.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.job.MilestoneSplitFinalized.Finality;
import vn.vibefix.job.internal.SplitStore.Party;
import vn.vibefix.job.internal.SplitStore.Split;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;

/**
 * What a split tells its people (UX-DR112, AD-20): the to-dos and notices of the Mentor and the parties, leavers
 * included, each with the Milestone so the audience classes hold after the Job's row went. The Customer is never told.
 * A party anonymized by an erasure has nobody to reach.
 */
@Component
class SplitNotices {

	static final String DRAFT_TODO = "job.draft_split";

	static final String CONFIRM_TODO = "job.confirm_split";

	static final String DRAFT_DUE = "job.split_draft_due";

	static final String PENDING = "job.split_pending";

	static final String DRAFT_REMINDER = "job.split_draft_reminder";

	static final String PROPOSED = "job.split_proposed";

	static final String DEFAULTED = "job.split_defaulted";

	static final String EDITED = "job.split_edited";

	static final String CONFIRMATION_REMINDER = "job.split_confirmation_reminder";

	static final String PARTY_CONFIRMED = "job.split_party_confirmed";

	static final String FINAL = "job.split_final";

	private final Notifications notifications;

	private final Todos todos;

	private final PersonDirectory people;

	SplitNotices(Notifications notifications, Todos todos, PersonDirectory people) {
		this.notifications = notifications;
		this.todos = todos;
		this.people = people;
	}

	/** A release with Crew parties: the Mentor's to-do and {@value #DRAFT_DUE}, and each party's {@value #PENDING}. */
	void released(Split split, String title, List<Party> parties) {
		String date = ClosureProposals.dayMonth(split.draftDueAt());
		Map<String, String> params = Map.of("n", String.valueOf(split.number()), "job", title, "date", date);
		send(DRAFT_DUE, split, split.id(), mentor(split), params);
		send(PENDING, split, split.id(), members(parties), params);
		UUID mentor = split.mentorId();
		if (mentor != null) {
			this.todos.open(new TodoRequest(DRAFT_TODO, mentor, Audience.MENTOR, split.id(),
					Map.of("n", String.valueOf(split.number()), "job", title), link(split), split.draftDueAt(), split.jobId(), null,
					split.milestoneId()));
		}
	}

	/** A version went out for confirmation: every party's to-do starts over, so nobody keeps an old deadline. */
	void askToConfirm(Split split, String title, List<Party> parties, Instant due) {
		this.todos.closeAll(DRAFT_TODO, split.id());
		this.todos.closeAll(CONFIRM_TODO, split.id());
		for (Recipient member : members(parties)) {
			this.todos.open(new TodoRequest(CONFIRM_TODO, member.accountId(), member.audience(), split.id(),
					Map.of("n", String.valueOf(split.number()), "job", title), link(split), due, split.jobId(), null,
					split.milestoneId()));
		}
	}

	/** The Mentor's first draft or an edit reached each party. */
	void sent(String type, Split split, int versionNo, String title, List<Party> parties, Instant due) {
		send(type, split, cause(split, type + ":" + versionNo), members(parties),
				Map.of("n", String.valueOf(split.number()), "job", title, "date", ClosureProposals.dayMonth(due)));
	}

	/** The default version reached the Mentor and each party. */
	void defaulted(Split split, int versionNo, String title, List<Party> parties, Instant due) {
		send(DEFAULTED, split, cause(split, DEFAULTED + ":" + versionNo), everyone(split, parties),
				Map.of("n", String.valueOf(split.number()), "job", title, "date", ClosureProposals.dayMonth(due)));
	}

	void draftReminder(Split split, UUID deadlineId) {
		send(DRAFT_REMINDER, split, deadlineId, mentor(split),
				Map.of("n", String.valueOf(split.number()), "date", ClosureProposals.dayMonth(split.draftDueAt())));
	}

	/** The reminder of a confirmation, to those who have not confirmed. */
	void confirmationReminder(Split split, UUID deadlineId, List<Party> waiting) {
		send(CONFIRMATION_REMINDER, split, deadlineId, members(waiting), Map.of("n", String.valueOf(split.number())));
	}

	/** One party confirmed: their to-do closes and the Mentor is told. */
	void confirmed(Split split, int versionNo, Party party) {
		UUID account = party.accountId();
		if (account != null) {
			this.todos.close(CONFIRM_TODO, account, split.id());
		}
		send(PARTY_CONFIRMED, split, cause(split, PARTY_CONFIRMED + ":" + versionNo + ":" + party.id()), mentor(split),
				Map.of("name", name(account), "n", String.valueOf(split.number())));
	}

	/** A split became final, by anything but the Mentor being alone: its to-dos close, and everyone hears how. */
	void finalized(Split split, String title, List<Party> parties, Finality finality) {
		this.todos.closeAll(DRAFT_TODO, split.id());
		this.todos.closeAll(CONFIRM_TODO, split.id());
		send(FINAL, split, split.id(), everyone(split, parties),
				Map.of("n", String.valueOf(split.number()), "job", title, "how", how(finality)));
	}

	/** The words of {@code job.split_final}'s "{how}". */
	static String how(Finality finality) {
		return switch (finality) {
			case CONFIRMED -> "mọi người đã xác nhận";
			case DEADLINE -> "hết hạn xác nhận";
			case DECISION -> "quản trị viên đã quyết định";
			case MENTOR_ONLY -> "chỉ có người hướng dẫn";
		};
	}

	/** The link of every notice and to-do of a split: JOB-10 at its Milestone. */
	static String link(Split split) {
		return "/cong-viec/" + split.jobId() + "/phan-chia/" + split.milestoneId();
	}

	private void send(String type, Split split, UUID cause, List<Recipient> recipients, Map<String, String> params) {
		if (recipients.isEmpty()) {
			return;
		}
		this.notifications.send(new NotificationRequest(type, cause, recipients, params, link(split), split.jobId(), null,
				split.milestoneId()));
	}

	private static UUID cause(Split split, String what) {
		return UUID.nameUUIDFromBytes((split.id() + ":" + what).getBytes(StandardCharsets.UTF_8));
	}

	private static List<Recipient> mentor(Split split) {
		UUID mentor = split.mentorId();
		return mentor == null ? List.of() : List.of(new Recipient(mentor, Audience.MENTOR));
	}

	private static List<Recipient> members(List<Party> parties) {
		return parties.stream()
			.filter(party -> !party.mentor() && party.accountId() != null)
			.map(party -> new Recipient(Objects.requireNonNull(party.accountId()), Audience.SPLIT_PARTY))
			.toList();
	}

	private static List<Recipient> everyone(Split split, List<Party> parties) {
		List<Recipient> everyone = new ArrayList<>(mentor(split));
		everyone.addAll(members(parties));
		return everyone;
	}

	/** What the Mentor's notice calls the party. */
	private String name(@Nullable UUID account) {
		String name = account == null ? "" : this.people.cards(List.of(account)).stream().findFirst()
			.map(PersonDirectory.PersonCard::shownName)
			.orElse("");
		return name.isBlank() ? "Một thành viên" : name;
	}

}
