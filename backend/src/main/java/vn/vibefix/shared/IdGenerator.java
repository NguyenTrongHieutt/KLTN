package vn.vibefix.shared;

import java.util.UUID;

/**
 * New primary keys (Conventions, IDs): UUIDv7, generated in the application and exposed in the API.
 * Their first 48 bits are the injected clock's millisecond, so IDs sort roughly by creation time.
 */
public interface IdGenerator {

	UUID newId();

}
