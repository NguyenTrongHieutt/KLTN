package vn.vibefix;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * AD-1: fails on any module cycle, access to another module's {@code internal} package, or
 * dependency a module did not declare in {@code allowedDependencies}.
 */
class ModularityTests {

	static final ApplicationModules modules = ApplicationModules.of(VibefixApplication.class);

	@Test
	void verifiesModuleBoundaries() {
		modules.verify();
	}

	@Test
	void writesModuleDocumentation() {
		new Documenter(modules).writeDocumentation();
	}

}
