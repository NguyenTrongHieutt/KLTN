package vn.vibefix.job.internal;

/** The Job states of PRD section 4.0; Story 3.1 creates Drafts only. */
enum JobState {
	DRAFT, OPEN, SELECTING, AGREEMENT, STARTING, ACTIVE, CLOSING, CLOSED, CANCELLED
}
