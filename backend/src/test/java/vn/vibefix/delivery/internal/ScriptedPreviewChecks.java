package vn.vibefix.delivery.internal;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The preview check in tests that boot the application (AD-32): every link responds unless a test
 * scripts it as unreachable, since no test reaches the internet. {@code PreviewCheckJobHandlerTest}
 * covers the real check's rules.
 */
public class ScriptedPreviewChecks implements PreviewChecks {

	private final Set<String> unreachable = ConcurrentHashMap.newKeySet();

	/** Every link checked, in order. */
	public final List<String> checked = new CopyOnWriteArrayList<>();

	/** {@code url} does not respond from now on. */
	public void unreachable(String url) {
		this.unreachable.add(url);
	}

	@Override
	public Result check(String url) {
		this.checked.add(url);
		return this.unreachable.contains(url) ? new Result(false, PreviewChecks.domain(url), PreviewCheckJobHandler.NO_RESPONSE)
				: new Result(true, PreviewChecks.domain(url), null);
	}

}
