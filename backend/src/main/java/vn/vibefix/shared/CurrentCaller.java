package vn.vibefix.shared;

/** The caller of the request being handled (AD-20). */
public interface CurrentCaller {

	/**
	 * The current request's caller; {@link Caller#anonymous()} when nobody is signed in.
	 * @throws IllegalStateException outside a request: pollers and listeners act as the System and
	 * have no caller
	 */
	Caller get();

}
