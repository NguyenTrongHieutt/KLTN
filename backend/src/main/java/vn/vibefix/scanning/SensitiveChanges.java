package vn.vibefix.scanning;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Sensitive Changes (PRD glossary, FR-32, FR-37, SG-19, AD-33): the changed files that can run code on
 * the Customer's systems. Handovers and the Final Merge read the paths a commit range changed and ask
 * here which are sensitive; each kind has one plain explanation on the screens. A path under
 * {@code .github/workflows/} is never one of them, because a handover that changes it is refused.
 */
public interface SensitiveChanges {

	/**
	 * The folder a handover may not change (FR-32, AD-18), as a path in the repository: a handover compares
	 * its tree at both ends of the range, which no cut list of changed files can hide.
	 */
	String WORKFLOWS_DIRECTORY = ".github/workflows";

	/** {@value #WORKFLOWS_DIRECTORY} as the prefix of the paths under it. */
	String WORKFLOWS = WORKFLOWS_DIRECTORY + "/";

	enum Kind {

		/** CI configuration outside {@value SensitiveChanges#WORKFLOWS}, such as {@code .gitlab-ci.yml} or {@code vercel.json}. */
		CI_CONFIG,

		/** Dependency manifests and lock files, such as {@code package.json} or {@code yarn.lock}. */
		DEPENDENCIES,

		/** Install and build scripts, such as a {@code Dockerfile} or {@code vite.config.ts}. */
		BUILD_SCRIPT,

		/** Executable or archive files, by extension. */
		BINARY

	}

	record SensitiveChange(String path, Kind kind) {

		public SensitiveChange {
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(kind, "kind");
		}

	}

	/** The sensitive paths among {@code paths}, in their order, each once. */
	List<SensitiveChange> classify(Collection<String> paths);

	/** Whether {@code path} is under {@value #WORKFLOWS}. */
	static boolean workflow(String path) {
		return path.startsWith(WORKFLOWS);
	}

}
