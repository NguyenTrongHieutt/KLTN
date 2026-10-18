package vn.vibefix.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.internal.Profiles.Theme;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/** The account's own preferences: the theme of the Theme toggle (UX-DR5). */
@RestController
@RequestMapping("/api/v1/account")
@Access(Gate.SIGNED_IN)
class AccountController {

	record ThemeRequest(@NotNull Theme theme) {
	}

	private final Profiles profiles;

	private final CurrentCaller caller;

	AccountController(Profiles profiles, CurrentCaller caller) {
		this.profiles = profiles;
		this.caller = caller;
	}

	@PutMapping("/theme")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void theme(@Valid @RequestBody ThemeRequest request) {
		this.profiles.saveTheme(this.caller.get().requireAccountId(), request.theme());
	}

}
