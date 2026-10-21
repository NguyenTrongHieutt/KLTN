package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.vibefix.scanning.SensitiveChanges;
import vn.vibefix.scanning.SensitiveChanges.Kind;
import vn.vibefix.scanning.SensitiveChanges.SensitiveChange;

/** PRD glossary, FR-32, AD-33: which changed files can run code on the Customer's systems. */
class PathSensitiveChangesTest {

	private final SensitiveChanges changes = new PathSensitiveChanges();

	@Test
	void eachKindIsFoundByNameFolderOrExtension() {
		assertThat(this.changes.classify(List.of("vercel.json", ".circleci/config.yml", ".github/dependabot.yml",
				"package.json", "web/yarn.lock", "requirements-dev.txt", "api/pyproject.toml", "Dockerfile",
				"deploy/docker-compose.prod.yml", "vite.config.ts", "scripts/seed.sh", ".husky/pre-commit", "tools/helper.EXE",
				"assets/fonts.zip")))
			.extracting(SensitiveChange::kind)
			.containsExactly(Kind.CI_CONFIG, Kind.CI_CONFIG, Kind.CI_CONFIG, Kind.DEPENDENCIES, Kind.DEPENDENCIES,
					Kind.DEPENDENCIES, Kind.DEPENDENCIES, Kind.BUILD_SCRIPT, Kind.BUILD_SCRIPT, Kind.BUILD_SCRIPT,
					Kind.BUILD_SCRIPT, Kind.BUILD_SCRIPT, Kind.BINARY, Kind.BINARY);
	}

	@Test
	void ordinarySourceFilesAndWorkflowsAreNotListed() {
		assertThat(this.changes.classify(List.of("src/App.tsx", "README.md", "public/logo.png", "src/config.json",
				".github/workflows/ci.yml", "src/App.tsx"))).isEmpty();
		assertThat(SensitiveChanges.workflow(".github/workflows/ci.yml")).isTrue();
		assertThat(SensitiveChanges.workflow("docs/.github/workflows/ci.yml")).isFalse();
	}

	@Test
	void aPathChangedTwiceIsListedOnce() {
		assertThat(this.changes.classify(List.of("package.json", "package.json"))).hasSize(1);
	}

}
