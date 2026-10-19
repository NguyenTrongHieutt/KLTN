package vn.vibefix.scanning;

/** Why a scan runs (AD-18). Push and handover scans arrive with Epic 7. */
public enum ScanPurpose {

	/** The whole history of a newly registered Product (FR-8). */
	INTAKE,

	/**
	 * A clean repository offered in place of the Source Repository (FR-65): its Findings count only
	 * when it holds no personal data, and its run is the Product's only once adopted.
	 */
	CLEAN_REPOSITORY,

	/** The whole history again, on the Customer's request ("Kiểm tra lại", at most 5 a day). */
	RESCAN

}
