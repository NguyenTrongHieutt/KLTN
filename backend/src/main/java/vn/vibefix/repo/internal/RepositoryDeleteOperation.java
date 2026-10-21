package vn.vibefix.repo.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;
import vn.vibefix.repo.internal.RepositoryDeletions.Pending;

/**
 * {@value RepositoryDeletions#DELETE} (FR-21, FR-38, AD-32, NFR-10): with {@code zip}, first makes sure
 * the final ZIP of the Intermediary Repository's default branch is stored, built in the sandbox from tree
 * objects; then deletes the Working Repository and the Intermediary Repository and reads both back as
 * gone. The payload names the repositories, so a Product deletion's request runs after the rows went.
 * Collaborators are looked up on use.
 */
@Component
class RepositoryDeleteOperation implements OperationHandler {

	private final ObjectProvider<RepositoryDeletions> deletions;

	private final ObjectProvider<PlatformRepositories> repositories;

	RepositoryDeleteOperation(ObjectProvider<RepositoryDeletions> deletions,
			ObjectProvider<PlatformRepositories> repositories) {
		this.deletions = deletions;
		this.repositories = repositories;
	}

	@Override
	public String type() {
		return RepositoryDeletions.DELETE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_LONG;
	}

	@Override
	public Outcome run(OperationRun run) {
		Map<String, Object> payload = run.payload();
		UUID setupId = UUID.fromString(String.valueOf(payload.get("setupId")));
		UUID jobId = UUID.fromString(String.valueOf(payload.get("jobId")));
		PlatformRepository intermediary = repository(payload, "intermediary");
		PlatformRepository working = repository(payload, "working");
		PlatformRepositories repositories = this.repositories.getObject();
		RepositoryDeletions deletions = this.deletions.getObject();
		try {
			if (Boolean.TRUE.equals(payload.get("zip"))) {
				Pending pending = deletions.find(setupId).orElse(null);
				if (pending == null) {
					// The Product's deletion requested its own deletion, without a ZIP.
					return new Outcome.Obsolete("SETUP_GONE");
				}
				if (pending.archiveFileId() == null && intermediary != null) {
					String branch = String.valueOf(payload.getOrDefault("branch", "main"));
					byte[] zip = repositories.zip(intermediary, branch);
					if (!deletions.archived(setupId, UUID.fromString(String.valueOf(payload.get("customerId"))),
							intermediary.name() + ".zip", zip)) {
						return new Outcome.Obsolete("SETUP_GONE");
					}
				}
			}
			List<PlatformRepository> gone = new ArrayList<>();
			// The fork first, then the repository it was forked from.
			for (PlatformRepository repository : new PlatformRepository[] { working, intermediary }) {
				if (repository != null) {
					repositories.delete(repository);
					gone.add(repository);
				}
			}
			for (PlatformRepository repository : gone) {
				if (repositories.find(repository.name()).isPresent()) {
					return new Outcome.Transient("REPOSITORY_STILL_THERE");
				}
			}
		}
		catch (GitHubException failed) {
			return new Outcome.Transient(failed.status() == 0 ? "GITHUB_UNAVAILABLE" : "GITHUB_HTTP_" + failed.status());
		}
		deletions.deleted(setupId, jobId);
		return Outcome.succeeded();
	}

	private static @Nullable PlatformRepository repository(Map<String, Object> payload, String name) {
		Object id = payload.get(name + "Id");
		Object fullName = payload.get(name + "Name");
		return id == null || fullName == null ? null
				: RepositoryDeletions.repository(((Number) id).longValue(), String.valueOf(fullName));
	}

}
