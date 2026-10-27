package vn.vibefix.identity.internal;

import java.time.Clock;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.AdministratorEnrolments.EnrolmentView;
import vn.vibefix.identity.internal.AdministratorEnrolments.Outcome;
import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Kind;

/**
 * The enrolment link of a new Administrator (FR-2, AD-19): it shows the authenticator secret once
 * the link is opened, then sets the password and enrols the app with a code from it, and signs the
 * Administrator in. Only an anonymous caller enrols; an unknown, used, or lapsed link finds nothing.
 */
@RestController
@RequestMapping("/api/v1/admin/enrolment/{token}")
@Access(Gate.PUBLIC)
class AdminEnrolmentController {

	record EnrolmentRequest(@NotBlank @Size(min = AdministratorBootstrap.MIN_PASSWORD_LENGTH, max = 200) String password,
			@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
	}

	private final AdministratorEnrolments enrolments;

	private final CurrentCaller callers;

	private final SecurityContextRepository contexts;

	private final Clock clock;

	AdminEnrolmentController(AdministratorEnrolments enrolments, CurrentCaller callers,
			SecurityContextRepository contexts, Clock clock) {
		this.enrolments = enrolments;
		this.callers = callers;
		this.contexts = contexts;
		this.clock = clock;
	}

	@GetMapping
	EnrolmentView enrolment(@PathVariable String token) {
		requireAnonymous();
		return this.enrolments.view(token).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void enrol(@PathVariable String token, @Valid @RequestBody EnrolmentRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		requireAnonymous();
		char[] password = body.password().toCharArray();
		Outcome outcome;
		try {
			outcome = this.enrolments.complete(token, password, body.code());
		}
		finally {
			Arrays.fill(password, '\0');
		}
		if (outcome instanceof Outcome.Refused refused) {
			throw refused.refusal();
		}
		HttpSession session = request.getSession(true);
		SignInSession.clear(session);
		request.changeSessionId();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new AccountAuthentication(((Outcome.Done) outcome).accountId(), Kind.ADMINISTRATOR));
		SecurityContextHolder.setContext(context);
		this.contexts.saveContext(context, request, response);
		AdminSessions.start(request.getSession(), this.clock.instant());
	}

	private void requireAnonymous() {
		if (!this.callers.get().isAnonymous()) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
	}

}
