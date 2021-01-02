/*
 * Copyright © 2020 FlashyReese
 *
 * This file is part of CommandAliases.
 *
 * Licensed under the MIT license. For more information,
 * see the LICENSE file.
 */

package me.flashyreese.mods.commandaliases.command.builders;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.flashyreese.mods.commandaliases.CommandAliasesMod;
import me.flashyreese.mods.commandaliases.classtool.FormattingTypeMap;
import me.flashyreese.mods.commandaliases.classtool.impl.argument.ArgumentTypeManager;
import me.flashyreese.mods.commandaliases.command.CommandAction;
import me.flashyreese.mods.commandaliases.command.CommandChild;
import me.flashyreese.mods.commandaliases.command.CommandParent;
import me.flashyreese.mods.commandaliases.command.CommandType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Represents the Command Builder
 * <p>
 * Used to build a LiteralArgumentBuilder
 *
 * @author FlashyReese
 * @version 0.4.0
 * @since 0.4.0
 */
public class CommandBuilder {
    private final CommandParent commandAliasParent;

    private final ArgumentTypeManager argumentTypeManager = new ArgumentTypeManager();
    private final FormattingTypeMap formattingTypeMap = new FormattingTypeMap();

    public CommandBuilder(CommandParent commandAliasParent) {
        this.commandAliasParent = commandAliasParent;
    }

