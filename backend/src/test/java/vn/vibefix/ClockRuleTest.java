package vn.vibefix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import clockfixtures.ReadsSystemTime;

/**
 * Conventions, Time: business time comes only from the injected {@link java.time.Clock}. Production
 * code may not call the {@code now()} methods of {@code java.time} that read the system clock, and
 * only the clock configuration may create a system clock.
 */
class ClockRuleTest {

	static final String CLOCK_CONFIGURATION = "vn.vibefix.shared.internal.ClockConfiguration";

	private static final Set<String> TIME_TYPES = Set.of("java.time.Instant", "java.time.LocalDateTime",
			"java.time.LocalDate", "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
			"java.time.ZonedDateTime", "java.time.Year", "java.time.YearMonth", "java.time.MonthDay");

	private static final Set<String> SYSTEM_CLOCKS = Set.of("systemUTC", "systemDefaultZone", "system");

	static final DescribedPredicate<JavaMethodCall> NOW_WITHOUT_CLOCK = DescribedPredicate.describe(
			"a java.time now() that reads the system clock", call -> {
				if (!call.getName().equals("now") || !TIME_TYPES.contains(call.getTargetOwner().getName())) {
					return false;
				}
				List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
				return parameters.isEmpty() || parameters.getFirst().isEquivalentTo(java.time.ZoneId.class);
			});

	static final DescribedPredicate<JavaMethodCall> SYSTEM_CLOCK = DescribedPredicate.describe(
			"a system Clock factory", call -> call.getTargetOwner().isEquivalentTo(java.time.Clock.class)
					&& SYSTEM_CLOCKS.contains(call.getName()));

	static final ArchRule NO_NOW = noClasses().should()
		.callMethodWhere(NOW_WITHOUT_CLOCK)
		.because("business time comes only from the injected Clock (Conventions, Time)");

	static final ArchRule NO_SYSTEM_CLOCK = noClasses().that()
		.doNotHaveFullyQualifiedName(CLOCK_CONFIGURATION)
		.should()
		.callMethodWhere(SYSTEM_CLOCK)
		.because("only ClockConfiguration creates the system clock (Conventions, Time)");

	@Test
	void productionCodeReadsTimeOnlyFromTheInjectedClock() {
		JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("vn.vibefix");

		NO_NOW.check(production);
		NO_SYSTEM_CLOCK.check(production);
	}

	@Test
	void rulesFireOnCodeThatReadsTheSystemClock() {
		JavaClasses fixture = new ClassFileImporter().importClasses(ReadsSystemTime.class);

		assertThatThrownBy(() -> NO_NOW.check(fixture)).isInstanceOf(AssertionError.class)
			.hasMessageContaining("Instant.now()")
			.hasMessageContaining("LocalDateTime.now()")
			.hasMessageContaining("ZonedDateTime.now(java.time.ZoneId)")
			.hasMessageNotContaining("LocalDate.now(java.time.Clock)");
		assertThatThrownBy(() -> NO_SYSTEM_CLOCK.check(fixture)).isInstanceOf(AssertionError.class)
			.hasMessageContaining("Clock.systemUTC()");
	}

}
