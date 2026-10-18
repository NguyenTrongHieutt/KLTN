package vn.vibefix.shared.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;

/**
 * {@code GET /api/v1/environment}: which environment this deployment is ({@code dev} or
 * {@code prod}), read from {@code platform.environment} through the runtime role.
 */
@RestController
@RequestMapping("/api/v1/environment")
@Access(Gate.PUBLIC)
class EnvironmentController {

	private final JdbcClient jdbc;

	EnvironmentController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping
	EnvironmentResponse environment() {
		String name = this.jdbc.sql("SELECT name FROM platform.environment").query(String.class).single();
		return new EnvironmentResponse(name);
	}

	/** @param name the environment code, {@code dev} or {@code prod}; the SPA maps it to Vietnamese */
	record EnvironmentResponse(String name) {
	}

}
