package vn.vibefix.timers.internal;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;

/** The one {@link DeadlineHandler} of each Deadline kind; two for one kind fail the start. */
@Component
class DeadlineHandlers {

	private final Map<String, DeadlineHandler> byKind = new TreeMap<>();

	DeadlineHandlers(List<DeadlineHandler> handlers) {
		for (DeadlineHandler handler : handlers) {
			String key = handler.kind().key();
			DeadlineHandler previous = this.byKind.putIfAbsent(key, handler);
			if (previous != null) {
				throw new IllegalStateException("Deadline kind " + key + " has two handlers: "
						+ previous.getClass().getName() + " and " + handler.getClass().getName());
			}
		}
	}

	DeadlineHandler require(String kind) {
		DeadlineHandler handler = this.byKind.get(kind);
		if (handler == null) {
			throw new IllegalArgumentException("No DeadlineHandler runs Deadlines of kind " + kind);
		}
		return handler;
	}

}
