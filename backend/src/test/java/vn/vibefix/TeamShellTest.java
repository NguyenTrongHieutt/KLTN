package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * AD-37, SG-63: {@code team} is a migration shell. It keeps {@code package-info.java} and {@code db/migration/team/}
 * at L3, it holds no class, and no module may depend on it, so Teams cannot come back through a story.
 */
class TeamShellTest {

	private static final String SHELL = "team";

	private static final String SHELL_PACKAGE = VibefixApplication.class.getPackageName() + "." + SHELL;

	@Test
	void theShellIsAModuleAtL3ThatDependsOnNothing() throws Exception {
		assertThat(ApplicationModules.of(VibefixApplication.class).getModuleByName(SHELL)).isPresent();
		assertThat(ModuleLayersTest.LAYERS).containsEntry(SHELL, 3);
		assertThat(declarationOf(SHELL_PACKAGE).allowedDependencies()).isEmpty();
	}

	@Test
	void theShellHoldsNoClass() {
		List<String> classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
			.importPackages(SHELL_PACKAGE)
			.stream()
			.map(JavaClass::getName)
			.toList();

		assertThat(classes).containsExactly(SHELL_PACKAGE + ".package-info");
	}

	@Test
	void noModuleDeclaresTheShell() {
		ApplicationModules.of(VibefixApplication.class).forEach(module -> {
			String name = module.getIdentifier().toString();
			if (!SHELL.equals(name)) {
				assertThat(Arrays.stream(declarationOf(module.getBasePackage().getName()).allowedDependencies())
					.map(dependency -> dependency.split("::", 2)[0])
					.toList()).as("the allowed dependencies of %s", name).doesNotContain(SHELL);
			}
		});
	}

	@Test
	void theShellKeepsItsFlywayHistory() throws IOException {
		assertThat(new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/team/V*__team_*.sql"))
			.extracting(resource -> resource.getFilename())
			.contains("V202610192312__team_init.sql", "V202610192320__team_crew_reservation.sql",
					"V202611021500__team_drop_tables.sql");
	}

	private static ApplicationModule declarationOf(String basePackage) {
		try {
			return Class.forName(basePackage + ".package-info").getAnnotation(ApplicationModule.class);
		}
		catch (ClassNotFoundException ex) {
			throw new AssertionError("no package-info in " + basePackage, ex);
		}
	}

}
