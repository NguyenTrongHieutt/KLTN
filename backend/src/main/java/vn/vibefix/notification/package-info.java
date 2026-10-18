/**
 * Layer 1 (AD-3): notifications (AD-27). Modules send notifications through
 * {@link vn.vibefix.notification.Notifications} (in-app items and email from one type key) and open
 * and close to-dos through {@link vn.vibefix.notification.Todos}; the next-action strip, the to-do
 * lists, ALL-1 and the bell read them. Every type and to-do key is in the registry with the audience
 * classes it may reach (AD-20), confirmed through the {@link vn.vibefix.notification.RecipientLookup}
 * port. Email goes out as External Operations of type {@code notification.email} through the email
 * lane, from Vietnamese server templates that escape every parameter (AD-21), within the daily
 * budget and its priority classes (AD-34).
 * <p>
 * Inside L1 the order is {@code audit}, then {@code operations}, then {@code notification}: this
 * module may call {@code operations} (ModuleLayersTest).
 * <p>
 * Public API: {@link vn.vibefix.notification.Notifications}, {@link vn.vibefix.notification.NotificationRequest},
 * {@link vn.vibefix.notification.Recipient}, {@link vn.vibefix.notification.Audience},
 * {@link vn.vibefix.notification.Channel}, {@link vn.vibefix.notification.NotificationType},
 * {@link vn.vibefix.notification.Todos}, {@link vn.vibefix.notification.TodoRequest},
 * {@link vn.vibefix.notification.TodoType}, {@link vn.vibefix.notification.NotificationCatalog},
 * the ports {@link vn.vibefix.notification.RecipientLookup} and
 * {@link vn.vibefix.notification.RecipientAddresses}, and {@link vn.vibefix.notification.Emails},
 * {@link vn.vibefix.notification.EmailRequest} and {@link vn.vibefix.notification.EmailClass}.
 */
@ApplicationModule(allowedDependencies = { "shared", "operations" })
package vn.vibefix.notification;

import org.springframework.modulith.ApplicationModule;
