package adminreadfixtures;

import java.time.Instant;
import java.util.UUID;

import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;

/**
 * Deliberately calls the grant contract from outside {@code dispute} and {@code admin}, so that
 * {@code AdminReadGrantCallersTest} can prove its rule fires.
 */
public class GrantsFromTheWrongModule {

	void grant(AdminReadGrants grants, UUID id) {
		grants.grantAdminRead(id, id, id, AdminReadItem.forDispute(), id);
		grants.endAdminRead(id, Instant.EPOCH);
	}

}
