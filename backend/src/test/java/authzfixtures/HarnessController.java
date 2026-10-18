package authzfixtures;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.authz.Canaries;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Policies;

/**
 * Endpoints that exist only to prove the authorization harness (AD-20): one per gate, one without
 * a gate, a policy that shows a note to its author only, and two that leak a canary. Outside
 * {@code vn.vibefix}, so only {@code AuthorizationHarnessIT} imports it and the real matrix never
 * sees it.
 */
@RestController
public class HarnessController {

	public static final UUID NOTE_ID = UUID.fromString("0190f0c3-0000-7000-8000-00000000a001");

	public static final UUID AUTHOR_ID = UUID.fromString("0190f0c3-0000-7000-8000-00000000a002");

	/** A reason of a module's own reason enum, as a policy would use it. */
	enum Reason {

		MENTOR_NOT_APPROVED

	}

	private final CurrentCaller callers;

	HarnessController(CurrentCaller callers) {
		this.callers = callers;
	}

	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/public")
	Map<String, Object> publicEndpoint() {
		return Map.of("ok", true);
	}

	@Access(Gate.SIGNED_IN)
	@GetMapping("/api/v1/fixture/signed-in")
	Map<String, Object> signedIn() {
		return Map.of("ok", true);
	}

	@Access(Gate.ADMINISTRATOR)
	@GetMapping("/api/v1/fixture/admin")
	Map<String, Object> admin() {
		return Map.of("ok", true);
	}

	@GetMapping("/api/v1/fixture/undeclared")
	Map<String, Object> undeclared() {
		return Map.of("ok", true);
	}

	/** The policy runs before the note is read, and allows only its author: deny by default. */
	@Access(Gate.SIGNED_IN)
	@GetMapping("/api/v1/fixture/notes/{noteId}")
	Map<String, Object> note(@PathVariable UUID noteId) {
		Caller caller = this.callers.get();
		Policies.requireVisible(NOTE_ID.equals(noteId) && AUTHOR_ID.equals(caller.accountId()));
		return Map.of("noteId", noteId, "text", "a note");
	}

	@Access(Gate.SIGNED_IN)
	@PostMapping("/api/v1/fixture/notes")
	Map<String, Object> echo(@RequestBody Map<String, Object> body) {
		Policies.requireVisible(AUTHOR_ID.equals(this.callers.get().accountId()));
		return Map.of("received", body.get("noteId"));
	}

	@Access(Gate.SIGNED_IN)
	@GetMapping("/api/v1/fixture/forbidden")
	Map<String, Object> forbidden() {
		Policies.requireAllowed(false, Reason.MENTOR_NOT_APPROVED);
		return Map.of("ok", true);
	}

	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/leak-body")
	Map<String, Object> leakBody() {
		return Map.of("note", Canaries.load().get("WORK_ITEM_NOTE").value());
	}

	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/leak-header")
	ResponseEntity<Map<String, Object>> leakHeader() {
		return ResponseEntity.ok().header("X-Fixture", Canaries.load().get("CREW_CHAT").value()).body(Map.of());
	}

}
