package vn.vibefix.product.internal;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.product.internal.SourceAppBindings.Returned;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * The Source App's Setup URL (AD-10, Story 23.5), where GitHub returns after "Cài ứng dụng vibefix trên GitHub ↗":
 * a valid {@code state} is consumed only for the signed-in Customer who minted it, and the answer goes back to CUS-3
 * with a marker, {@code ung-dung=da-cai} (installed), {@code cho-duyet} (waiting for an organization owner,
 * {@code setup_action=request}) or {@code chua-thay} (nothing seen), where the wizard runs "Kiểm tra". It binds
 * nothing: the check binds. Any other return, an expired, used, or another Customer's state, goes back to CUS-3
 * with nothing recorded. A return that carries no {@code state} at all is the plain install page CUS-4 links to for a
 * registered Product (Story 23.5c), so it goes to CUS-2, where the Customer opens the Product and runs "Kiểm tra lại".
 */
@RestController
@Access(Gate.SIGNED_IN)
class SourceAppSetupController {

	/** CUS-3, the registration wizard. */
	static final String WIZARD = "/san-pham/moi";

	/** CUS-2, the Products. */
	static final String PRODUCTS = "/san-pham";

	private final SourceAppBindings bindings;

	private final CurrentCaller callers;

	SourceAppSetupController(SourceAppBindings bindings, CurrentCaller callers) {
		this.bindings = bindings;
		this.callers = callers;
	}

	@GetMapping("/api/v1/github/source-app/setup")
	ResponseEntity<Void> setup(@RequestParam(name = "installation_id", required = false) @Nullable Long installationId,
			@RequestParam(name = "setup_action", required = false) @Nullable String action,
			@RequestParam(required = false) @Nullable String state) {
		if (state == null) {
			return back(PRODUCTS);
		}
		Caller caller = this.callers.get();
		Returned returned = state.isBlank() || state.length() > 200 || !caller.isUser() ? null
				: this.bindings.consume(state, caller.requireAccountId(), installationId, action).orElse(null);
		if (returned == null) {
			return back(WIZARD);
		}
		String marker = switch (returned) {
			case INSTALLED -> "da-cai";
			case REQUESTED -> "cho-duyet";
			case NOT_SEEN -> "chua-thay";
		};
		return back(WIZARD + "?ung-dung=" + marker);
	}

	private static ResponseEntity<Void> back(String path) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(path)).build();
	}

}
