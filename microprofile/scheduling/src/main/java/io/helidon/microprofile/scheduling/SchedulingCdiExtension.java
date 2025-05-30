/*
 * Copyright (c) 2021, 2025 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.microprofile.scheduling;

import java.lang.System.Logger.Level;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.helidon.common.configurable.ScheduledThreadPoolSupplier;
import io.helidon.config.Config;
import io.helidon.config.DeprecatedConfig;
import io.helidon.microprofile.cdi.RuntimeStart;
import io.helidon.scheduling.Cron;
import io.helidon.scheduling.Invocation;
import io.helidon.scheduling.Scheduling;
import io.helidon.scheduling.Task;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessManagedBean;
import jakarta.enterprise.inject.spi.WithAnnotations;

import static jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER;

/**
 * Scheduling CDI Extension.
 */
@SuppressWarnings("removal")
public class SchedulingCdiExtension implements Extension {
    private static final System.Logger LOGGER = System.getLogger(SchedulingCdiExtension.class.getName());
    private static final Pattern CRON_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{(?<key>[^\\}]+)\\}");
    private final Queue<AnnotatedMethod<?>> methods = new LinkedList<>();
    private final Map<AnnotatedMethod<?>, Bean<?>> beans = new HashMap<>();
    private final Queue<ScheduledExecutorService> executors = new LinkedList<>();
    private Config config;
    private Config schedulingConfig;

    void registerMethods(
            @Observes
            @WithAnnotations({
                    Scheduled.class,
                    FixedRate.class,
                    Scheduling.Cron.class,
                    Scheduling.FixedRate.class}) ProcessAnnotatedType<?> pat) {
        // Lookup scheduled methods
        pat.getAnnotatedType()
                .getMethods()
                .stream()
                .filter(this::hasScheduleAnnotation)
                .forEach(methods::add);
    }

    void onProcessBean(@Observes ProcessManagedBean<?> event) {
        // Gather bean references
        Bean<?> bean = event.getBean();
        Class<?> beanClass = bean.getBeanClass();
        for (AnnotatedMethod<?> am : methods) {
            if (beanClass == am.getDeclaringType().getJavaClass()) {
                beans.put(am, bean);
            }
        }
    }

    private void prepareRuntime(@Observes @RuntimeStart Config config) {
        this.config = config;
        this.schedulingConfig = config.get("schedule");
    }

    void invoke(@Observes @Priority(PLATFORM_AFTER + 4000) @Initialized(ApplicationScoped.class) Object event,
                BeanManager beanManager) {

        var scheduledThreadPoolbuilder = ScheduledThreadPoolSupplier.builder().config(schedulingConfig);

        if (scheduledThreadPoolbuilder.threadNamePrefix().isEmpty()) {
            scheduledThreadPoolbuilder.threadNamePrefix("scheduled-");
        }

        ScheduledExecutorService executorService = scheduledThreadPoolbuilder.build().get();
        executors.add(executorService);

        for (AnnotatedMethod<?> am : methods) {
            Class<?> aClass = am.getDeclaringType().getJavaClass();
            Bean<?> bean = beans.get(am);
            Object beanInstance = lookup(bean, beanManager);
            Method method = am.getJavaMember();

            if (!method.trySetAccessible()) {
                throw new DeploymentException(String.format("Scheduled method %s#%s is not accessible!",
                        method.getDeclaringClass().getName(),
                        method.getName()));
            }

            if (am.isAnnotationPresent(FixedRate.class)
                    && am.isAnnotationPresent(Scheduled.class)) {
                throw new DeploymentException(String.format("Scheduled method %s#%s can have only one scheduling annotation.",
                        method.getDeclaringClass().getName(),
                        method.getName()));
            }

            String defaultConfigKey = aClass.getName() + "." + method.getName() + ".schedule";
            if (am.isAnnotationPresent(FixedRate.class)) {
                FixedRate annotation = am.getAnnotation(FixedRate.class);
                Config methodConfig = config.get(defaultConfigKey);

                Task task = io.helidon.scheduling.FixedRate.builder()
                        .initialDelay(annotation.initialDelay())
                        .delayType(annotation.delayType())
                        .delay(annotation.value())
                        .timeUnit(annotation.timeUnit())
                        .config(methodConfig)
                        .executor(executorService)
                        .task(inv -> invokeWithOptionalParam(beanInstance, method, inv))
                        .build();

                LOGGER.log(Level.DEBUG, () -> String.format("Method %s#%s scheduled to be executed %s",
                                                            aClass.getSimpleName(), method.getName(), task.description()));
            } else if (am.isAnnotationPresent(Scheduling.FixedRate.class)) {
                Scheduling.FixedRate annotation = am.getAnnotation(Scheduling.FixedRate.class);

                Config methodConfig;
                if (annotation.configKey().isBlank()) {
                     methodConfig = config.get(defaultConfigKey);
                } else {
                    methodConfig = config.get(annotation.configKey());
                }
                Task task = io.helidon.scheduling.FixedRate.builder()
                        .delayBy(Duration.parse(annotation.delayBy()))
                        .delayType(annotation.delayType())
                        .interval(Duration.parse(annotation.value()))
                        .config(methodConfig)
                        .executor(executorService)
                        .task(inv -> invokeWithOptionalParam(beanInstance, method, inv))
                        .build();

                LOGGER.log(Level.DEBUG, () -> String.format("Method %s#%s scheduled to be executed %s",
                                                            aClass.getSimpleName(), method.getName(), task.description()));
            } else if (am.isAnnotationPresent(Scheduled.class)) {
                Scheduled annotation = am.getAnnotation(Scheduled.class);

                Config methodConfig = config.get(defaultConfigKey);

                Task task = Cron.builder()
                        .concurrentExecution(annotation.concurrentExecution())
                        .expression(DeprecatedConfig.get(methodConfig, "expression", "cron")
                                            .asString()
                                            .orElseGet(() -> resolvePlaceholders(annotation.value(), config)))
                        .config(methodConfig)
                        .executor(executorService)
                        .task(inv -> invokeWithOptionalParam(beanInstance, method, inv))
                        .build();

                LOGGER.log(Level.DEBUG, () -> String.format("Method %s#%s scheduled to be executed %s",
                        aClass.getSimpleName(), method.getName(), task.description()));
            } else if (am.isAnnotationPresent(Scheduling.Cron.class)) {
                Scheduling.Cron annotation = am.getAnnotation(Scheduling.Cron.class);

                Config methodConfig;
                if (annotation.configKey().isBlank()) {
                    methodConfig = config.get(defaultConfigKey);
                } else {
                    methodConfig = config.get(annotation.configKey());
                }
                Task task = Cron.builder()
                        .concurrentExecution(annotation.concurrent())
                        .expression(DeprecatedConfig.get(methodConfig, "expression", "cron")
                                            .asString()
                                            .orElseGet(() -> resolvePlaceholders(annotation.value(), config)))
                        .config(methodConfig)
                        .executor(executorService)
                        .task(inv -> invokeWithOptionalParam(beanInstance, method, inv))
                        .build();

                LOGGER.log(Level.DEBUG, () -> String.format("Method %s#%s scheduled to be executed %s",
                                                            aClass.getSimpleName(), method.getName(), task.description()));
            }
        }
    }

    void terminate(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @SuppressWarnings("unchecked")
    static <T> T lookup(Bean<?> bean, BeanManager beanManager) {
        jakarta.enterprise.context.spi.Context context = beanManager.getContext(bean.getScope());
        Object instance = context.get(bean);

        if (instance == null) {
            CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
            instance = beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
        }

        if (instance == null) {
            throw new DeploymentException("Instance of bean " + bean.getName() + " not found");
        }

        return (T) instance;
    }

    static void invokeWithOptionalParam(Object instance, Method method, Invocation invocation)
            throws InvocationTargetException, IllegalAccessException {

        Class<?>[] parameterTypes = method.getParameterTypes();
        Class<? extends Invocation> invClazz = invocation.getClass();

        if (parameterTypes.length > 1
                || parameterTypes.length > 0 && !parameterTypes[0].isAssignableFrom(invClazz)) {

            throw new DeploymentException(
                    String.format("Unsupported param types for scheduled method %s, none or %s is supported.",
                            method.getName(), invClazz.getName())
            );
        }

        if (parameterTypes.length == 0) {
            method.invoke(instance);
        } else {
            method.invoke(instance, invocation);
        }
    }

    static String resolvePlaceholders(String src, Config config) {
        Matcher m = CRON_PLACEHOLDER_PATTERN.matcher(src);
        StringBuilder result = new StringBuilder();
        int index = 0;

        while (m.find()) {
            String key = m.group("key");
            String value = config.get(key)
                    .asString()
                    .orElseThrow(() ->
                            new IllegalArgumentException(String.format("Scheduling placeholder %s could not be resolved.", key))
                    );

            result.append(src, index, m.start()).append(value);
            index = m.end();
        }

        if (index < src.length()) {
            result.append(src, index, src.length());
        }

        return result.toString();
    }

    private boolean hasScheduleAnnotation(AnnotatedMethod<?> am) {
        return am.isAnnotationPresent(Scheduled.class)
                || am.isAnnotationPresent(FixedRate.class)
                || am.isAnnotationPresent(Scheduling.Cron.class)
                || am.isAnnotationPresent(Scheduling.FixedRate.class);
    }
}
