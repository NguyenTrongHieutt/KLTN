package vn.vibefix.scanning.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import vn.vibefix.scanning.SensitiveChanges;

/**
 * The Sensitive Change rules by path, with no AI (AD-18): a file name or folder that CI services,
 * package managers, or build tools run, and executable or archive extensions. The first rule that
 * matches decides the kind; anything else is not sensitive.
 */
@Component
class PathSensitiveChanges implements SensitiveChanges {

	private static final Set<String> CI_FILES = Set.of(".gitlab-ci.yml", ".travis.yml", "jenkinsfile", "azure-pipelines.yml",
			"bitbucket-pipelines.yml", "cloudbuild.yaml", "cloudbuild.yml", "netlify.toml", "vercel.json", "render.yaml",
			"fly.toml", "amplify.yml", "appveyor.yml", ".drone.yml", "buildspec.yml", "railway.json", "railway.toml",
			"nixpacks.toml", "codemagic.yaml", "firebase.json");

	private static final List<String> CI_FOLDERS = List.of(".circleci/", ".github/", ".buildkite/", ".gitlab/");

	private static final Set<String> DEPENDENCY_FILES = Set.of("package.json", "package-lock.json", "npm-shrinkwrap.json",
			"yarn.lock", "pnpm-lock.yaml", "pnpm-workspace.yaml", "bun.lock", "bun.lockb", "deno.json", "deno.lock",
			".npmrc", ".yarnrc", ".yarnrc.yml", "pipfile", "pipfile.lock", "pyproject.toml", "poetry.lock", "uv.lock",
			"setup.cfg", "gemfile", "gemfile.lock", "composer.json", "composer.lock", "go.mod", "go.sum", "cargo.toml",
			"cargo.lock", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
			"packages.config", "pubspec.yaml", "pubspec.lock", "podfile", "podfile.lock");

	private static final Pattern REQUIREMENTS = Pattern.compile("requirements([-_.][a-z0-9_-]+)?\\.(txt|in)");

	private static final Set<String> BUILD_FILES = Set.of("dockerfile", "makefile", "procfile", "setup.py", "gradlew",
			"gradlew.bat", "mvnw", "mvnw.cmd", ".babelrc", "justfile");

	private static final Pattern BUILD_NAMES = Pattern.compile(
			"dockerfile\\..+|.+\\.dockerfile|(docker-)?compose(\\..+)?\\.ya?ml|.+\\.config\\.(js|cjs|mjs|ts|cts|mts)");

	private static final Set<String> SCRIPT_EXTENSIONS = Set.of("sh", "bash", "zsh", "ps1", "bat", "cmd");

	private static final Set<String> BINARY_EXTENSIONS = Set.of("exe", "dll", "so", "dylib", "bin", "jar", "war", "class",
			"wasm", "node", "pyc", "apk", "ipa", "msi", "deb", "rpm", "dmg", "iso", "zip", "tar", "gz", "tgz", "bz2", "xz",
			"7z", "rar");

	@Override
	public List<SensitiveChange> classify(Collection<String> paths) {
		List<SensitiveChange> changes = new ArrayList<>();
		for (String path : new LinkedHashSet<>(paths)) {
			Kind kind = kind(path);
			if (kind != null) {
				changes.add(new SensitiveChange(path, kind));
			}
		}
		return changes;
	}

	private static Kind kind(String path) {
		if (path.isBlank() || SensitiveChanges.workflow(path)) {
			return null;
		}
		String lower = path.toLowerCase(Locale.ROOT);
		String name = lower.substring(lower.lastIndexOf('/') + 1);
		String extension = name.lastIndexOf('.') > 0 ? name.substring(name.lastIndexOf('.') + 1) : "";
		if (CI_FILES.contains(name) || CI_FOLDERS.stream().anyMatch(lower::startsWith)) {
			return Kind.CI_CONFIG;
		}
		if (DEPENDENCY_FILES.contains(name) || name.endsWith(".csproj") || REQUIREMENTS.matcher(name).matches()
				|| lower.startsWith("requirements/") && extension.equals("txt")) {
			return Kind.DEPENDENCIES;
		}
		if (BUILD_FILES.contains(name) || BUILD_NAMES.matcher(name).matches() || SCRIPT_EXTENSIONS.contains(extension)
				|| lower.startsWith(".husky/") || lower.contains("/.husky/")) {
			return Kind.BUILD_SCRIPT;
		}
		if (BINARY_EXTENSIONS.contains(extension)) {
			return Kind.BINARY;
		}
		return null;
	}

}
