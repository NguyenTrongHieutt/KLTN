package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import vn.vibefix.notification.Audience;

/** AD-27, Story 20.6: the role a to-do stores comes from its recipient's audience class alone. */
class TodoRoleTest {

	@Test
	void eachAudienceClassNamesTheRoleItConfirmsOrNone() {
		Map<Audience, TodoRole> roles = new EnumMap<>(Audience.class);
		Arrays.stream(Audience.values()).forEach(audience -> roles.put(audience, TodoRole.of(audience)));

		assertThat(roles).containsEntry(Audience.CUSTOMER, TodoRole.CUSTOMER)
			.containsEntry(Audience.MENTOR, TodoRole.MENTOR)
			.containsEntry(Audience.CREW_ASSIGNED, TodoRole.CREW)
			.containsEntry(Audience.CREW_ACTIVE, TodoRole.CREW)
			.containsEntry(Audience.CREW_EVER_ACTIVE, TodoRole.CREW)
			.containsEntry(Audience.SPLIT_PARTY, TodoRole.CREW)
			.containsEntry(Audience.SUBJECT, null)
			.containsEntry(Audience.ADMIN, null)
			.hasSize(Audience.values().length);
	}

	/** AD-47 exception 12 (c), Story 25.4: the Customer of a Self-mentored Job holds one role, whichever class opened it. */
	@Test
	void theCombinedRoleReplacesTheCustomersAndTheMentorsAndNoOther() {
		assertThat(TodoRole.of(Audience.CUSTOMER, true)).isEqualTo(TodoRole.CUSTOMER_MENTOR);
		assertThat(TodoRole.of(Audience.MENTOR, true)).isEqualTo(TodoRole.CUSTOMER_MENTOR);
		assertThat(TodoRole.of(Audience.CREW_ACTIVE, true)).isEqualTo(TodoRole.CREW);
		assertThat(TodoRole.of(Audience.SUBJECT, true)).isNull();
		for (Audience audience : Audience.values()) {
			assertThat(TodoRole.of(audience, false)).as(audience.name()).isEqualTo(TodoRole.of(audience));
		}
	}

}
