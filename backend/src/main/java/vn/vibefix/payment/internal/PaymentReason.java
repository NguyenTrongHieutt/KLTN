package vn.vibefix.payment.internal;

/** Why {@code payment} refuses (AD-23 reasons). */
enum PaymentReason {

	/** The environment has no VNPay sandbox merchant yet (deploy/README.md "Spike S2"). */
	PAYMENT_NOT_CONFIGURED,

	/** A share is held only on a Milestone with nothing released, for a party of its pin (FR-79, AD-40). */
	MILESTONE_NOT_HELD

}
