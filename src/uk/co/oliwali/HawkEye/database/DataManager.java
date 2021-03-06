package uk.co.oliwali.HawkEye.database;

import org.bukkit.Bukkit;
import uk.co.oliwali.HawkEye.HawkEye;
import uk.co.oliwali.HawkEye.entry.DataEntry;
import uk.co.oliwali.HawkEye.util.Config;
import uk.co.oliwali.HawkEye.util.Util;

import java.sql.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Handler for everything to do with the database.
 * All queries except searching goes through this class.
 *
 * @author oliverw92
 */

public class DataManager implements Runnable, AutoCloseable {

    private static final LinkedBlockingQueue<DataEntry> queue = new LinkedBlockingQueue<>();
    private static ConnectionManager connectionManager;

    private static final IdMapCache playerDb = new IdMapCache();
    private static final IdMapCache worldDb = new IdMapCache();

    private static DeleteManager deleteManager = new DeleteManager();

    private boolean threadbusy = false;

    /**
     * Initiates database connection pool, checks tables, starts cleansing utility
     * Throws an exception if it is unable to complete setup
     *
     * @param instance
     * @throws Exception
     */
    public DataManager(HawkEye instance) throws Exception {

        connectionManager = new ConnectionManager();

        //Check tables and update player/world lists
        if (!checkTables())
            throw new Exception();

        if (!updateDbLists())
            throw new Exception();

        Bukkit.getScheduler().runTaskTimerAsynchronously(instance, deleteManager, 20 * 15, 20 * 5);

        //Start cleansing utility
        try {
            new CleanseUtil(instance);
        } catch (Exception e) {
            Util.severe(e.getMessage());
            Util.severe("Unable to start cleansing utility - check your cleanse age");
        }
    }

    public static DeleteManager getDeleteManager() {
        return deleteManager;
    }

    /**
     * Returns current queue
     */
    public static LinkedBlockingQueue<DataEntry> getQueue() {
        return queue;
    }

    /**
     * Closes down all connections
     */
    public void close() throws Exception {
        if (connectionManager != null) {

            while (!queue.isEmpty()) {
                threadbusy = false;
                run();
            }

            connectionManager.close();
        }
    }

    /**
     * Adds a {@link DataEntry} to the database queue.
     * {Rule}s are checked at this point
     *
     * @param entry {@link DataEntry} to be added
     * @return
     */
    public static void addEntry(DataEntry entry) {

        if (!entry.getType().isLogged()) return;

        if (Config.IgnoreWorlds.contains(entry.getWorld())) return;

        queue.add(entry);
    }

    /**
     * Get the player cache
     */
    public static IdMapCache getPlayerDb() {
        return playerDb;
    }

    /**
     * Get the world cache
     */
    public static IdMapCache getWorldDb() {
        return worldDb;
    }

    /**
     * Returns a database connection from the pool
     *
     * @return {JDCConnection}
     */
    public static Connection getConnection() throws SQLException {
        return connectionManager.getConnection();
    }


    /**
     * Adds an identifier to the database and updates the provided map
     */
    private boolean addKey(String table, String column, IdMapCache cache, String value) {
        Util.debug("Attempting to add " + column + " '" + value + "' to database");

        String sql = "INSERT INTO `" + table + "` (" + column + ") VALUES (?) ON DUPLICATE KEY UPDATE " + column + "=VALUES(" + column + ");";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, value);

            stmt.executeUpdate();

            conn.commit();

