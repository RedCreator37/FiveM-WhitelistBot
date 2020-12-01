package com.redcreator37.WhitelistBot;

import com.redcreator37.WhitelistBot.Database.FiveMDb;
import com.redcreator37.WhitelistBot.Database.GuildsDb;
import com.redcreator37.WhitelistBot.Database.LocalDb;
import com.redcreator37.WhitelistBot.Database.SharedDb;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.event.domain.guild.GuildDeleteEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Discord Bot that aims to simplify server management for
 * a variety of different games
 *
 * @author RedCreator37
 */
public class DiscordBot {

    /**
     * The prefix with which the command can be executed
     */
    private static final char cmdPrefix = '%';

    /**
     * The ISO 8601-compliant date format used for storing dates
     * in the database
     */
    public static final String dateFormat = "yyyy-MM-dd";

    /**
     * The currently used gateway client
     */
    private static GatewayDiscordClient client = null;

    /**
     * The path to the SQLite database file
     */
    private static final String localDbPath = "data.db";

    /**
     * The connection to the local SQLite database
     */
    private static Connection localDb = null;

    /**
     * The shared database the game uses
     */
    public static FiveMDb fiveMDb = null;

    /**
     * A map storing all supported commands
     */
    private static final Map<String, Command> commands = new HashMap<>();

    /**
     * A list of all guilds
     */
    static List<Guild> guilds = new ArrayList<>();

    /**
     * A list of all whitelisted players per guild
     */
    static List<WhitelistedPlayer> whitelisted;

    /**
     * The current Guilds database instance
     */
    private static GuildsDb guildsDb = null;

    /**
     * Initializes the commands
     */
    private static void setUpCommands() {
        commands.put("list", e -> Mono.just(CommandHandlers.listWhitelisted(e)).then());
        commands.put("whitelist", e -> Mono.justOrEmpty(e.getMessage().getContent())
                .map(cnt -> Arrays.asList(cnt.split(" ")))
                .doOnNext(cmd -> CommandHandlers.whitelistPlayer(cmd, e)).then());
        commands.put("unlist", e -> Mono.justOrEmpty(e.getMessage().getContent())
                .map(cnt -> Arrays.asList(cnt.split(" ")))
                .doOnNext(cmd -> CommandHandlers.unlistPlayer(cmd, e)).then());
    }

    /**
     * Initializes and hooks up the event handlers
     */
    private static void setUpEventDispatcher() {
        client.getEventDispatcher().on(MessageCreateEvent.class)
                .flatMap(e -> Mono.just(e.getMessage().getContent())
                        .flatMap(content -> Flux.fromIterable(commands.entrySet())
                                .filter(entry -> content.startsWith(cmdPrefix + entry.getKey()))
                                .flatMap(entry -> entry.getValue().execute(e))
                                .next()))
                .subscribe();
        client.getEventDispatcher().on(GuildCreateEvent.class)
                .flatMap(e -> Mono.just(e.getGuild())
                        .flatMap(guild -> {
                            String timeStamp = new SimpleDateFormat(dateFormat)
                                    .format(Calendar.getInstance().getTime());
                            Guild newGuild = new Guild(0, guild.getId(),
                                    timeStamp, null, localDb);
                            guilds.add(newGuild);
                            try {
                                guildsDb.addGuild(newGuild);
                                System.out.println("Registered guild "
                                        + guild.getId().asString() + " to the database");
                            } catch (SQLException ex) {
                                System.err.println("Warning! Adding the guild failed: "
                                        + ex.getMessage());
                            }
                            return Mono.empty();
                        })).subscribe();
        client.getEventDispatcher().on(GuildDeleteEvent.class)
                .flatMap(e -> Mono.justOrEmpty(e.getGuild())
                        .flatMap(guild -> Mono.just(Guild.guildBySnowflake(e.getGuildId(), guilds)))
                        .flatMap(g -> {
                            guilds.remove(g);
                            try {
                                guildsDb.removeGuild(g);
                                System.out.println("Unregistered guild "
                                        + g.getSnowflake().asString() + " from the database");
                            } catch (SQLException ex) {
                                System.err.println("Warning! Removing the guild failed: "
                                        + ex.getMessage());
                            }
                            return Mono.empty();
                        })).subscribe();
    }

    /**
     * Sets up the database connection
     */
    private static void setUpDatabase() {
        boolean success = true, isNew = !new File(localDbPath).exists();
        try {
            localDb = LocalDb.connect(localDbPath);
            guildsDb = new GuildsDb(localDb);
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }

        if (isNew) // create a new database
            try {
                LocalDb.createDatabaseTables(localDb);
                System.out.println("Created an empty database");
            } catch (SQLException e) {
                System.err.println("Error while creating the database:" + e.getMessage());
                success = false;
            }

        try {
            guilds = guildsDb.getGuilds();
            // todo: use local storage
            whitelisted = fiveMDb.getWhitelistedPlayers();
            System.out.println("Database loaded successfully");
        } catch (SQLException e) {
            System.err.println("Error while reading from the database: " + e.getMessage());
            success = false;
        }

        if (!success) {
            System.err.println("FATAL: Unable to establish the database connection");
            System.exit(1);
        }
    }

    /**
     * Program entry point, starts the bot
     */
    public static void main(String[] args) {
        String password = "";
        if (args.length < 3) {
            System.err.println("Syntax: bot_token mysql_url username password");
            System.exit(1);
        } else if (args.length == 4) {  // to allow blank passwords
            password = args[3];
        }

        try {
            fiveMDb = new FiveMDb(SharedDb.connect(args[1], args[2], password));
        } catch (SQLException e) {
            System.err.println("Unable to establish the database connection: " + e.getMessage());
        }
        setUpCommands();
        setUpDatabase();

        client = DiscordClientBuilder.create(args[0]).build().login().block();
        if (client == null) {
            System.err.println("Error: Login failed");
            System.exit(1);
        }
        setUpEventDispatcher();
        // close the database connection on shutdown
        client.onDisconnect().filter(unused -> {
            try {
                localDb.close();
            } catch (SQLException e) {
                System.err.println("Warning! Closing the database connection"
                        + " failed: " + e.getMessage());
            }
            return true;
        }).block();
    }

}
