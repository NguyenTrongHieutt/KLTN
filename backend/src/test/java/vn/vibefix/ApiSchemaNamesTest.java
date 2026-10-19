package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * springdoc names a schema after the record's simple name, so two API records with the same simple
 * name in different controllers silently share one schema, and the generated client types one of
 * them wrong (AD-23). Every record an endpoint reads or returns, directly or nested, has a name of
 * its own.
 */
class ApiSchemaNamesTest {

	@Test
	void everyApiRecordHasItsOwnSchemaName() {
		Map<String, Set<String>> byName = new TreeMap<>();
		new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("vn.vibefix")
			.stream()
			.filter(type -> type.isAnnotatedWith(RestController.class))
			.map(JavaClass::reflect)
			.forEach(controller -> {
				for (Method method : controller.getDeclaredMethods()) {
					if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
						collect(method.getGenericReturnType(), byName);
						for (Type parameter : method.getGenericParameterTypes()) {
							collect(parameter, byName);
						}
					}
				}
			});
		assertThat(byName).as("API records found").isNotEmpty();
		assertThat(byName.values().stream().filter(classes -> classes.size() > 1).toList())
			.as("API records sharing a schema name")
			.isEmpty();
	}

	private static void collect(Type type, Map<String, Set<String>> byName) {
		switch (type) {
			case Class<?> raw when raw.isArray() -> collect(raw.getComponentType(), byName);
			case Class<?> raw when raw.isRecord() -> {
				if (byName.computeIfAbsent(raw.getSimpleName(), name -> new TreeSet<>()).add(raw.getName())) {
					for (RecordComponent component : raw.getRecordComponents()) {
						collect(component.getGenericType(), byName);
					}
				}
			}
			case ParameterizedType parameterized -> {
				collect(parameterized.getRawType(), byName);
				for (Type argument : parameterized.getActualTypeArguments()) {
					collect(argument, byName);
				}
			}
			case GenericArrayType array -> collect(array.getGenericComponentType(), byName);
			case WildcardType wildcard -> {
				for (Type bound : wildcard.getUpperBounds()) {
					collect(bound, byName);
				}
			}
			default -> {
			}
		}
	}

}
