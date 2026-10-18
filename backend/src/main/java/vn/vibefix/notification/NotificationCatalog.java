package vn.vibefix.notification;

import java.util.List;

/**
 * Entries of the type registry. The application's catalog is {@code notification.internal.NotificationTypes},
 * to which each story adds the types and to-do keys its module sends; tests add catalogs of their own.
 */
public interface NotificationCatalog {

	List<NotificationType> notificationTypes();

	List<TodoType> todoTypes();

}
