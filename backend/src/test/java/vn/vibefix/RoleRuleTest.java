package vn.vibefix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rolefixtures.ChecksTheShimRole;

/**
 * AD-19, AD-43, SG-56 (Story 20.3): an account holds no role. A person's rights come from their role in each Job
 * ({@code job.roleOf}) and from their capabilities, so no production class may be named {@code Role} in
 * {@code vn.vibefix.shared} (the deleted account role) or in {@code vn.vibefix.identity.internal} (the package of
 * the test-only shim {@code IdentityTestSupport} seeds accounts with), and none may depend on one.
 */
class RoleRuleTest {

	static final String SHARED_ROLE = "vn.vibefix.shared.Role";

	static final String SHIM_ROLE = "vn.vibefix.identity.internal.Role";

	static final DescribedPredicate<JavaClass> ACCOUNT_ROLE = DescribedPredicate.describe(
			"a class named Role in vn.vibefix.shared or vn.vibefix.identity.internal",
			javaClass -> Set.of(SHARED_ROLE, SHIM_ROLE).contains(javaClass.getName()));

	static final ArchRule NO_ACCOUNT_ROLE = noClasses().should()
		.haveFullyQualifiedName(SHARED_ROLE)
		.orShould()
		.haveFullyQualifiedName(SHIM_ROLE)
		.orShould()
		.dependOnClassesThat(ACCOUNT_ROLE)
		.because("an account holds no role: rights come from the role in each Job and the capabilities (AD-19, AD-43)");

	@Test
	void productionCodeHasNoAccountRole() {
		JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("vn.vibefix");

		NO_ACCOUNT_ROLE.check(production);
	}

	@Test
	void ruleFiresOnAProductionClassNamedSharedRoleAndOnADependencyOnIt(@TempDir Path work) throws IOException {
		JavaClasses fixture = new ClassFileImporter().importPath(compile(work, Map.of("vn/vibefix/shared/Role.java", """
				package vn.vibefix.shared;

				public enum Role { CUSTOMER, MENTOR }
				""", "rolefixtures/ChecksTheSharedRole.java", """
				package rolefixtures;

				import vn.vibefix.shared.Role;

				public class ChecksTheSharedRole {
				    boolean customer(Role role) {
				        return role == Role.CUSTOMER;
				    }
				}
				""")));

		assertThatThrownBy(() -> NO_ACCOUNT_ROLE.check(fixture)).isInstanceOf(AssertionError.class)
			.hasMessageContaining("<" + SHARED_ROLE + ">")
			.hasMessageContaining("rolefixtures.ChecksTheSharedRole.customer(" + SHARED_ROLE + ")");
	}

	@Test
	void ruleFiresOnTheShimRoleAndOnADependencyOnIt() {
		JavaClasses fixture = new ClassFileImporter().importClasses(vn.vibefix.identity.internal.Role.class,
				ChecksTheShimRole.class);

		assertThatThrownBy(() -> NO_ACCOUNT_ROLE.check(fixture)).isInstanceOf(AssertionError.class)
			.hasMessageContaining("<" + SHIM_ROLE + ">")
			.hasMessageContaining("rolefixtures.ChecksTheShimRole.mentor(" + SHIM_ROLE + ")");
	}

	/** Compiles the sources (path to text) into {@code work}/classes and returns that directory. */
	private static Path compile(Path work, Map<String, String> sources) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("the build runs on a JDK").isNotNull();
		Path classes = Files.createDirectories(work.resolve("classes"));
		List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path file = work.resolve("src").resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
			arguments.add(file.toString());
		}
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		int status = compiler.run(null, null, errors, arguments.toArray(String[]::new));
		assertThat(status).as("the fixture compiles: %s", errors.toString(StandardCharsets.UTF_8)).isZero();
		return classes;
	}

}
