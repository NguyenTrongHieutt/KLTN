package vn.vibefix.identity;

/** The Administrators as other modules see them (FR-2, SG-10): only how many can act. */
public interface Administrators {

	/** Administrators who can sign in: enrolled, not locked, not erased. */
	long activeCount();

}
