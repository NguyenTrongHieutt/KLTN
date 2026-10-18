package vn.vibefix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import clockfixtures.ReadsSystemTime;

/**
 * Conventions, Time: business time comes only from the injected {@link java.time.Clock}. Production
 * code may not call or reference the {@code now()} methods of {@code java.time} that read the
 * system clock, and only the clock configuration may create a system clock.
 * <p>
 * The rules match every access to a code unit, so method references ({@code Instant::now}) count
 * as well as calls.
 */
class ClockRuleTest {

	static final String CLOCK_CONFIGURATION = "vn.vibefix.shared.internal.ClockConfiguration";

	private static final Set<String> TIME_TYPES = Set.of("java.time.Instant", "java.time.LocalDateTime",
			"java.time.LocalDate", "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
			"java.time.ZonedDateTime", "java.time.Year", "java.time.YearMonth", "java.time.MonthDay");

	private static final Set<String> SYSTEM_CLOCKS = Set.of("systemUTC", "systemDefaultZone", "system", "tickMillis",
			"tickSeconds", "tickMinutes");

	static final DescribedPredicate<JavaAccess<?>> NOW_WITHOUT_CLOCK = DescribedPredicate.describe(
			"a java.time now() that reads the system clock", access -> {
				if (!(access.getTarget() instanceof CodeUnitAccessTarget target) || !target.getName().equals("now")
						|| !TIME_TYPES.contains(target.getOwner().getName())) {
					return false;
				}
				List<JavaClass> parameters = target.getRawParameterTypes();
				return parameters.isEmpty() || parameters.getFirst().isEquivalentTo(ZoneId.class);
			});

	static final DescribedPredicate<JavaAccess<?>> SYSTEM_CLOCK = DescribedPredicate.describe(
			"a system Clock or InstantSource factory",
			access -> access.getTarget() instanceof CodeUnitAccessTarget target
					&& (target.getOwner().isEquivalentTo(Clock.class)
							|| target.getOwner().isEquivalentTo(InstantSource.class))
					&& SYSTEM_CLOCKS.contains(target.getName()));

	static final ArchRule NO_NOW = noClasses().should()
		.accessTargetWhere(NOW_WITHOUT_CLOCK)
		.because("business time comes only from the injected Clock (Conventions, Time)");

	static final ArchRule NO_SYSTEM_CLOCK = noClasses().that()
		.doNotHaveFullyQualifiedName(CLOCK_CONFIGURATION)
		.should()
		.accessTargetWhere(SYSTEM_CLOCK)
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
			.hasMessageContaining("calls method <java.time.Instant.now()>")
			.hasMessageContaining("LocalDateTime.now()")
			.hasMessageContaining("ZonedDateTime.now(java.time.ZoneId)")
			.hasMessageContaining("references method <java.time.Instant.now()>")
			.hasMessageNotContaining("LocalDate.now(java.time.Clock)");
		assertThatThrownBy(() -> NO_SYSTEM_CLOCK.check(fixture)).isInstanceOf(AssertionError.class)
			.hasMessageContaining("calls method <java.time.Clock.systemUTC()>")
			.hasMessageContaining("references method <java.time.Clock.systemUTC()>")
			.hasMessageContaining("Clock.tickMillis(java.time.ZoneId)")
			.hasMessageContaining("InstantSource.system()");
	}

}
