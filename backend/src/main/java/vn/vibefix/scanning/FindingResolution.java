package vn.vibefix.scanning;

/** How a Finding was resolved (FR-8). False positives and clean repositories arrive with Stories 2.6 and 2.7. */
public enum FindingResolution {

	/** The resolver confirmed the credential was rotated ("Tôi đã đổi khóa"). */
	ROTATED

}
