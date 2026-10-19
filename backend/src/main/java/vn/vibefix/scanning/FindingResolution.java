package vn.vibefix.scanning;

/** How a Finding was resolved (FR-8). False positives and clean repositories arrive with Stories 2.6 and 2.7. */
public enum FindingResolution {

	/** The resolver confirmed the credential was rotated ("Tôi đã đổi khóa"). */
	ROTATED,

	/** The Source Repository was replaced by a clean one without personal data (FR-65). */
	CLEAN_REPOSITORY

}
