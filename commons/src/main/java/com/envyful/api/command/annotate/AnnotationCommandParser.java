package com.envyful.api.command.annotate;

import com.envyful.api.command.CommandFactory;
import com.envyful.api.command.CommandParser;
import com.envyful.api.command.PlatformCommand;
import com.envyful.api.command.PlatformCommandExecutor;
import com.envyful.api.command.annotate.description.Description;
import com.envyful.api.command.annotate.description.DescriptionHandler;
import com.envyful.api.command.annotate.executor.*;
import com.envyful.api.command.annotate.permission.Permissible;
import com.envyful.api.command.annotate.permission.PermissionHandler;
import com.envyful.api.command.exception.CommandParseException;
import com.envyful.api.command.injector.ArgumentInjector;
import com.envyful.api.command.injector.TabCompleter;
import com.envyful.api.command.sender.SenderType;
import com.envyful.api.command.sender.SenderTypeFactory;
import com.envyful.api.command.tab.TabHandler;
import com.envyful.api.concurrency.UtilConcurrency;
import com.envyful.api.concurrency.UtilLogger;
import com.google.common.collect.Lists;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

public class AnnotationCommandParser<A extends PlatformCommand<B>, B> implements CommandParser<A, B> {

    protected final CommandFactory<?, B> commandFactory;
    protected final Class<B> senderClass;

    protected AnnotationCommandParser(CommandFactory<?, B> commandFactory, Class<B> senderClass) {
        this.commandFactory = commandFactory;
        this.senderClass = senderClass;
    }

    @SuppressWarnings("unchecked")
    @Override
    public A parseCommand(Object o) throws CommandParseException {
        Command commandData = this.getCommandData(o);
        BiPredicate<B, List<String>> permissionCheck = this.getPermissionCheck(o);
        BiFunction<B, List<String>, List<String>> descriptionProvider = this.getDescriptionProvider(o);
        PlatformCommandExecutor<B> commandExecutor = this.getCommandExecutor(o);
        List<PlatformCommand<B>> subCommands = this.getSubCommands(o);
        TabHandler<B> tabHandler = this.getTabHandler(o);


        return (A) this.commandFactory.commandBuilder()
                .name(commandData.value()[0])
                .aliases(Lists.newArrayList(commandData.value()))
                .permissionCheck(permissionCheck)
                .descriptionProvider(descriptionProvider)
                .noPermissionProvider(b -> Collections.singletonList("&c&l(!) &cYou do not have permission to use this command!"))
                .executor(commandExecutor)
                .subCommands(subCommands)
                .tabHandler(tabHandler)
                .build();
    }

    protected Command getCommandData(Object o) {
        Command annotation = o.getClass().getAnnotation(Command.class);

        if (annotation == null) {
            throw new CommandParseException("Class " + o.getClass().getName() + " is not annotated with @Command");
        }

        if (annotation.value().length == 0) {
            throw new CommandParseException("Class " + o.getClass().getName() + " has no aliases");
        }

        return annotation;
    }

    protected BiPredicate<B, List<String>> getPermissionCheck(Object o) {
        Permissible permissible = o.getClass().getAnnotation(Permissible.class);

        if (permissible != null) {
            return (sender, args) -> this.commandFactory.hasPermission(sender, permissible.value());
        }

        for (Method declaredMethod : o.getClass().getDeclaredMethods()) {
            PermissionHandler annotation = declaredMethod.getAnnotation(PermissionHandler.class);

            if (annotation == null) {
                continue;
            }

            if (!this.isPermissionHandlerMethod(declaredMethod)) {
                throw new CommandParseException("Method " + declaredMethod.getName() + " in class " + o.getClass().getName() + " is not a valid permission handler method");
            }

            return (sender, args) -> {
                try {
                    return (boolean) declaredMethod.invoke(o, sender, args);
                } catch (Exception e) {
                    UtilLogger.logger().ifPresent(logger -> logger.error("Error occurred when performing permission check for command " + o.getClass().getSimpleName(), e));
                }

                return false;
            };
        }

        return null;
    }

