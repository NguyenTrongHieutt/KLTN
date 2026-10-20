package vn.vibefix.job.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.job.JobDrafts;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/** {@link JobDrafts} through {@link Jobs#create}, with every rule CUS-6's save has. */
@Component
class JobDraftsFromFollowUps implements JobDrafts {

	private final Jobs jobs;

	JobDraftsFromFollowUps(Jobs jobs) {
		this.jobs = jobs;
	}

	@Override
	public UUID createDraft(UUID customerId, UUID productId, String title, @Nullable String problem,
			@Nullable String expected, List<String> jobTypes, List<UUID> skillIds) {
		Set<JobType> types = new LinkedHashSet<>();
		for (String type : jobTypes) {
			try {
				types.add(JobType.valueOf(type));
			}
			catch (IllegalArgumentException unknown) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
		}
		return this.jobs.create(customerId, productId,
				new Jobs.Draft(title, problem, expected, null, null, types, List.copyOf(skillIds), null, null));
	}

}
