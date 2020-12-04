package com.redcreator37.WhitelistBot.Database.BotHandling;

import com.redcreator37.WhitelistBot.DataModels.CacheState;
import discord4j.common.util.Snowflake;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;

public class CachesDb {

    /**
     * The SQLite database connection to use for all database-related
     * operations
     */
    private final Connection con;

    /**
     * Constructs a new CachesDb instance
     *
     * @param connection connection to the SQLite database to use
     */
    public CachesDb(Connection connection) {
        this.con = connection;
    }

    /**
     * Returns the map of all cache refreshes per guild
     *
     * @return the map of all cache refreshes
     * @throws SQLException on errors
     */
    public HashMap<Snowflake, CacheState> getCacheState() throws SQLException {
        HashMap<Snowflake, CacheState> states = new HashMap<>();
        Statement st = con.createStatement();
        st.closeOnCompletion();
        ResultSet set = st.executeQuery("SELECT * FROM guilds");
        while (set.next()) {
            Snowflake s = Snowflake.of(set.getString("guild_id"));
            Instant i = Instant.parse(set.getString("last_refresh"));
            states.put(s, new CacheState(s, i));
        }
        set.close();
        return states;
    }

    /**
     * Logs this cache refresh in the database
     *
     * @param state the new cache state
     * @throws SQLException on errors
     */
    public void logRefresh(CacheState state) throws SQLException {
        PreparedStatement st = con.prepareStatement("INSERT INTO" +
                " caches(guild_id, last_refresh) VALUES(?, ?);");
        st.closeOnCompletion();
        st.setString(1, state.getGuildId().asString());
        st.setString(2, state.getLastRefresh().toString());
        st.executeUpdate();
    }

    /**
     * Removes the cache refresh data for the guild with this id from
     * the database
     *
     * @param guildId the guild to remove the data for
     * @throws SQLException on errors
     */
    public void clearCacheData(Snowflake guildId) throws SQLException {
        PreparedStatement st = con.prepareStatement("DELETE FROM caches"
                + " WHERE guild_id = ?;");
        st.setString(1, guildId.asString());
        st.executeUpdate();
    }

}