    protected boolean isPermissionHandlerMethod(Method method) {
        if (method.getParameterCount() != 2) {
            return false;
        }

        return method.getParameterTypes()[0].isAssignableFrom(this.senderClass)
                && method.getParameterTypes()[1].isAssignableFrom(List.class);
    }

    @SuppressWarnings("unchecked")
    protected BiFunction<B, List<String>, List<String>> getDescriptionProvider(Object o) {
        Description description = o.getClass().getAnnotation(Description.class);

        if (description != null) {
            return (sender, args) -> Lists.newArrayList(description.value());
        }

        for (Method declaredMethod : o.getClass().getDeclaredMethods()) {
            DescriptionHandler annotation = declaredMethod.getAnnotation(DescriptionHandler.class);

            if (annotation == null) {
                continue;
            }

            if (!this.isPermissionHandlerMethod(declaredMethod)) {
                throw new CommandParseException("Method " + declaredMethod.getName() + " in class " + o.getClass().getName() + " is not a valid description handler method");
            }

            return (sender, args) -> {
                try {
                    return (List<String>) declaredMethod.invoke(o, sender, args);
                } catch (Exception e) {
                    UtilLogger.logger().ifPresent(logger -> logger.error("Error occurred when performing description handling for command " + o.getClass().getSimpleName(), e));
                }

                return Collections.emptyList();
            };
        }

        return null;
    }

    protected PlatformCommandExecutor<B> getCommandExecutor(Object commandObject) {
        Method commandProcessor = this.findCommandProcessor(commandObject);

        if (commandProcessor == null) {
            return null;
        }

        CommandProcessor annotation = commandProcessor.getAnnotation(CommandProcessor.class);
        Annotation[][] parameterAnnotations = this.getProcessorAnnotations(commandObject, commandProcessor);
        Class<?>[] parameterTypes = commandProcessor.getParameterTypes();

        if (this.isInvalidSenderAnnotation(parameterAnnotations)) {
            throw new CommandParseException("The first parameter must always be annotated with @Sender, and is missing or invalid in method " + commandProcessor.getName() + " in class " + commandObject.getClass().getName());
        }

        SenderType<B, ?> senderType = SenderTypeFactory.<B, Object, SenderType<B, Object>>getSenderType(parameterTypes[0]).orElse(null);

        if (senderType == null) {
            throw new CommandParseException("Unrecognized sender type used in method " + commandProcessor.getName() + " in class " + commandObject.getClass().getName());
        }

        boolean argsCapture = this.shouldCaptureArgs(commandObject, commandProcessor, parameterAnnotations, parameterTypes);
        List<AnnotationPlatformCommandExecutor.Argument<B>> arguments = this.buildArguments(commandObject, commandProcessor, parameterAnnotations, parameterTypes, argsCapture);

        return AnnotationPlatformCommandExecutor.builder(senderType)
                .instance(commandObject)
                .method(commandProcessor)
                .argsCapture(argsCapture)
                .async(annotation.executeAsync())
                .arguments(arguments)
                .build();
    }

    private Method findCommandProcessor(Object o) {
        for (Method declaredMethod : o.getClass().getDeclaredMethods()) {
            CommandProcessor commandProcessor = declaredMethod.getAnnotation(CommandProcessor.class);

            if (commandProcessor == null) {
                continue;
            }

            if (!Modifier.isPublic(declaredMethod.getModifiers())) {
                throw new CommandParseException("Method " + declaredMethod.getName() + " in class " + o.getClass().getName() + " is flagged as a command processor but is not public");
            }

            return declaredMethod;
        }

        return null;
    }

    private Annotation[][] getProcessorAnnotations(Object commandObject, Method method) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();

        if (parameterAnnotations.length == 0) {
            throw new CommandParseException("Missing annotations in method " + method.getName() + " in class " + commandObject.getClass().getName());
        }

