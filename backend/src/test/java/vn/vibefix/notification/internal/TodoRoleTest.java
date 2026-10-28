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
			.containsEntry(Audience.SPLIT_PARTY, null)
			.containsEntry(Audience.SUBJECT, null)
			.containsEntry(Audience.ADMIN, null)
			.hasSize(Audience.values().length);
	}

}
