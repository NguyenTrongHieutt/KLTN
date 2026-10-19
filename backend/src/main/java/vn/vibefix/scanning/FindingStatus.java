package vn.vibefix.scanning;

/** Where a Finding stands (FR-8, FR-65). */
public enum FindingStatus {

	/** Not yet resolved ("Chưa xử lý"). */
	OPEN,

	/** Resolved by one of its resolvers ("Đã xử lý"). */
	RESOLVED

}