        return parameterAnnotations;
    }

    protected boolean shouldCaptureArgs(Object commandInstance, Method method, Annotation[][] parameterAnnotations, Class<?>[] parameterTypes) {
        for (int i = 1; i < parameterTypes.length; ++i) {
            Annotation[] annotations = parameterAnnotations[i];

            if (annotations.length != 0) {
                continue;
            }

            if (!parameterTypes[i].equals(String[].class)) {
                throw new CommandParseException("Missing annotation for parameter " + i + " in method " + method.getName() + " in class " + commandInstance.getClass().getName());
            }

            if (i != (parameterTypes.length - 1)) {
                throw new CommandParseException("Remaining args capture parameter must be the last parameter in method which it is not for " + method.getName() + " in class " + commandInstance.getClass().getName());
            }

            return true;
        }

        return false;
    }

    protected List<AnnotationPlatformCommandExecutor.Argument<B>> buildArguments(Object commandInstance, Method method, Annotation[][] parameterAnnotations, Class<?>[] parameterTypes, boolean captureArgs) {
        List<AnnotationPlatformCommandExecutor.Argument<B>> arguments = Lists.newArrayList();

        for (int i = 1; i < parameterTypes.length - (captureArgs ? 1 : 0); ++i) {
            ArgumentInjector<?, B> registeredInjector = this.commandFactory.getRegisteredInjector(parameterTypes[i]);

            if (registeredInjector == null) {
                throw new CommandParseException("Invalid parameter type found " + parameterTypes[i].getName() + " in method " + method.getName() + " in class " + commandInstance.getClass().getName());
            }

            Argument argumentAnnotation = this.getArgumentAnnotation(parameterAnnotations[i]);

            if (argumentAnnotation == null) {
                throw new CommandParseException("Missing Argument annotation found for parameter " + i + " in method " + method.getName() + " in class " + commandInstance.getClass().getName());
            }

            arguments.add(new AnnotationPlatformCommandExecutor.Argument<>(registeredInjector, argumentAnnotation.defaultValue()));
        }

        return arguments;
    }

    protected Argument getArgumentAnnotation(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Argument) {
                return (Argument) annotation;
            }
        }

        return null;
    }

    private boolean isInvalidSenderAnnotation(Annotation[][] annotations) {
        if (annotations.length < 1) {
            return true;
        }

        if (annotations[0].length != 1) {
            return true;
        }

        return !(annotations[0][0] instanceof Sender);
    }

    protected List<PlatformCommand<B>> getSubCommands(Object commandInstance) {
        SubCommands subCommands = commandInstance.getClass().getAnnotation(SubCommands.class);

        if (subCommands == null) {
            return Collections.emptyList();
        }

        List<PlatformCommand<B>> platformCommands = Lists.newArrayList();

        for (Class<?> subCommandClass : subCommands.value()) {
            Object instance = getSubCommandInstance(subCommandClass);
            platformCommands.add(this.parseCommand(instance));
        }

        return platformCommands;
    }

    protected Object getSubCommandInstance(Class<?> subCommandClass) {
        try {
            Constructor<?> constructor = subCommandClass.getConstructor();
            return constructor.newInstance();
        } catch (Exception e) {
            throw new CommandParseException("No public constructor with no parameters found for sub command class " + subCommandClass.getName());
        }
    }

    protected TabHandler<B> getTabHandler(Object commandInstance) {
        Method commandProcessor = this.findCommandProcessor(commandInstance);
        boolean argCapture = this.shouldCaptureArgs(commandInstance, commandProcessor, commandProcessor.getParameterAnnotations(), commandProcessor.getParameterTypes());
        boolean[] hasTabCompleter = this.getHasTabCompleter(commandProcessor, argCapture);
        List<TabCompleteAnnotations> tabCompleter = this.getParameterTabCompleters(commandInstance, commandProcessor, hasTabCompleter);
        Method tabHandlerMethod = this.findTabHandlerMethod(commandInstance);

        return (sender, args) -> {
            int currentPosition = Math.max(0, args.length - 1);

            if (currentPosition < hasTabCompleter.length && hasTabCompleter[currentPosition]) {
                TabCompleteAnnotations data = tabCompleter.get(currentPosition);

                return CompletableFuture.supplyAsync(() ->
                                        data.completer.getCompletions(sender, args, data.annotations.toArray(new Annotation[0])),
                                UtilConcurrency.SCHEDULED_EXECUTOR_SERVICE)
                        .exceptionally(throwable -> {
                            UtilLogger.logger().ifPresent(logger -> logger.error("Error when handling tab completions", throwable));
                            return Lists.newArrayList();
                        });
            }

            if (tabHandlerMethod == null) {
                return CompletableFuture.completedFuture(Lists.newArrayList());
            }

            return CompletableFuture.supplyAsync(() -> {
                        try {
                            return (List<String>) tabHandlerMethod.invoke(commandInstance, sender, args);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            throw new RuntimeException("Error when executing tab handler method " + tabHandlerMethod.getName() + " in class " + commandInstance.getClass().getName(), e);
                        }
                    }, UtilConcurrency.SCHEDULED_EXECUTOR_SERVICE)
                    .exceptionally(throwable -> {
                        UtilLogger.logger().ifPresent(logger -> logger.error("Error when handling tab completions", throwable));
                        return Lists.newArrayList();
                    });
        };
    }

    protected boolean[] getHasTabCompleter(Method commandProcessor, boolean argsCapture) {
        Annotation[][] parameterAnnotations = commandProcessor.getParameterAnnotations();
        int parameterOffset = - 1 - (argsCapture ? 1 : 0);
        boolean[] hasTabCompleter = new boolean[parameterAnnotations.length - parameterOffset];

        for (int i = 1; i < parameterAnnotations.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof Completable) {
                    hasTabCompleter[i - 1] = true;
                    break;
                }
            }
        }

        return hasTabCompleter;
    }

    protected List<TabCompleteAnnotations> getParameterTabCompleters(Object commandInstance, Method commandProcessor, boolean[] hasTabCompleter) {
        Annotation[][] parameterAnnotations = commandProcessor.getParameterAnnotations();
        List<TabCompleteAnnotations> tabCompleters = Lists.newArrayList();

        for (int i = 1; i < parameterAnnotations.length; i++) {
            if (hasTabCompleter[i - 1]) {
                List<Annotation> annotations = Lists.newArrayList();
                TabCompleter<B> completable = null;

                for (Annotation annotation : parameterAnnotations[i]) {
                    if (!(annotation instanceof Completable)) {
                        annotations.add(annotation);
                        continue;
                    }

                    completable = this.getCompleterInstance(commandInstance, commandProcessor, (Completable) annotation);
                }

                tabCompleters.add(new TabCompleteAnnotations(completable, annotations));
            } else {
                tabCompleters.add(null);
            }
        }

        return tabCompleters;
    }

    @SuppressWarnings("unchecked")
    protected TabCompleter<B> getCompleterInstance(Object commandInstance, Method commandProcessor, Completable completable) {
        try {
            Constructor<? extends TabCompleter<?>> constructor = completable.value().getConstructor();
            return (TabCompleter<B>) constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new CommandParseException("Error creating tab completer instance for command " + commandInstance.getClass().getName() + " in method " + commandProcessor.getName(), e);
        }
    }

    protected Method findTabHandlerMethod(Object commandInstance) {
        for (Method declaredMethod : commandInstance.getClass().getDeclaredMethods()) {
            CompletionHandler completionHandler = declaredMethod.getAnnotation(CompletionHandler.class);

            if (completionHandler == null) {
                continue;
            }

            if (!Modifier.isPublic(declaredMethod.getModifiers())) {
                throw new CommandParseException("Method with CompletionHandler annotation is not public for method " + declaredMethod.getName() + " in " + commandInstance.getClass().getName());
            }

            return declaredMethod;
        }

        return null;
    }

    public class TabCompleteAnnotations {

        protected final TabCompleter<B> completer;
        protected final List<Annotation> annotations;

        public TabCompleteAnnotations(TabCompleter<B> completer, List<Annotation> annotations) {
            this.completer = completer;
            this.annotations = annotations;
        }
    }
}
