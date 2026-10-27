package vn.vibefix.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.AdminSignIns.Outcome;
import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Kind;

/**
 * PUB-6's sign-in (FR-2, AD-19): the separate Administrator path, never linked from a public page.
 * Only an anonymous caller signs in here; success starts an Administrator session with its idle and
 * absolute limits (SG-10).
 */
@RestController
@RequestMapping("/api/v1/admin")
@Access(Gate.PUBLIC)
class AdminSignInController {

	record AdminSignInRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 200) String password,
			@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
	}

	private final AdminSignIns signIns;

	private final CurrentCaller callers;

	private final SecurityContextRepository contexts;

	private final Clock clock;

	AdminSignInController(AdminSignIns signIns, CurrentCaller callers, SecurityContextRepository contexts,
			Clock clock) {
		this.signIns = signIns;
		this.callers = callers;
		this.contexts = contexts;
		this.clock = clock;
	}

	@PostMapping("/sign-in")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void signIn(@Valid @RequestBody AdminSignInRequest body, HttpServletRequest request, HttpServletResponse response) {
		if (!this.callers.get().isAnonymous()) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		Outcome outcome = this.signIns.signIn(body.email(), body.password(), body.code());
		if (outcome instanceof Outcome.Refused refused) {
			throw refused.refusal();
		}
		Outcome.SignedIn signedIn = (Outcome.SignedIn) outcome;
		HttpSession session = request.getSession(true);
		SignInSession.clear(session);
		request.changeSessionId();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new AccountAuthentication(signedIn.accountId(), Kind.ADMINISTRATOR));
		SecurityContextHolder.setContext(context);
		this.contexts.saveContext(context, request, response);
		AdminSessions.start(request.getSession(), this.clock.instant());
	}

}
