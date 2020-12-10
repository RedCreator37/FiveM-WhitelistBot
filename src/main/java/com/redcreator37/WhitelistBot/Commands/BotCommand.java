package com.redcreator37.WhitelistBot.Commands;

import com.redcreator37.WhitelistBot.DataModels.Guild;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.rest.util.Color;
import reactor.core.publisher.Mono;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static com.redcreator37.WhitelistBot.Localizations.lc;

/**
 * Represents a command, executed by this bot
 */
public abstract class BotCommand {

    /**
     * The invocation word of the command
     */
    private final String name;

    /**
     * The description of the action, performed by this command
     */
    private final String description;

    /**
     * The {@link HashMap} of command arguments.
     * Keys are argument names, values toggle whether a specific
     * argument is required or not
     */
    private final HashMap<String, Boolean> arguments;

    /**
     * The name of the role, required to execute this command
     */
    private final String roleName;

    /**
     * Constructs a new BotCommand instance
     *
     * @param name        the invocation word of the command
     * @param description the description of the action performed by
     *                    the command
     * @param arguments   the {@link HashMap} of arguments, where the
     *                    boolean values signal whether specific
     *                    arguments are required or not. Can be
     *                    <code>null</code> if no arguments are required.
     * @param roleName    the name of the role, required to execute
     *                    this command
     */
    public BotCommand(String name, String description, HashMap<String, Boolean> arguments, String roleName) {
        this.name = name;
        this.description = description;
        this.arguments = arguments == null ? new HashMap<>() : arguments;
        this.roleName = roleName;
    }

    /**
     * Checks whether the member causing the {@link MessageCreateEvent}
     * has the permission to invoke the command.
     *
     * @param event the {@link MessageCreateEvent} which occurred when
     *              the message was sent
     * @return <code>true</code> if the user <strong>has</strong> the
     * permission, <code>false</code> otherwise
     */
    private boolean checkAllowed(MessageCreateEvent event) {
        if (!event.getMember().isPresent()) return false;
        else if (roleName == null) return true;
        boolean permission = CommandUtils.findRole(event.getMember().get(), roleName) != null;
        if (!permission) CommandUtils.getMessageChannel(event).createEmbed(spec -> {
            spec.setTitle(lc("permission-denied"));
            spec.setColor(Color.RED);
            spec.setAuthor(event.getMember().get().getUsername(), null, null);
            spec.addField(lc("no-permission-to-use-command"),
                    MessageFormat.format(lc("required-role"), roleName), false);
            spec.setTimestamp(Instant.now());
        }).block();
        return permission;
    }

    /**
     * Checks whether the member has the permissions to execute this
     * command and whether the number of entered arguments matches the
     * number of required arguments. This method then returns whether
     * all these requirements were met.
     *
     * @param enteredArgs the {@link List} of entered arguments
     * @param event       the {@link MessageCreateEvent} which occurred when
     *                    the message was sent
     * @return If the requirements are met,  <code>false</code>, otherwise
     * <code>false</code>.
     */
    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    public Mono<Boolean> checkValidity(List<String> enteredArgs, MessageCreateEvent event) {
        long countReq = arguments.values().stream().filter(req -> req).count();
        if (checkAllowed(event) || enteredArgs.size() >= countReq) return Mono.just(true);
        CommandUtils.getMessageChannel(event).createEmbed(spec -> {
            spec.setTitle("Syntax error");
            spec.setColor(Color.RED);
            StringBuilder args = new StringBuilder(100).append("`");
            arguments.forEach((argName, req) -> // fixme: localizations
                    args.append(MessageFormat.format(req ? "**<{0}>** " : "*[{0}]* ", argName)));
            spec.addField(MessageFormat.format("Usage of {0}", name),
                    args.append("`").toString(), false);
            spec.setDescription(description);
            CommandUtils.setSelfAuthor(event.getGuild(), spec);
            spec.setTimestamp(Instant.now());
        }).block();
        return Mono.just(false);
    }

    /**
     * Runs the action for this command
     *
     * @param args    the command arguments entered, can be <code>null</code>
     *                if none are required
     * @param context the {@link Guild} context in which to run the
     *                command. Can be <code>null</code> if no guild is
     *                tied to the command's working.
     * @param event   the {@link MessageCreateEvent} which occurred
     *                when the message was sent
     * @return an empty {@link Mono} object
     */
    public abstract Mono<Void> execute(List<String> args, Guild context, MessageCreateEvent event);

    /**
     * Returns the name of this {@link BotCommand}
     *
     * @return the name of the command, by which it can be executed
     */
    protected String getName() {
        return name;
    }
}