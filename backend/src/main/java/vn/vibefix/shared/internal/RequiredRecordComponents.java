package vn.vibefix.shared.internal;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * API contract convention (AD-23): every component of a record in the OpenAPI document is
 * {@code required} unless it is annotated {@link Nullable}, so the generated TypeScript types are
 * {@code name: string} rather than {@code name?: string}. springdoc registers this converter
 * because it is a bean.
 */
@Component
class RequiredRecordComponents implements ModelConverter {

	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
		if (!chain.hasNext()) {
			return null;
		}
		Schema resolved = chain.next().resolve(type, context, chain);
		Class<?> raw = rawClass(type.getType());
		if (resolved == null || raw == null || !raw.isRecord()) {
			return resolved;
		}
		Schema model = resolved;
		if (resolved.get$ref() != null) {
			String ref = resolved.get$ref();
			model = context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
		}
		if (model == null) {
			return resolved;
		}
		Map<String, Schema> properties = model.getProperties();
		if (properties == null) {
			return resolved;
		}
		for (RecordComponent component : raw.getRecordComponents()) {
			String name = component.getName();
			List<String> required = model.getRequired();
			if (properties.containsKey(name) && !component.getAnnotatedType().isAnnotationPresent(Nullable.class)
					&& (required == null || !required.contains(name))) {
				model.addRequiredItem(name);
			}
		}
		return resolved;
	}

	private static @Nullable Class<?> rawClass(@Nullable Type type) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof JavaType javaType) {
			return javaType.getRawClass();
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
			return c;
		}
		return null;
	}

}
