package vn.vibefix.payment.internal;

/** Why {@code payment} refuses (AD-23 reasons). */
enum PaymentReason {

	/** The environment has no VNPay sandbox merchant yet (deploy/README.md "Spike S2"). */
	PAYMENT_NOT_CONFIGURED

}
