package authzfixtures;

import java.util.Map;
import java.util.Optional;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.authz.fixtures.RoleCallers;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;

/**
 * Seeds {@code {noteId}} and realizes the note's {@link MatrixCaller#AUTHOR}, plus a stand-in
 * {@link MatrixCaller#CREW_ACTIVE} that may see Work Item notes, for {@code AuthorizationHarnessIT}
 * only.
 */
public class NoteFixture implements MatrixFixture {

	@Override
	public void seed(MatrixWorld world) {
		world.put("noteId", HarnessController.NOTE_ID);
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		return switch (caller) {
			case AUTHOR -> Optional.of(RoleCallers.as(Caller.account(HarnessController.AUTHOR_ID, Role.STUDENT)));
			case CREW_ACTIVE -> Optional.of(RoleCallers.as(Role.STUDENT));
			default -> Optional.empty();
		};
	}

}
