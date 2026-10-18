package vn.vibefix;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * AD-3: modules sit in layers, and a module may depend only on modules in lower layers. Every
 * module must declare {@code @ApplicationModule(allowedDependencies = ...)} in its
 * {@code package-info.java}, and every declared dependency must be in a lower layer.
 * {@link ModularityTests} then verifies the code against those declarations.
 */
class ModuleLayersTest {

	/** AD-3's layer table. A new top-level package under {@code vn.vibefix} must be added here first. */
	static final Map<String, Integer> LAYERS = Map.ofEntries(entry("shared", 0), entry("audit", 1), entry("files", 1),
			entry("settings", 1), entry("catalog", 1), entry("github", 1), entry("operations", 1), entry("timers", 1),
			entry("notification", 1), entry("identity", 2), entry("team", 3), entry("scanning", 4),
			entry("product", 5), entry("job", 6), entry("matching", 7), entry("repo", 7), entry("payment", 7),
			entry("work", 7), entry("chat", 7), entry("delivery", 8), entry("reputation", 9), entry("dispute", 10),
			entry("admin", 11));

	/**
	 * The order inside L1, which AD-3 leaves open: each module listed may depend on the ones before
	 * it. {@code operations} writes audit entries at completion (AD-6, AD-8), and {@code notification}
	 * sends email as External Operations (AD-27). Other L1 modules may not depend on each other.
	 */
	static final List<String> L1_ORDER = List.of("audit", "operations", "notification");

	@Test
	void everyModuleIsInTheLayerTable() {
		List<String> modules = ApplicationModules.of(VibefixApplication.class)
			.stream()
			.map(module -> module.getIdentifier().toString())
			.toList();

		assertThat(modules).isNotEmpty().allSatisfy(module -> assertThat(LAYERS).containsKey(module));
	}

	@Test
	void everyModuleDeclaresDependenciesOnLowerLayersOnly() {
		ApplicationModules.of(VibefixApplication.class).forEach(module -> {
			String name = module.getIdentifier().toString();
			ApplicationModule declaration = declarationOf(module.getBasePackage().getName());

			assertThat(declaration).as("@ApplicationModule in %s/package-info.java", name).isNotNull();
			assertThat(declaration.allowedDependencies())
				.as("%s must list its allowed dependencies explicitly", name)
				.doesNotContain(ApplicationModule.OPEN_TOKEN);
			assertThat(violations(name, declaration.allowedDependencies())).as("layer violations of %s", name)
				.isEmpty();
		});
	}

	@Test
	void layerRuleRejectsSameAndHigherLayers() {
		assertThat(violations("identity", new String[] { "shared", "audit", "notification::api" })).isEmpty();
		assertThat(violations("identity", new String[] { "team" })).hasSize(1);
		assertThat(violations("audit", new String[] { "files" })).hasSize(1);
		assertThat(violations("shared", new String[] { "audit" })).hasSize(1);
		assertThat(violations("job", new String[] { "unknown" })).hasSize(1);
	}

	@Test
	void insideL1OnlyTheListedOrderIsAllowed() {
		assertThat(violations("operations", new String[] { "shared", "audit" })).isEmpty();
		assertThat(violations("notification", new String[] { "shared", "audit", "operations" })).isEmpty();
		assertThat(violations("audit", new String[] { "operations" })).hasSize(1);
		assertThat(violations("operations", new String[] { "notification" })).hasSize(1);
		assertThat(violations("files", new String[] { "audit" })).hasSize(1);
		assertThat(violations("notification", new String[] { "timers" })).hasSize(1);
	}

	static List<String> violations(String module, String[] allowedDependencies) {
		int layer = LAYERS.getOrDefault(module, -1);
		List<String> violations = new ArrayList<>();
		for (String dependency : allowedDependencies) {
			String target = dependency.split("::", 2)[0];
			Integer targetLayer = LAYERS.get(target);
			if (targetLayer == null || (targetLayer >= layer && !earlierInL1(target, module))) {
				violations.add(module + " (L" + layer + ") may not depend on " + dependency + " (L" + targetLayer + ")");
			}
		}
		return violations;
	}

	private static boolean earlierInL1(String target, String module) {
		int targetIndex = L1_ORDER.indexOf(target);
		int moduleIndex = L1_ORDER.indexOf(module);
		return targetIndex >= 0 && moduleIndex >= 0 && targetIndex < moduleIndex;
	}

	private static ApplicationModule declarationOf(String basePackage) {
		try {
			return Class.forName(basePackage + ".package-info").getAnnotation(ApplicationModule.class);
		}
		catch (ClassNotFoundException ex) {
			return null;
		}
	}

}
