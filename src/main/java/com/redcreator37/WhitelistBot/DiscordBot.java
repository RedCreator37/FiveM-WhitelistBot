package com.redcreator37.WhitelistBot;

import com.redcreator37.WhitelistBot.BackgroundTasks.DataAutoSave;
import com.redcreator37.WhitelistBot.Commands.BotCommand;
import com.redcreator37.WhitelistBot.Commands.BotCommands.EmbedAdminData;
import com.redcreator37.WhitelistBot.Commands.BotCommands.EmbedDatabaseData;
import com.redcreator37.WhitelistBot.Commands.BotCommands.LeaveGuild;
import com.redcreator37.WhitelistBot.Commands.BotCommands.ListWhitelisted;
import com.redcreator37.WhitelistBot.Commands.BotCommands.SetAdmin;
import com.redcreator37.WhitelistBot.Commands.BotCommands.SetDatabase;
import com.redcreator37.WhitelistBot.Commands.BotCommands.UnlistPlayer;
import com.redcreator37.WhitelistBot.Commands.BotCommands.WhitelistPlayer;
import com.redcreator37.WhitelistBot.Commands.Command;
import com.redcreator37.WhitelistBot.Commands.CommandUtils;
import com.redcreator37.WhitelistBot.DataModels.Guild;
import com.redcreator37.WhitelistBot.Database.BotHandling.DbInstances;
import com.redcreator37.WhitelistBot.Database.BotHandling.GuildsDb;
import com.redcreator37.WhitelistBot.Database.BotHandling.LocalDb;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.redcreator37.WhitelistBot.Localizations.lc;

/**
 * A Discord bot that aims to simplify server management for FiveM-based
 * game servers
 *
 * @author RedCreator37
 */
public class DiscordBot {

    /**
     * The prefix to look for when parsing messages into commands
     */
    public static final char cmdPrefix = '-';

    /**
     * The currently used {@link GatewayDiscordClient} object when
     * connecting to Discord's servers
     */
    private static GatewayDiscordClient client = null;

    /**
     * The connection to the local SQLite database
     */
    private static Connection localDb = null;

    /**
     * A {@link HashMap} holding all currently implemented commands
     */
    private static final Map<String, Command> commands = new HashMap<>();

    /**
     * A {@link HashMap} of all registered guilds
     */
    public static HashMap<Snowflake, Guild> guilds = new HashMap<>();

    /**
     * The currently used local database support object
     */
    private static GuildsDb guildsDb = null;

    /**
     * The currently used external database support object
     */
    private static DbInstances instancesDb = null;

    /**
     * Registers this {@link C command} into the global {@link MessageCreateEvent}
     * event dispatcher
     *
     * @param cmd         the name of the command as well as the action
     *                    word by which the command is executed
     * @param parseParams set to <code>true</code> if this command accepts
     *                    parameters
     * @param command     the {@link C command object}, which gets executed
     * @param <C>         the command's implementation class
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private static <C extends BotCommand> void registerCommand(String cmd, boolean parseParams, C command) {
        if (parseParams)
            commands.put(cmd, e -> Mono.justOrEmpty(e.getMessage().getContent())
                    .map(cnt -> Arrays.asList(cnt.split(" ")))
                    .doOnNext(params -> Mono.justOrEmpty(guilds.get(e.getGuildId().get()))
                            .flatMap(guild -> command.execute(params, guild, e)).block()).then());
        else commands.put(cmd, e -> Mono.just(guilds.get(e.getGuildId().get()))
                .flatMap(guild -> command.execute(null, guild, e).then()));
    }

    /**
     * Registers the bot commands
     */
    private static void setUpCommands() {
        registerCommand("list", false, new ListWhitelisted());
        registerCommand("whitelist", true, new WhitelistPlayer());
        registerCommand("unlist", true, new UnlistPlayer());
        registerCommand("getadmin", false, new EmbedAdminData());
        registerCommand("setadmin", true, new SetAdmin());
        registerCommand("getdatabase", false, new EmbedDatabaseData());
        registerCommand("setdatabase", true, new SetDatabase());
        registerCommand("kickbot", false, new LeaveGuild());
    }

    /**
     * Adds this {@link Guild} to the local database and sends its
     * owner the welcome message
     *
     * @param guild the {@link Guild} to add
     * @param event the {@link GuildCreateEvent} which occurred when the
     *              guild was registered
     * @return the status message
     */
    private static Mono<String> addGuild(Guild guild, GuildCreateEvent event) {
        try {
            guildsDb.addGuild(guild);
            guilds.put(guild.getSnowflake(), guild);
            CommandUtils.sendWelcome(event.getGuild());
            return Mono.just(MessageFormat.format(lc("registered-guild"),
                    guild.getSnowflake().asString()));
        } catch (SQLException ex) {
            return Mono.just(MessageFormat.format(lc("warn-guild-add-failed"),
                    ex.getMessage()));
        }
    }

