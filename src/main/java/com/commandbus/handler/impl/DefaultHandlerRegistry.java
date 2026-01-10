package com.commandbus.handler.impl;

import com.commandbus.exception.HandlerAlreadyRegisteredException;
import com.commandbus.exception.HandlerNotFoundException;
import com.commandbus.handler.CommandHandler;
import com.commandbus.handler.Handler;
import com.commandbus.handler.HandlerRegistry;
import com.commandbus.model.Command;
import com.commandbus.model.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of HandlerRegistry.
 *
 * <p>Implements BeanPostProcessor to automatically discover and register
 * handlers from Spring beans annotated with @Handler.
 */
@Component
public class DefaultHandlerRegistry implements HandlerRegistry, BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultHandlerRegistry.class);

    private final Map<HandlerKey, CommandHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(String domain, String commandType, CommandHandler handler) {
        var key = new HandlerKey(domain, commandType);
        if (handlers.containsKey(key)) {
            throw new HandlerAlreadyRegisteredException(domain, commandType);
        }
        handlers.put(key, handler);
        log.debug("Registered handler for {}.{}", domain, commandType);
    }

    @Override
    public Optional<CommandHandler> get(String domain, String commandType) {
        return Optional.ofNullable(handlers.get(new HandlerKey(domain, commandType)));
    }

    @Override
    public CommandHandler getOrThrow(String domain, String commandType) {
        return get(domain, commandType)
            .orElseThrow(() -> new HandlerNotFoundException(domain, commandType));
    }

    @Override
    public Object dispatch(Command command, HandlerContext context) throws Exception {
        var handler = getOrThrow(command.domain(), command.commandType());
        log.debug("Dispatching {}.{} (commandId={})",
            command.domain(), command.commandType(), command.commandId());
        return handler.handle(command, context);
    }

    @Override
    public boolean hasHandler(String domain, String commandType) {
        return handlers.containsKey(new HandlerKey(domain, commandType));
    }

    @Override
    public List<HandlerKey> registeredHandlers() {
        return List.copyOf(handlers.keySet());
    }

    @Override
    public void clear() {
        handlers.clear();
    }

    @Override
    public List<HandlerKey> registerBean(Object bean) {
        List<HandlerKey> registered = new ArrayList<>();

        for (Method method : bean.getClass().getMethods()) {
            Handler annotation = method.getAnnotation(Handler.class);
            if (annotation == null) {
                continue;
            }

            validateHandlerMethod(method);

            String domain = annotation.domain();
            String commandType = annotation.commandType();

            CommandHandler handler = (command, context) -> {
                return method.invoke(bean, command, context);
            };

            register(domain, commandType, handler);
            registered.add(new HandlerKey(domain, commandType));

            log.info("Discovered handler {}.{}() for {}.{}",
                bean.getClass().getSimpleName(), method.getName(), domain, commandType);
        }

        return registered;
    }

    /**
     * BeanPostProcessor callback - scans beans for @Handler methods.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        boolean hasHandlers = Arrays.stream(bean.getClass().getMethods())
            .anyMatch(m -> m.isAnnotationPresent(Handler.class));

        if (hasHandlers) {
            registerBean(bean);
        }

        return bean;
    }

    private void validateHandlerMethod(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 2 ||
            !params[0].equals(Command.class) ||
            !params[1].equals(HandlerContext.class)) {

            throw new IllegalArgumentException(
                "Handler method " + method.getName() + " must have signature: " +
                "Object methodName(Command command, HandlerContext context)"
            );
        }
    }
}
