package vn.vibefix.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the {@link Gate} of a controller method, or of every method of a controller. Access is
 * denied by default (AD-20): a handler under {@code /api} or {@code /webhooks} without this
 * annotation answers {@code 404 NOT_FOUND} to everyone, and {@code MatrixCoverageIT} fails the
 * build.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Access {

	Gate value();

}
