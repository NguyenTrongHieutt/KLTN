/**
 * Layer 1 (AD-3): notifications (AD-27). For now, email: {@link vn.vibefix.notification.Emails}
 * records each message as an External Operation of type {@code notification.email} in the caller's
 * transaction, and the email lane sends it over SMTP from a Vietnamese server template that escapes
 * every parameter (AD-21), within the daily budget and its priority classes (AD-34). In-app
 * notifications, to-dos, and {@code notification.send} arrive with Story 1.7.
 * <p>
 * Inside L1 the order is {@code audit}, then {@code operations}, then {@code notification}: this
 * module may call {@code operations} (ModuleLayersTest).
 * <p>
 * Public API: {@link vn.vibefix.notification.Emails}, {@link vn.vibefix.notification.EmailRequest}
 * and {@link vn.vibefix.notification.EmailClass}.
 */
@ApplicationModule(allowedDependencies = { "shared", "operations" })
package vn.vibefix.notification;

import org.springframework.modulith.ApplicationModule;
