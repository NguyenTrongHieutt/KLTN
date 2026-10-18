package vn.vibefix.operations.internal;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;

/** Every {@link OperationHandler} of the application, by type: one handler per type. */
@Component
class OperationHandlers {

	private static final Pattern TYPE = Pattern.compile("[a-z]+(\\.[a-z0-9_]+)+");

	private final Map<String, OperationHandler> byType = new TreeMap<>();

	OperationHandlers(List<OperationHandler> handlers) {
		for (OperationHandler handler : handlers) {
			String type = handler.type();
			if (type == null || !TYPE.matcher(type).matches()) {
				throw new IllegalStateException("An operation type looks like module.name, not '" + type + "'");
			}
			OperationHandler previous = this.byType.putIfAbsent(type, handler);
			if (previous != null) {
				throw new IllegalStateException("Operation type " + type + " has two handlers: "
						+ previous.getClass().getName() + " and " + handler.getClass().getName());
			}
		}
	}

	OperationHandler require(String type) {
		OperationHandler handler = this.byType.get(type);
		if (handler == null) {
			throw new IllegalArgumentException("No OperationHandler runs operations of type " + type);
		}
		return handler;
	}

	/** The lanes that have at least one handler; only they are polled. */
	Set<Lane> lanes() {
		Set<Lane> lanes = EnumSet.noneOf(Lane.class);
		this.byType.values().forEach(handler -> lanes.add(handler.lane()));
		return lanes;
	}

	List<String> types(Lane lane) {
		return this.byType.values().stream().filter(handler -> handler.lane() == lane).map(OperationHandler::type).toList();
	}

}
