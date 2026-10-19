package vn.vibefix.chat.internal;

/** {@code chat}'s refusal reasons ({@code reason.chat.*} in {@code vi.json}). */
enum ChatReason {

	/** FR-47 found a likely credential or bulk personal data; the dialog lists the masked matches. */
	SENSITIVE_CONTENT,

	/** A message needs text or an attachment. */
	MESSAGE_EMPTY

}
