package com.prodigalgal.ircs.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;

class OpsSpringBeanConstructorPolicyTest {

    @Test
    void springBeansUseSingleConstructorAndNoAutowiredInjectionPoints() throws Exception {
        List<String> violations = new ArrayList<>();
        Class<? extends Annotation> forbiddenInjectionAnnotation = forbiddenInjectionAnnotation();
        for (Class<?> beanClass : opsClasses()) {
            if (!isSpringBean(beanClass)) {
                continue;
            }
            Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
            if (constructors.length != 1) {
                violations.add(beanClass.getName() + " declares " + constructors.length + " constructors");
            }
            for (Constructor<?> constructor : constructors) {
                if (constructor.isAnnotationPresent(forbiddenInjectionAnnotation)) {
                    violations.add(beanClass.getName() + " constructor uses forbidden injection annotation");
                }
            }
            for (Field field : beanClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(forbiddenInjectionAnnotation)) {
                    violations.add(beanClass.getName() + "#" + field.getName()
                            + " field uses forbidden injection annotation");
                }
            }
            for (Method method : beanClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(forbiddenInjectionAnnotation)) {
                    violations.add(beanClass.getName() + "#" + method.getName()
                            + " method uses forbidden injection annotation");
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private static List<Class<?>> opsClasses() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources("classpath*:com/prodigalgal/ircs/ops/**/*.class");
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : resources) {
            String className = metadataReaderFactory.getMetadataReader(resource).getClassMetadata().getClassName();
            if (!className.contains("$")) {
                classes.add(Class.forName(className, false, classLoader));
            }
        }
        return classes;
    }

    private static boolean isSpringBean(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, Component.class);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> forbiddenInjectionAnnotation() throws ClassNotFoundException {
        return (Class<? extends Annotation>) Class.forName(
                "org.springframework.beans.factory.annotation." + "Autowired");
    }
}