            try (ResultSet rs = stmt.getGeneratedKeys()) {

                if (rs.next())
                    cache.put(rs.getInt(1), value);

            }
        } catch (SQLException ex) {
            Util.severe("Unable to add " + column + " to database: " + ex);
            return false;
        }
        return true;
    }

    /**
     * Updates world and player local lists
     *
     * @return true on success, false on failure
     */
    private boolean updateDbLists() {
        try (Connection conn = getConnection();
             Statement stmnt = conn.createStatement()) {

            try (ResultSet res = stmnt.executeQuery("SELECT * FROM `" + Config.DbPlayerTable + "`;")) {
                while (res.next())
                    playerDb.put(res.getInt("player_id"), res.getString("player"));
            }

            try (ResultSet res = stmnt.executeQuery("SELECT * FROM `" + Config.DbWorldTable + "`;")) {
                while (res.next())
                    worldDb.put(res.getInt("world_id"), res.getString("world"));
            }

        } catch (SQLException ex) {
            Util.severe("Unable to update local data lists from database: " + ex);
            return false;
        }
        return true;
    }

    /**
     * Updates a table based on params - Only use on mass changes
     */
    private void updateTables(String table, String columns, Statement stmnt, String sql) {
        try {
            stmnt.execute(sql);//This is where you create the table - use new + tablename!
            stmnt.execute("INSERT INTO `new" + table + "` (" + columns + ") SELECT " + columns + " FROM `" + table + "`;");
            stmnt.execute("RENAME TABLE `" + table + "` TO `old" + table + "`, `new" + table + "` TO `" + table + "`;");
            stmnt.execute("DROP TABLE `old" + table + "`;");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Checks that all tables are up to date and exist
     *
     * @return true on success, false on failure
     */
    private boolean checkTables() {

        try (Connection conn = getConnection();
             Statement stmnt = conn.createStatement()) {

            String playerTable = "CREATE TABLE IF NOT EXISTS `" + Config.DbPlayerTable + "` (" +
                    "`player_id` SMALLINT(6) UNSIGNED NOT NULL AUTO_INCREMENT, " +
                    "`player` varchar(40) CHARACTER SET latin1 COLLATE latin1_general_ci NOT NULL, " +
                    "PRIMARY KEY (`player_id`), " +
                    "UNIQUE KEY `player` (`player`)" +
                    ") COLLATE latin1_general_ci, ENGINE = INNODB;";

            String worldTable = "CREATE TABLE IF NOT EXISTS `" + Config.DbWorldTable + "` (" +
                    "`world_id` TINYINT(3) UNSIGNED NOT NULL AUTO_INCREMENT, " +
                    "`world` varchar(40) CHARACTER SET latin1 COLLATE latin1_general_ci NOT NULL, " +
                    "PRIMARY KEY (`world_id`), " +
                    "UNIQUE KEY `world` (`world`)" +
                    ") COLLATE latin1_general_ci, ENGINE = INNODB;";

            String dataTable = "CREATE TABLE `" + Config.DbHawkEyeTable + "` (" +
                    "`data_id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT," +
                    "`timestamp` datetime NOT NULL," +
                    "`player_id` SMALLINT(6) UNSIGNED NOT NULL," +
                    "`action` TINYINT(3) UNSIGNED NOT NULL," +
                    "`world_id` TINYINT(3) UNSIGNED NOT NULL," +
                    "`x` int(11) NOT NULL," +
                    "`y` int(11) NOT NULL," +
                    "`z` int(11) NOT NULL," +
                    "`data` varchar(500) CHARACTER SET latin1 COLLATE latin1_general_ci DEFAULT NULL," +
                    "PRIMARY KEY (`data_id`)," +
                    "KEY `timestamp` (`timestamp`)," +
                    "KEY `player` (`player_id`)," +
                    "KEY `action` (`action`)," +
                    "KEY `world_id` (`world_id`)," +
                    "KEY `x_y_z` (`x`,`y`,`z`)" +
                    ") COLLATE latin1_general_ci, ENGINE = INNODB;";

            DatabaseMetaData dbm = conn.getMetaData();

            //Check if tables exist
            if (!JDBCUtil.tableExists(dbm, Config.DbPlayerTable)) {
                Util.info("Table `" + Config.DbPlayerTable + "` not found, creating...");
                stmnt.execute(playerTable);
            }

            if (!JDBCUtil.tableExists(dbm, Config.DbWorldTable)) {
                Util.info("Table `" + Config.DbWorldTable + "` not found, creating...");
                stmnt.execute(worldTable);
            }

            if (!JDBCUtil.tableExists(dbm, Config.DbHawkEyeTable)) {
                Util.info("Table `" + Config.DbHawkEyeTable + "` not found, creating...");
                stmnt.execute(dataTable);
            }

            //This will print an error if the user does not have SUPER privilege
            try {
                stmnt.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 2");
                stmnt.execute("SET GLOBAL sync_binlog = 0");
            } catch (Exception e) {
                Util.debug("HawkEye does not have enough privileges for setting global settings");
            }

            //Here is were the table alterations take place (Aside from alters from making tables)

            ResultSet rs = stmnt.executeQuery("SHOW FIELDS FROM `" + Config.DbHawkEyeTable + "` where Field ='action'");

            //Older hawkeye versions x = double, and contains the column "plugin"
            if (rs.next() && !rs.getString(2).contains("tinyint") || JDBCUtil.columnExists(dbm, Config.DbHawkEyeTable, "plugin")) {

                Util.info("Updating " + Config.DbPlayerTable + "...");

                updateTables(Config.DbPlayerTable, "`player_id`,`player`", stmnt, playerTable.replace(Config.DbPlayerTable, "new" + Config.DbPlayerTable));


                Util.info("Updating " + Config.DbWorldTable + "...");

                updateTables(Config.DbWorldTable, "`world_id`,`world`", stmnt, worldTable.replace(Config.DbWorldTable, "new" + Config.DbWorldTable));

                Util.info("Updating " + Config.DbHawkEyeTable + "...");

                updateTables(Config.DbHawkEyeTable, "`data_id`,`timestamp`,`player_id`,`action`,`world_id`,`x`,`y`,`z`,`data`", stmnt,
                        dataTable.replace(Config.DbHawkEyeTable, "new" + Config.DbHawkEyeTable));

                Util.info("Finished!");

            }

            conn.commit();

        } catch (SQLException ex) {
            Util.severe("Error checking HawkEye tables: " + ex);
            return false;
        }

        return true;
    }

    /**
     * Empty the {@link DataEntry} queue into the database
     */
    @Override
    public void run() {
        if (threadbusy || queue.isEmpty()) return;

        threadbusy = true;

        if (queue.size() > 70000)
            Util.info("HawkEye can't keep up! Current Queue: " + queue.size());

        try (Connection conn = getConnection();
             PreparedStatement stmnt = conn.prepareStatement("INSERT IGNORE into `" + Config.DbHawkEyeTable + "` (timestamp, player_id, action, world_id, x, y, z, data, data_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

            for (int i = 0; i < queue.size(); i++) {
                DataEntry entry = queue.poll();

                if (!playerDb.containsKey(entry.getPlayer()) && !addKey(Config.DbPlayerTable, "player", playerDb, entry.getPlayer())) {
                    Util.debug("Player '" + entry.getPlayer() + "' not found, skipping entry");
                    continue;
                }
                if (!worldDb.containsKey(entry.getWorld()) && !addKey(Config.DbWorldTable, "world", worldDb, entry.getWorld())) {
                    Util.debug("World '" + entry.getWorld() + "' not found, skipping entry");
                    continue;
                }

                Integer player = playerDb.get(entry.getPlayer());

                //If player ID is unable to be found, continue
                if (player == null) {
                    Util.debug("No player found, skipping entry");
                    continue;
                }

                stmnt.setTimestamp(1, entry.getTimestamp());
                stmnt.setInt(2, player);
                stmnt.setInt(3, entry.getType().getId());
                stmnt.setInt(4, worldDb.get(entry.getWorld()));
                stmnt.setDouble(5, entry.getX());
                stmnt.setDouble(6, entry.getY());
                stmnt.setDouble(7, entry.getZ());
                stmnt.setString(8, entry.getSqlData());

                if (entry.getDataId() > 0) stmnt.setInt(9, entry.getDataId());
                else stmnt.setInt(9, 0); //0 is better then setting it to null, like before

                stmnt.addBatch();

                if (i % 1000 == 0) stmnt.executeBatch(); //If the batchsize is divisible by 1000, execute!
            }

            stmnt.executeBatch();

            conn.commit();

        } catch (Exception ex) {
            Util.warning(ex.getMessage());
            ex.printStackTrace();
        } finally {
            threadbusy = false;
        }
    }

    public boolean isInsertThreadBusy() {
        return threadbusy;
    }

}
