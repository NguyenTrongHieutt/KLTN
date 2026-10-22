package vn.vibefix.repo.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.repo.DriftEvidence;

/** {@link DriftEvidence} over the warnings {@link SourceDrift} stored. */
@Component
class DriftWarningEvidence implements DriftEvidence {

	private final SourceDrift drift;

	DriftWarningEvidence(SourceDrift drift) {
		this.drift = drift;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Warning> driftWarnings(UUID jobId) {
		return this.drift.warnings(jobId)
			.stream()
			.map(warning -> new Warning(warning.id(), warning.kind().name(), warning.headSha(), warning.fullName(),
					warning.commits(), warning.detectedAt(), warning.reconfirmedAt()))
			.toList();
	}

}