    /**
     * Removes this {@link Guild} from the local database
     *
     * @param guild the {@link Guild} to remove
     * @return the status message
     */
    public static Mono<Boolean> removeGuild(Guild guild) {
        try {
            guildsDb.removeGuild(guild);
            guilds.remove(guild.getSnowflake());
            System.out.println(MessageFormat.format(lc("unregistered-guild"),
                    guild.getSnowflake().asString()));
            return Mono.just(true);
        } catch (SQLException ex) {
            System.err.println(MessageFormat.format(lc("warn-guild-remove-failed"),
                    ex.getMessage()));
            return Mono.just(false);
        }
    }

    /**
     * Initializes and hooks up the event handlers
     */
    private static void setUpEventDispatcher() {
        client.getEventDispatcher().on(MessageCreateEvent.class)
                .flatMap(e -> Mono.just(e.getMessage().getContent())
                        .flatMap(content -> Flux.fromIterable(commands.entrySet())
                                .filter(entry -> content.startsWith(cmdPrefix + entry.getKey()))
                                .flatMap(entry -> entry.getValue().execute(e)).next()))
                .subscribe();
        client.getEventDispatcher().on(GuildCreateEvent.class)
                .flatMap(e -> Mono.just(e.getGuild())
                        .flatMap(guild -> Mono.just(new Guild(guild.getId(), Instant.now())))
                        .flatMap(guild -> {
                            if (guilds.get(guild.getSnowflake()) != null)
                                return Mono.empty();
                            return Mono.just(addGuild(guild, e));
                        }))
                .subscribe(System.out::println);
    }

    /**
     * Sets up the local database connection
     */
    private static void setUpDatabase() {
        boolean success = true, isNew = !new File("bot.db").exists();
        try {
            localDb = LocalDb.connect("bot.db");
            guildsDb = new GuildsDb(localDb);
            instancesDb = new DbInstances(localDb);
        } catch (SQLException e) {
            System.err.println(MessageFormat.format(lc("error-format"), e.getMessage()));
        }

        if (isNew) try {
            new LocalDb().createDatabaseTables(localDb, DiscordBot.class
                    .getClassLoader().getResourceAsStream("GenerateDb.sql"));
            System.out.println(lc("created-empty-db"));
        } catch (SQLException | IOException e) {
            System.err.println(MessageFormat.format(lc("error-creating-db"),
                    e.getMessage()));
            success = false;
        }

        try {
            guilds = guildsDb.getGuilds();
            guilds.values().forEach(guild -> {
                try {
                    if (guild.getSharedDbProvider() != null)
                        guild.connectSharedDb();
                } catch (SQLException e) {
                    System.err.println(MessageFormat.format(lc("connecting-failed-for-guild"),
                            guild.getSnowflake().toString(), e.getMessage()));
                }
            });
            System.out.println(lc("db-loaded-success"));
        } catch (SQLException e) {
            System.err.println(MessageFormat.format(lc("error-reading-db"),
                    e.getMessage()));
            success = false;
        }

        if (!success) {
            System.err.println(lc("fatal-db-connect-failed"));
            System.exit(1);
        }
    }

    /**
     * Sets up multi-threaded background tasks
     */
    private static void setUpBackgroundTasks() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                new DataAutoSave(instancesDb, guildsDb), 0, 15, TimeUnit.MINUTES);
    }

    /**
     * Starts up the bot, loads the local database and connects to the
     * Discord's API
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println(lc("provide-token"));
            System.exit(1);
        }

        setUpCommands();
        setUpDatabase();

        client = DiscordClientBuilder.create(args[0]).build().login().block();
        if (client == null) {
            System.err.println(lc("login-failed"));
            System.exit(1);
        }
        setUpEventDispatcher();
        setUpBackgroundTasks();
        // close the database connection on shutdown
        client.onDisconnect().filter(unused -> {
            try {
                new DataAutoSave(instancesDb, guildsDb).run();   // trigger manual data save
                localDb.close();
            } catch (SQLException e) {
                System.err.println(MessageFormat.format(lc("warn-db-close-failed"),
                        e.getMessage()));
            }
            return true;
        }).block();
    }

}
