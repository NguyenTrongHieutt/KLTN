package authzfixtures;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * a gate, a policy that shows a note to its author only, one that finds only an Active Job and one only a Job
 * with an open bid (so a row's {@code job=crew} and {@code job=post} are seen to name the crew Job and the Open
 * Job Post), and two that leak a canary. Outside
 * {@code vn.vibefix}, so only {@code AuthorizationHarnessIT} imports it and the real matrix never
 * sees it.
 */
@RestController
public class HarnessController {

	public static final UUID NOTE_ID = UUID.fromString("0190f0c3-0000-7000-8000-00000000a001");

	public static final UUID AUTHOR_ID = UUID.fromString("0190f0c3-0000-7000-8000-00000000a002");

	/** A reason of a module's own reason enum, as a policy would use it. */
	enum Reason {

		FIXTURE_NOT_ALLOWED

	}

	private final CurrentCaller callers;

	private final JdbcClient jdbc;

	HarnessController(CurrentCaller callers, JdbcClient jdbc) {
		this.callers = callers;
		this.jdbc = jdbc;
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

	/** Finds the Job only while it is Active: the fixture's Draft is not, its crew Job is. */
	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/jobs/{jobId}/active")
	Map<String, Object> activeJob(@PathVariable UUID jobId) {
		Policies.requireVisible(this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM job.job WHERE id = :id AND state = 'ACTIVE')")
			.param("id", jobId)
			.query(Boolean.class)
			.single());
		return Map.of("jobId", jobId);
	}

	/** Finds the Job only while a bid is open on it: only the fixture's Open Job Post has one. */
	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/jobs/{jobId}/bids")
	Map<String, Object> jobWithBids(@PathVariable UUID jobId) {
		Policies.requireVisible(this.jdbc
			.sql("SELECT EXISTS (SELECT 1 FROM matching.application WHERE job_id = :id AND status = 'SUBMITTED')")
			.param("id", jobId)
			.query(Boolean.class)
			.single());
		return Map.of("jobId", jobId);
	}

	@Access(Gate.SIGNED_IN)
	@GetMapping("/api/v1/fixture/forbidden")
	Map<String, Object> forbidden() {
		Policies.requireAllowed(false, Reason.FIXTURE_NOT_ALLOWED);
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

	/** The {@code CREW_SHARE} amount as a screen shows it in VND, never as its digits. */
	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/leak-amount")
	Map<String, Object> leakAmount() {
		long amount = Long.parseLong(Canaries.load().get("CREW_SHARE").value());
		return Map.of("part", String.format(java.util.Locale.ROOT, "%,d", amount).replace(',', '.') + " ₫");
	}

	/** A second Job's Crew Chat message: the cross-role person is that Job's Customer. */
	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/leak-cross")
	Map<String, Object> leakCross() {
		return Map.of("message", Canaries.load().get("CROSS_CREW_CHAT").value());
	}

	/** A public reputation naming a Crew member, as PUB-4's do. */
	@Access(Gate.PUBLIC)
	@GetMapping("/api/v1/fixture/reputation")
	Map<String, Object> reputation() {
		return Map.of("crew", Canaries.load().get("CREW_NAME").value());
	}

}
