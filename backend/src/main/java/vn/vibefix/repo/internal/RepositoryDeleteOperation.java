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

/**
 * {@value RepositoryDeletions#DELETE} (FR-21, FR-38, NFR-10): deletes the Working Repository, then the
 * Intermediary Repository, and reads both back as gone. vibefix keeps no copy of the code, so nothing is
 * archived first (Story 23.10d); a row queued before that story carries a {@code zip} flag and a
 * {@code customerId} that nothing reads any more, and deletes the repositories its payload names all the
 * same. The payload names them so a Product deletion's request runs after the rows went. Collaborators are
 * looked up on use.
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
			List<PlatformRepository> gone = new ArrayList<>();
			// The team's copy first, then the Intermediary Repository the Customer reads (there is no fork, D1).
			for (PlatformRepository repository : new PlatformRepository[] { working, intermediary }) {
				if (repository == null) {
					continue;
				}
				// A retry re-enters the loop after one repository was already deleted. Deleting it again mints a
				// token narrowed to a numeric ID GitHub no longer knows, which it refuses with 422 -- another
				// Transient outcome, so the operation would never finish. From the second attempt on, read the
				// outside state first and skip what is gone (OperationHandler's own contract).
				if (run.attempt() > 1 && repositories.find(repository.name()).isEmpty()) {
					continue;
				}
				repositories.delete(repository);
				gone.add(repository);
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
		deletions.deleted(setupId, jobId, intermediary == null ? null : intermediary.id());
		return Outcome.succeeded();
	}

	private static @Nullable PlatformRepository repository(Map<String, Object> payload, String name) {
		Object id = payload.get(name + "Id");
		Object fullName = payload.get(name + "Name");
		return id == null || fullName == null ? null
				: RepositoryDeletions.repository(((Number) id).longValue(), String.valueOf(fullName));
	}

}
