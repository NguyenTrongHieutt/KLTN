package vn.vibefix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import adminreadfixtures.GrantsFromTheWrongModule;
import vn.vibefix.shared.AdminReadGrants;

/**
 * AD-20, AD-3: only the owning case modules, {@code dispute} and {@code admin}, push Administrator
 * read grants down to the modules holding Job content.
 */
class AdminReadGrantCallersTest {

	private static final Set<String> GRANT_METHODS = Set.of("grantAdminRead", "endAdminRead");

	static final DescribedPredicate<JavaAccess<?>> GRANT_CALL = DescribedPredicate.describe(
			"a call of AdminReadGrants.grantAdminRead or endAdminRead",
			access -> GRANT_METHODS.contains(access.getTarget().getName())
					&& access.getTarget().getOwner().isAssignableTo(AdminReadGrants.class));

	static final ArchRule ONLY_CASE_MODULES_GRANT = noClasses().that()
		.resideOutsideOfPackages("vn.vibefix.dispute..", "vn.vibefix.admin..")
		.should()
		.accessTargetWhere(GRANT_CALL)
		.allowEmptyShould(true)
		.because("only the owning case module pushes a read grant down (AD-20, AD-3)");

	@Test
	void onlyDisputeAndAdminCallTheGrantContract() {
		JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("vn.vibefix");

		ONLY_CASE_MODULES_GRANT.check(production);
	}

	@Test
	void ruleFiresOnACallFromAnotherModule() {
		JavaClasses fixture = new ClassFileImporter().importClasses(GrantsFromTheWrongModule.class);

		assertThatThrownBy(() -> ONLY_CASE_MODULES_GRANT.check(fixture)).isInstanceOf(AssertionError.class)
			.hasMessageContaining("AdminReadGrants.grantAdminRead")
			.hasMessageContaining("AdminReadGrants.endAdminRead");
	}

}
