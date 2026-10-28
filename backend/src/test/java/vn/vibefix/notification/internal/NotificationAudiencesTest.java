package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationType;
import vn.vibefix.notification.RecipientLookup;
import vn.vibefix.notification.TodoType;

/**
 * The audience classes of the registry (AD-20, UX-DR112, Story 20.5): the post-Job types reach whoever was ever
 * active on the Crew, and the Mentor where the Mentor receives them; split notices reach the Milestone's split
 * parties only; no type a Customer reads names the Crew beyond their GitHub usernames (R15); and every send
 * refuses a recipient outside the type's classes, or a split party without the Milestone.
 */
class NotificationAudiencesTest {

	/**
	 * The post-Job types: data deletion and the Revocation Checklist (J8 or J10), the Final Merge's pending notice,
	 * reminder and completion and the ZIP Fallback (Closing, after J8), the Rating Window, the Ratings, private
	 * feedback and the Portfolio (J9), and the Team Issue updates, which a Rating issue also sends after the Job.
	 * Each was checked by its senders (review iteration 4): {@code repo.access_revoked} is not one, since a lock and a
	 * Crew change revoke grants on a running Job too.
	 */
	static final Map<String, Set<Audience>> POST_JOB = Map.ofEntries(
			Map.entry("repo.data_deletion_confirmation_pending", Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE)),
			Map.entry("repo.revocation_checklist_pending", Set.of(Audience.CUSTOMER)),
			Map.entry("repo.revocation_checklist_reminder", Set.of(Audience.CUSTOMER)),
			Map.entry("repo.final_merge_authorization_pending", Set.of(Audience.CUSTOMER)),
			Map.entry("repo.final_merge_authorization_reminder", Set.of(Audience.CUSTOMER)),
			Map.entry("repo.final_merge_completed", Set.of(Audience.CUSTOMER, Audience.MENTOR)),
			Map.entry("repo.zip_fallback_ready", Set.of(Audience.CUSTOMER, Audience.MENTOR)),
			Map.entry("reputation.rating_window_opened", Set.of(Audience.CUSTOMER, Audience.MENTOR)),
			Map.entry("reputation.ratings_published", Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_EVER_ACTIVE)),
			Map.entry("reputation.private_feedback_invited", Set.of(Audience.CREW_EVER_ACTIVE)),
			Map.entry("reputation.portfolio_summary_awaiting_approval", Set.of(Audience.CUSTOMER)),
			Map.entry("reputation.portfolio_entry_published", Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE)),
			Map.entry("dispute.team_issue_answered", Set.of(Audience.CREW_EVER_ACTIVE)),
			Map.entry("dispute.team_issue_decided", Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE)));

	/** The post-Job to-dos: data deletion and the Revocation Checklist (J8 or J10), the Ratings and the Portfolio (J9). */
	static final Map<String, Set<Audience>> POST_JOB_TODOS = Map.of("repo.confirm_data_deletion",
			Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE), "repo.revocation_item", Set.of(Audience.CUSTOMER),
			"reputation.rate", Set.of(Audience.CUSTOMER, Audience.MENTOR), "reputation.give_private_feedback",
			Set.of(Audience.CREW_EVER_ACTIVE), "reputation.draft_portfolio_summary", Set.of(Audience.MENTOR),
			"reputation.approve_portfolio_summary", Set.of(Audience.CUSTOMER));

	/** What a parameter's name may not say in a type the Customer reads: the Crew appears by GitHub username only. */
	static final List<String> CREW_IDENTIFYING = List.of("student", "member", "email", "school", "linkedin");

	private static final Set<Audience> CREW_DURING_THE_JOB = Set.of(Audience.CREW_ASSIGNED, Audience.CREW_ACTIVE);

	private final NotificationTypes catalog = new NotificationTypes();

	@Test
	void thePostJobTypesReachEveryoneEverActiveAndTheMentorWhereTheMentorReceivesThem() {
		POST_JOB.forEach((key, audiences) -> assertThat(type(key).audiences()).as(key).isEqualTo(audiences));
		POST_JOB_TODOS.forEach((key, audiences) -> assertThat(todo(key).audiences()).as(key).isEqualTo(audiences));
		for (NotificationType type : this.catalog.notificationTypes()) {
			if (type.audiences().contains(Audience.CREW_EVER_ACTIVE)) {
				assertThat(type.audiences()).as("%s reaches the Crew in one class", type.key())
					.doesNotContainAnyElementsOf(CREW_DURING_THE_JOB);
			}
		}
		assertThat(type("reputation.ratings_published").audiences()).as("the Ratings reach the Crew once the Job ended")
			.contains(Audience.CREW_EVER_ACTIVE)
			.doesNotContainAnyElementsOf(CREW_DURING_THE_JOB);
	}

	@Test
	void splitNoticesReachTheSplitPartiesOnly() {
		assertThat(type("payment.share_held").audiences()).containsExactly(Audience.SPLIT_PARTY);
		// payment.funds_released keeps its built classes until Story 22.1 moves it to MENTOR and SPLIT_PARTY (A-11).
		assertThat(type("payment.funds_released").audiences())
			.containsExactlyInAnyOrder(Audience.MENTOR, Audience.CREW_EVER_ACTIVE);
		for (NotificationType type : this.catalog.notificationTypes()) {
			if (type.audiences().contains(Audience.SPLIT_PARTY)) {
				assertThat(type.audiences()).as("%s reaches the split's parties and, at most, the Mentor", type.key())
					.isSubsetOf(Audience.MENTOR, Audience.SPLIT_PARTY);
			}
		}
		for (TodoType type : this.catalog.todoTypes()) {
			if (type.audiences().contains(Audience.SPLIT_PARTY)) {
				assertThat(type.audiences()).as(type.key()).isSubsetOf(Audience.MENTOR, Audience.SPLIT_PARTY);
			}
		}
		assertThat(Audience.SPLIT_PARTY.jobScoped()).isTrue();
		assertThat(Audience.SPLIT_PARTY.milestoneScoped()).isTrue();
		assertThat(Audience.values()).filteredOn(Audience::milestoneScoped).containsExactly(Audience.SPLIT_PARTY);
	}

	@Test
	void noTypeTheCustomerReadsDeclaresACrewIdentifyingParameter() {
		List<String> found = new ArrayList<>();
		for (NotificationType type : this.catalog.notificationTypes()) {
			if (type.audiences().contains(Audience.CUSTOMER)) {
				identifying(type.key(), type.params(), found);
			}
		}
		for (TodoType type : this.catalog.todoTypes()) {
			if (type.audiences().contains(Audience.CUSTOMER)) {
				identifying(type.key(), type.params(), found);
			}
		}
		assertThat(found).isEmpty();
		// The GitHub username is what the Customer sees of the Crew (R15).
		assertThat(type("job.crew_login_removed").params()).contains("login");
	}

	@Test
	void aRecipientOutsideTheTypesClassesIsRefused() {
		NotificationRegistry registry = new NotificationRegistry(List.of(this.catalog), List.of(new Confirming()));
		UUID account = UUID.randomUUID();
		UUID job = UUID.randomUUID();

		for (Audience audience : Audience.values()) {
			if (audience != Audience.SPLIT_PARTY) {
				assertThatThrownBy(() -> registry.checkRecipient("payment.share_held", type("payment.share_held").audiences(),
						account, audience, job, UUID.randomUUID(), UUID.randomUUID()))
					.as(audience.name())
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("does not reach the audience class " + audience);
			}
		}
		assertThatThrownBy(() -> registry.checkRecipient("job.crew_login_removed",
				type("job.crew_login_removed").audiences(), account, Audience.CREW_ACTIVE, job, null, null))
			.hasMessageContaining("does not reach the audience class CREW_ACTIVE");
	}

	@Test
	void aSplitPartyNeedsTheMilestoneAndALookupThatConfirmsItInIt() {
		Confirming lookup = new Confirming();
		NotificationRegistry registry = new NotificationRegistry(List.of(this.catalog), List.of(lookup));
		Set<Audience> allowed = type("payment.share_held").audiences();
		UUID account = UUID.randomUUID();
		UUID job = UUID.randomUUID();
		UUID milestone = UUID.randomUUID();

		assertThatThrownBy(() -> registry.checkRecipient("payment.share_held", allowed, account, Audience.SPLIT_PARTY, job,
				null, null))
			.hasMessageContaining("SPLIT_PARTY needs the Milestone");
		assertThatThrownBy(() -> registry.checkRecipient("payment.share_held", allowed, account, Audience.SPLIT_PARTY, null,
				null, milestone))
			.hasMessageContaining("SPLIT_PARTY needs the Job");
		assertThatCode(() -> registry.checkRecipient("payment.share_held", allowed, account, Audience.SPLIT_PARTY, job, null,
				milestone))
			.doesNotThrowAnyException();
		assertThat(lookup.milestones).containsExactly(milestone);
		lookup.confirms = false;
		assertThatThrownBy(() -> registry.checkRecipient("payment.share_held", allowed, account, Audience.SPLIT_PARTY, job,
				null, milestone))
			.hasMessageContaining("not confirmed as SPLIT_PARTY");
	}

	private static void identifying(String key, Set<String> params, List<String> found) {
		for (String param : params) {
			String name = param.toLowerCase(Locale.ROOT);
			if (CREW_IDENTIFYING.stream().anyMatch(name::contains)) {
				found.add(key + " declares " + param);
			}
		}
	}

	private NotificationType type(String key) {
		return this.catalog.notificationTypes()
			.stream()
			.filter(type -> type.key().equals(key))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No notification type " + key));
	}

	private TodoType todo(String key) {
		return this.catalog.todoTypes()
			.stream()
			.filter(type -> type.key().equals(key))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No to-do key " + key));
	}

	/** A lookup confirming every Job-scoped class, which records the Milestones it was asked about. */
	private static final class Confirming implements RecipientLookup {

		private final List<UUID> milestones = new ArrayList<>();

		private boolean confirms = true;

		@Override
		public Set<Audience> audiences() {
			return Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_ASSIGNED, Audience.CREW_ACTIVE,
					Audience.CREW_EVER_ACTIVE, Audience.SPLIT_PARTY);
		}

		@Override
		public boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId) {
			return this.confirms;
		}

		@Override
		public boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId, @Nullable UUID milestoneId) {
			if (milestoneId != null) {
				this.milestones.add(milestoneId);
			}
			return this.confirms;
		}

	}

}