    /**
     * Builds Command for Dispatcher to register.
     *
     * @param dispatcher The command dispatcher
     * @return ArgumentBuilder
     */
    public LiteralArgumentBuilder<ServerCommandSource> buildCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        return this.buildCommandParent(dispatcher);
    }

    /**
     * Builds parent ArgumentBuilder
     *
     * @param dispatcher The command dispatcher
     * @return ArgumentBuilder
     */
    private LiteralArgumentBuilder<ServerCommandSource> buildCommandParent(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> argumentBuilder = CommandManager.literal(this.commandAliasParent.getParent());
        if (this.commandAliasParent.getPermission() < 0 && this.commandAliasParent.getPermission() >= 4) {
            argumentBuilder = argumentBuilder.requires(serverCommandSource -> serverCommandSource.hasPermissionLevel(this.commandAliasParent.getPermission()));
        }

        if (this.commandAliasParent.isOptional()) {
            argumentBuilder = argumentBuilder.executes(context -> {
                //Execution action here
                return this.executeAction(this.commandAliasParent.getActions(), this.commandAliasParent.getMessage(), dispatcher, context, new Object2ObjectOpenHashMap<>());
            });
        }
        if (this.commandAliasParent.getChildren() != null && !this.commandAliasParent.getChildren().isEmpty()) {
            for (CommandChild child : this.commandAliasParent.getChildren()) {
                ArgumentBuilder<ServerCommandSource, ?> subArgumentBuilder = this.buildCommandChild(child, dispatcher, new Object2ObjectOpenHashMap<>());
                if (subArgumentBuilder != null) {
                    argumentBuilder = argumentBuilder.then(subArgumentBuilder);
                }
            }
        }
        return argumentBuilder;
    }

    /**
     * Builds child commands and determine if optional or not.
     *
     * @param child      CommandChild
     * @param dispatcher The command dispatcher
     * @param input      User input map
     * @return ArgumentBuilder
     */
    private ArgumentBuilder<ServerCommandSource, ?> buildCommandChild(CommandChild child, CommandDispatcher<ServerCommandSource> dispatcher, Map<String, BiFunction<CommandContext<ServerCommandSource>, String, String>> input) {
        ArgumentBuilder<ServerCommandSource, ?> argumentBuilder = null;
        if (child.getType().equals("literal")) {
            argumentBuilder = CommandManager.literal(child.getChild());
        } else if (child.getType().equals("argument")) {
            if (this.argumentTypeManager.contains(child.getArgumentType())) {
                argumentBuilder = CommandManager.argument(child.getChild(), this.argumentTypeManager.getValue(child.getArgumentType()).getArgumentType());
                input.put(child.getChild(), this.argumentTypeManager.getValue(child.getArgumentType()).getBiFunction());
            } else {
                CommandAliasesMod.getLogger().warn("Invalid Argument Type: {}", child.getArgumentType());
            }
        }
        if (argumentBuilder != null) {
            // Assign permission
            if (child.getPermission() < 0 && child.getPermission() >= 4) {
                argumentBuilder = argumentBuilder.requires(serverCommandSource -> serverCommandSource.hasPermissionLevel(child.getPermission()));
            }

            if (child.isOptional()) {
                argumentBuilder = argumentBuilder.executes(context -> {
                    //Execution action here
                    return this.executeAction(child.getActions(), child.getMessage(), dispatcher, context, input);
                });
            }
            //Start building children if exist
            if (child.getChildren() != null && !child.getChildren().isEmpty()) {
                for (CommandChild subChild : child.getChildren()) {
                    ArgumentBuilder<ServerCommandSource, ?> subArgumentBuilder = this.buildCommandChild(subChild, dispatcher, new Object2ObjectOpenHashMap<>(input));
                    argumentBuilder = argumentBuilder.then(subArgumentBuilder);
                }
            }
        }
        return argumentBuilder;
    }

    /**
     * Executes command action
     *
     * @param actions         List of command actions
     * @param message         Message
     * @param dispatcher      The command dispatcher
     * @param context         The command context
     * @param currentInputMap User input map with functions
     * @return Command execution state
     */
    private int executeAction(List<CommandAction> actions, String message, CommandDispatcher<ServerCommandSource> dispatcher, CommandContext<ServerCommandSource> context, Map<String, BiFunction<CommandContext<ServerCommandSource>, String, String>> currentInputMap) {
        if ((actions == null || actions.isEmpty()) && (message != null || !message.isEmpty())) {
            String formatString = this.formatString(context, currentInputMap, message);
            context.getSource().sendFeedback(new LiteralText(formatString), true);
            return Command.SINGLE_SUCCESS;
        } else if ((actions != null || !actions.isEmpty()) && (message == null || message.isEmpty())) {
            return this.executeCommand(actions, dispatcher, context, currentInputMap);
        } else {
            int state = this.executeCommand(actions, dispatcher, context, currentInputMap);
            String formatString = this.formatString(context, currentInputMap, message);
            context.getSource().sendFeedback(new LiteralText(formatString), true);
            return state;
        }
    }

    /**
     * Executes command in command action
     *
     * @param actions         List of command actions
     * @param dispatcher      The command dispatcher
     * @param context         The command context
     * @param currentInputMap User input map with functions
     * @return Command execution state
     */
    private int executeCommand(List<CommandAction> actions, CommandDispatcher<ServerCommandSource> dispatcher, CommandContext<ServerCommandSource> context, Map<String, BiFunction<CommandContext<ServerCommandSource>, String, String>> currentInputMap) {
        AtomicInteger executeState = new AtomicInteger();
        Thread thread = new Thread(() -> {
            try {
                if (actions != null) {
                    for (CommandAction action : actions) {
                        if (action.getCommand() != null) {
                            String actionCommand = this.formatString(context, currentInputMap, action.getCommand());
                            if (action.getCommandType() == CommandType.CLIENT) {
                                executeState.set(dispatcher.execute(actionCommand, context.getSource()));
                            } else if (action.getCommandType() == CommandType.SERVER) {
                                executeState.set(dispatcher.execute(actionCommand, context.getSource().getMinecraftServer().getCommandSource()));
                            }
                        }
                        if (action.getMessage() != null) {
                            String message = this.formatString(context, currentInputMap, action.getMessage());
                            context.getSource().sendFeedback(new LiteralText(message), true);
                        }
                        if (action.getSleep() != null) {
                            String formattedTime = this.formatString(context, currentInputMap, action.getSleep());
                            int time = Integer.parseInt(formattedTime);
                            Thread.sleep(time);
                        }
                    }
                }
            } catch (CommandSyntaxException | InterruptedException e) {
                e.printStackTrace();
                String output = e.getLocalizedMessage();
                context.getSource().sendFeedback(new LiteralText(output), true);
            }
        });
        thread.setName("Command Aliases");
        thread.start();
        return executeState.get();
    }

    /**
     * Method to format string(command or messages) with user input map.
     *
     * @param context         The command context
     * @param currentInputMap User input map with functions
     * @param string          Input string
     * @return Formatted string
     */
    private String formatString(CommandContext<ServerCommandSource> context, Map<String, BiFunction<CommandContext<ServerCommandSource>, String, String>> currentInputMap, String string) {
        Map<String, String> resolvedInputMap = new Object2ObjectOpenHashMap<>();
        currentInputMap.forEach((key, value) -> resolvedInputMap.put(key, value.apply(context, key)));
        //Functions fixme: more hardcoding
        string = string.replace("$executor_name()", context.getSource().getName());
        //Input Map
        for (Map.Entry<String, String> entry : resolvedInputMap.entrySet()) { //fixme: A bit of hardcoding here
            string = string.replace(String.format("{{%s}}", entry.getKey()), entry.getValue());

            for (Map.Entry<String, Function<String, String>> entry2: this.formattingTypeMap.getFormatTypeMap().entrySet()){
                String tempString = String.format("{{%s@%s}}", entry.getKey(), entry2.getKey());
                if (string.contains(tempString)){
                    String newString = entry2.getValue().apply(entry.getValue());
                    string = string.replace(tempString, newString);
                }
            }
        }

        string = string.trim();
        return string;
    }
}