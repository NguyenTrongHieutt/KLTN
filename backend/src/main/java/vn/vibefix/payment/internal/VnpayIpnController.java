package vn.vibefix.payment.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.payment.internal.PaymentOrders.IpnAnswer;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;

/**
 * VNPay's IPN (AD-14): the only request that changes a Payment Order. Anyone may call it, since the
 * hash proves who sent it; the answer is VNPay's {@code RspCode} JSON. A FUND booked concurrently by a
 * replay answers {@code 02} once its transaction rolled back.
 */
@RestController
@Access(Gate.PUBLIC)
class VnpayIpnController {

	record IpnResponse(String RspCode, String Message) {
	}

	private final PaymentOrders orders;

	VnpayIpnController(PaymentOrders orders) {
		this.orders = orders;
	}

	@GetMapping("/webhooks/vnpay/ipn")
	IpnResponse vnpayIpn(@RequestParam Map<String, String> params) {
		IpnAnswer answer;
		try {
			answer = this.orders.ipn(new LinkedHashMap<>(params));
		}
		catch (DuplicateKeyException replayed) {
			answer = IpnAnswer.REPLAYED;
		}
		return new IpnResponse(answer.rspCode(), answer.message());
	}

}
