package com.denizenscript.denizen;

import com.denizenscript.denizen.events.ScriptEventRegistry;
import com.denizenscript.denizen.events.bukkit.SavesReloadEvent;
import com.denizenscript.denizen.events.server.ServerPrestartScriptEvent;
import com.denizenscript.denizen.events.server.ServerStartScriptEvent;
import com.denizenscript.denizen.events.server.ServerStopScriptEvent;
import com.denizenscript.denizen.nms.NMSVersion;
import com.denizenscript.denizen.objects.InventoryTag;
import com.denizenscript.denizen.objects.NPCTag;
import com.denizenscript.denizen.objects.PlayerTag;
import com.denizenscript.denizen.objects.notable.NotableManager;
import com.denizenscript.denizen.objects.properties.PropertyRegistry;
import com.denizenscript.denizen.scripts.commands.BukkitCommandRegistry;
import com.denizenscript.denizen.scripts.commands.player.ClickableCommand;
import com.denizenscript.denizen.scripts.containers.ContainerRegistry;
import com.denizenscript.denizen.scripts.containers.core.*;
import com.denizenscript.denizen.scripts.triggers.TriggerRegistry;
import com.denizenscript.denizen.tags.BukkitTagContext;
import com.denizenscript.denizen.tags.core.NPCTagBase;
import com.denizenscript.denizen.tags.core.ServerTagBase;
import com.denizenscript.denizen.utilities.*;
import com.denizenscript.denizen.utilities.command.*;
import com.denizenscript.denizen.utilities.command.manager.CommandManager;
import com.denizenscript.denizen.utilities.command.manager.Injector;
import com.denizenscript.denizen.utilities.command.manager.messaging.Messaging;
import com.denizenscript.denizen.utilities.debugging.BStatsMetricsLite;
import com.denizenscript.denizen.utilities.debugging.StatsRecord;
import com.denizenscript.denizen.utilities.debugging.Debug;
import com.denizenscript.denizen.utilities.depends.Depends;
import com.denizenscript.denizen.utilities.entity.DenizenEntityType;
import com.denizenscript.denizen.utilities.flags.PlayerFlagHandler;
import com.denizenscript.denizen.utilities.flags.WorldFlagHandler;
import com.denizenscript.denizen.utilities.implementation.DenizenCoreImplementation;
import com.denizenscript.denizen.utilities.maps.DenizenMapManager;
import com.denizenscript.denizen.nms.NMSHandler;
import com.denizenscript.denizen.nms.interfaces.FakeArrow;
import com.denizenscript.denizen.nms.interfaces.FakePlayer;
import com.denizenscript.denizen.nms.interfaces.ItemProjectile;
import com.denizenscript.denizen.npc.TraitRegistry;
import com.denizenscript.denizen.npc.DenizenNPCHelper;
import com.denizenscript.denizen.utilities.packets.NetworkInterceptHelper;
import com.denizenscript.denizen.utilities.world.VoidGenerator;
import com.denizenscript.denizencore.DenizenCore;
import com.denizenscript.denizencore.events.OldEventManager;
import com.denizenscript.denizencore.flags.MapTagBasedFlagTracker;
import com.denizenscript.denizencore.flags.SavableMapFlagTracker;
import com.denizenscript.denizencore.objects.ObjectFetcher;
import com.denizenscript.denizencore.objects.core.TimeTag;
import com.denizenscript.denizencore.scripts.ScriptHelper;
import com.denizenscript.denizencore.scripts.ScriptRegistry;
import com.denizenscript.denizencore.scripts.commands.core.AdjustCommand;
import com.denizenscript.denizencore.scripts.commands.queue.RunLaterCommand;
import com.denizenscript.denizencore.tags.TagManager;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.debugging.SlowWarning;
import com.denizenscript.denizencore.utilities.debugging.StrongWarning;
import com.denizenscript.denizencore.utilities.text.ConfigUpdater;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.net.URLDecoder;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Denizen extends JavaPlugin {

    public static Denizen instance;

    public static Denizen getInstance() {
        return instance;
    }

    public static String versionTag = null;
    private boolean startedSuccessful = false;

    public static boolean supportsPaper = false;

    public CommandManager commandManager;

    public BukkitCommandRegistry commandRegistry;
    public TriggerRegistry triggerRegistry;
    public DenizenNPCHelper npcHelper;

    @Deprecated
    public BukkitCommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public TagManager tagManager;
    public NotableManager notableManager;
    public OldEventManager eventManager;

    public BukkitWorldScriptHelper worldScriptHelper;

    public ItemScriptHelper itemScriptHelper;

    public ExCommandHandler exCommand;

    public final static long startTime = System.currentTimeMillis();

    public DenizenCoreImplementation coreImplementation = new DenizenCoreImplementation();

    public SavableMapFlagTracker serverFlagMap;

    public long lastReloadTime;

    /*
     * Sets up Denizen on start of the CraftBukkit server.
     */
    @Override
    public void onEnable() {
        instance = this;
        try {
            versionTag = this.getDescription().getVersion();

            CoreUtilities.noDebugContext = new BukkitTagContext(null, null, null, false, null);
            CoreUtilities.basicContext = new BukkitTagContext(null, null, null, true, null);
            CoreUtilities.errorButNoDebugContext = new BukkitTagContext(null, null, null, false, null);
            CoreUtilities.errorButNoDebugContext.showErrors = true;
            // Load Denizen's core
            DenizenCore.init(coreImplementation);
        }
        catch (Exception e) {
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            startedSuccessful = false;
            return;
        }
        PlayerFlagHandler.dataFolder = new File(getDataFolder(), "player_flags");
        if (!PlayerFlagHandler.dataFolder.exists()) {
            PlayerFlagHandler.dataFolder.mkdir();
        }
        String javaVersion = System.getProperty("java.version");
        getLogger().info("Running on java version: " + javaVersion);
        if (javaVersion.startsWith("8") || javaVersion.startsWith("1.8")) {
            getLogger().info("Running on fully supported Java 8.");
        }
        else if (javaVersion.startsWith("11")) {
            getLogger().info("Running on mostly supported Java 11. Some warnings may show or limitations may apply due to Java 11. If these limits become problems, switch to Java 8 or Java 16.");
        }
        else if (javaVersion.startsWith("9") || javaVersion.startsWith("1.9") || javaVersion.startsWith("10") || javaVersion.startsWith("1.10")
                || javaVersion.startsWith("12") || javaVersion.startsWith("13") || javaVersion.startsWith("14") || javaVersion.startsWith("15")) {
            getLogger().warning("Running unreliable Java version. Old Minecraft is built for Java 8, modern Minecraft is built for Java 16. Other Java versions are not guaranteed to function properly.");
        }
        else {
            getLogger().info("Running on fully supported Java 16.");
        }
        if (!NMSHandler.initialize(this)) {
            getLogger().warning("-------------------------------------");
            getLogger().warning("This build of Denizen is not compatible with this Spigot version! Deactivating Denizen!");
            getLogger().warning("-------------------------------------");
            getServer().getPluginManager().disablePlugin(this);
            startedSuccessful = false;
            return;
        }
        if (!NMSHandler.getInstance().isCorrectMappingsCode()) {
            getLogger().warning("-------------------------------------");
            getLogger().warning("This build of Denizen was built for a different Spigot revision! This may potentially cause issues."
                    + " If you are experiencing trouble, update Denizen and Spigot both to latest builds!"
                    + " If this message appears with both Denizen and Spigot fully up-to-date, contact the Denizen team (via GitHub, Spigot, or Discord) to request an update be built.");
            getLogger().warning("-------------------------------------");
        }
        commandRegistry = new BukkitCommandRegistry();
        triggerRegistry = new TriggerRegistry();
        notableManager = new NotableManager();
        tagManager = new TagManager();
        boolean citizensBork = false;
        try {
            // Activate dependencies
            Depends.initialize();
            if (Depends.citizens == null) {
                if (Bukkit.getPluginManager().getPlugin("Citizens") != null) {
                    citizensBork = true;
                    getLogger().warning("Citizens is present but doesn't seem to be activated! You may have an error earlier in your logs, or you may have a broken plugin load order.");
                }
                else {
                    getLogger().warning("Citizens does not seem to be available! Denizen will have greatly reduced functionality!");
                }
            }
            startedSuccessful = true;
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            // Populate config.yml if it doesn't yet exist.
            saveDefaultConfig();
            reloadConfig();
            // Startup procedure
            Debug.log(ChatColor.LIGHT_PURPLE + "+-------------------------+");
            Debug.log(ChatColor.YELLOW + " _/_ _  ._  _ _  ");
            Debug.log(ChatColor.YELLOW + "(/(-/ )/ /_(-/ ) " + ChatColor.GRAY + " scriptable minecraft");
            Debug.log("");
            Debug.log(ChatColor.GRAY + "by: " + ChatColor.WHITE + "The DenizenScript team");
            Debug.log(ChatColor.GRAY + "Chat with us at: " + ChatColor.WHITE + " https://discord.gg/Q6pZGSR");
            Debug.log(ChatColor.GRAY + "Or learn more at: " + ChatColor.WHITE + " https://denizenscript.com");
            Debug.log(ChatColor.GRAY + "version: " + ChatColor.WHITE + versionTag);
            Debug.log(ChatColor.LIGHT_PURPLE + "+-------------------------+");
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            if (Class.forName("com.destroystokyo.paper.PaperConfig") != null) {
                supportsPaper = true;
            }
        }
        catch (ClassNotFoundException ex) {
            // Ignore.
        }
        catch (Throwable ex) {
            Debug.echoError(ex);
        }
        // bstats.org
        try {
            BStatsMetricsLite metrics = new BStatsMetricsLite(this);
        }
        catch (Throwable e) {
            Debug.echoError(e);
        }
        try {
            // If Citizens is enabled, Create the NPC Helper
            if (Depends.citizens != null) {
                npcHelper = new DenizenNPCHelper(this);
            }
            // Create our CommandManager to handle '/denizen' commands
            commandManager = new CommandManager();
            commandManager.setInjector(new Injector(this));
            commandManager.register(DenizenCommandHandler.class);
            // If Citizens is enabled, let it handle '/npc' commands
            if (Depends.citizens != null) {
                Depends.citizens.registerCommandClass(NPCCommandHandler.class);
            }
            DenizenEntityType.registerEntityType("ITEM_PROJECTILE", ItemProjectile.class);
            DenizenEntityType.registerEntityType("FAKE_ARROW", FakeArrow.class);
            DenizenEntityType.registerEntityType("FAKE_PLAYER", FakePlayer.class);
            // Track all player names for quick PlayerTag matching
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                PlayerTag.notePlayer(player);
            }
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            DenizenCore.setCommandRegistry(commandRegistry);
            commandRegistry.registerCommands();
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            // Register script-container types
            ScriptRegistry._registerCoreTypes();
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            ContainerRegistry.registerMainContainers();
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            // Ensure the Scripts and Midi folder exist
            new File(getDataFolder() + "/scripts").mkdirs();
            new File(getDataFolder() + "/midi").mkdirs();
            new File(getDataFolder() + "/schematics").mkdirs();
            // Ensure the example Denizen.mid sound file is available
            if (!new File(getDataFolder() + "/midi/Denizen.mid").exists()) {
                String sourceFile = URLDecoder.decode(Denizen.class.getProtectionDomain().getCodeSource().getLocation().getFile());
                Debug.log("Denizen.mid not found, extracting from " + sourceFile);
                Utilities.extractFile(new File(sourceFile), "Denizen.mid", getDataFolder() + "/midi/");
            }
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            // Automatic config file update
            InputStream properConfig = Denizen.class.getResourceAsStream("/config.yml");
            String properConfigString = ScriptHelper.convertStreamToString(properConfig);
            properConfig.close();
            FileInputStream currentConfig = new FileInputStream(getDataFolder() + "/config.yml");
            String currentConfigString = ScriptHelper.convertStreamToString(currentConfig);
            currentConfig.close();
            String updated = ConfigUpdater.updateConfig(currentConfigString, properConfigString);
            if (updated != null) {
                Debug.log("Your config file is outdated. Automatically updating it...");
                FileOutputStream configOutput = new FileOutputStream(getDataFolder() + "/config.yml");
                OutputStreamWriter writer = new OutputStreamWriter(configOutput);
                writer.write(updated);
                writer.close();
                configOutput.close();
                reloadConfig();
            }
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            worldScriptHelper = new BukkitWorldScriptHelper();
            itemScriptHelper = new ItemScriptHelper();
            new InventoryScriptHelper();
            new EntityScriptHelper();
            new CommandScriptHelper();
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            if (Depends.citizens != null) {
                // Register traits
                TraitRegistry.registerMainTraits();
            }
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        // Register Core Members in the Denizen Registries
        try {
            if (Depends.citizens != null) {
                triggerRegistry.registerCoreMembers();
            }
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            AdjustCommand.specialAdjustables.put("server", ServerTagBase::adjustServer);
            tagManager.registerCoreTags();
            CommonRegistries.registerMainTagHandlers();
            eventManager = new OldEventManager();
            // Register all the modern script events
            ScriptEventRegistry.registerMainEvents();
            // Register Core ObjectTags with the ObjectFetcher
            ObjectFetcher.registerCoreObjects();
            CommonRegistries.registerMainObjects();
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            // Initialize all properties
            PropertyRegistry.registerMainProperties();
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            new CommandEvents();
            if (Settings.cache_packetInterceptAutoInit) {
                NetworkInterceptHelper.enable();
            }
        }
        catch (Exception e) {
            Debug.echoError(e);
        }
        try {
            if (supportsPaper) {
                final Class<?> clazz = Class.forName("com.denizenscript.denizen.paper.PaperModule");
                clazz.getMethod("init").invoke(null);
            }
        }
        catch (ClassNotFoundException ex) {
            supportsPaper = false;
        }
        catch (Throwable ex) {
            supportsPaper = false;
            Debug.echoError(ex);
        }
        exCommand = new ExCommandHandler();
        exCommand.enableFor(getCommand("ex"));
        ExSustainedCommandHandler exsCommand = new ExSustainedCommandHandler();
        exsCommand.enableFor(getCommand("exs"));
        // Load script files without processing.
        DenizenCore.preloadScripts();
        // Load the saves.yml into memory
        reloadSaves();
        try {
            // Fire the 'on Server PreStart' world event
            ServerPrestartScriptEvent.instance.specialHackRunEvent();
        }
        catch (Throwable ex) {
            Debug.echoError(ex);
        }
        final boolean hadCitizensBork = citizensBork;
        // Run everything else on the first server tick
        Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
            try {
                if (hadCitizensBork) {
                    Depends.setupCitizens();
                    if (Depends.citizens != null) {
                        getLogger().warning("Citizens was activated late - this means a plugin load order error occurred. You may have plugins with invalid 'plugin.yml' files (eg that use the 'loadbefore' directive, or that have circular dependencies).");
                        npcHelper = new DenizenNPCHelper(this);
                        Depends.citizens.registerCommandClass(NPCCommandHandler.class);
                        TraitRegistry.registerMainTraits();
                        triggerRegistry.registerCoreMembers();
                        commandRegistry.registerCitizensCommands();
                        ScriptEventRegistry.registerCitizensEvents();
                        new NPCTagBase();
                        ObjectFetcher.registerWithObjectFetcher(NPCTag.class, NPCTag.tagProcessor);
                    }
                }
            }
            catch (Throwable ex) {
                Debug.echoError(ex);
            }
            try {
                exCommand.processTagList();
                // Reload notables from notables.yml into memory
                notableManager.reloadNotables();
                // Process script files (events, etc).
                DenizenCore.postLoadScripts();
                Debug.log(ChatColor.LIGHT_PURPLE + "+-------------------------+");
                // Fire the 'on Server Start' world event
                ServerStartScriptEvent.instance.fire();
                worldScriptHelper.serverStartEvent();
                if (Settings.allowStupidx()) {
                    Debug.echoError("Don't screw with bad config values.");
                    Bukkit.shutdown();
                }
                Bukkit.getScheduler().scheduleSyncRepeatingTask(Denizen.this, () -> {
                    Debug.outputThisTick = 0;
                    Debug.errorDuplicatePrevention = false;
                    DenizenCore.tick(50); // Sadly, minecraft has no delta timing, so a tick is always 50ms.
                }, 1, 1);
                InventoryTag.setupInventoryTracker();
                if (!MapTagBasedFlagTracker.skipAllCleanings && NMSHandler.getVersion().isAtLeast(NMSVersion.v1_16)) {
                    BukkitWorldScriptHelper.cleanAllWorldChunkFlags();
                }
                Debug.log("Denizen fully loaded at: " + TimeTag.now().format());
            }
            catch (Throwable ex) {
                Debug.echoError(ex);
            }
        }, 1);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (Settings.canRecordStats()) {
                    new StatsRecord().start();
                }
            }
        }.runTaskTimer(this, 100, 20 * 60 * 60);
        Bukkit.getPluginManager().registerEvents(new PlayerFlagHandler(), this);
        new BukkitRunnable() {
            @Override
            public void run() {
                PlayerFlagHandler.cleanCache();
            }
        }.runTaskTimer(this, 100, 20 * 60);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!StrongWarning.recentWarnings.isEmpty()) {
                    StringBuilder warnText = new StringBuilder();
                    warnText.append(ChatColor.YELLOW).append("[Denizen] ").append(ChatColor.RED).append("Recent strong system warnings, scripters need to address ASAP (check earlier console logs for details):");
                    for (StrongWarning warning : StrongWarning.recentWarnings) {
                        warnText.append("\n- ").append(warning.message);
                    }
                    StrongWarning.recentWarnings.clear();
                    Bukkit.getConsoleSender().sendMessage(warnText.toString());
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.isOp()) {
                            player.sendMessage(warnText.toString());
                        }
                    }
                }
            }
        }.runTaskTimer(this, 100, 20 * 60 * 5);
    }

    public boolean hasDisabled = false;

    /*
     * Unloads Denizen on shutdown of the server.
     */
    @Override
    public void onDisable() {
        if (!startedSuccessful) {
            return;
        }
        if (hasDisabled) {
            return;
        }
        hasDisabled = true;
        ServerStopScriptEvent.instance.fire();
        notableManager.saveNotables();
        ScoreboardHelper._saveScoreboards();
        InventoryScriptHelper._savePlayerInventories();
        commandRegistry.disableCoreMembers();
        triggerRegistry.disableCoreMembers();
        DenizenCore.logInterceptor.standardOutput();
        getLogger().log(Level.INFO, " v" + getDescription().getVersion() + " disabled.");
        Bukkit.getServer().getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        saveSaves(false);
        worldFlags.shutdown();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        Settings.refillCache();
        if (!Settings.showDebug()) {
            getLogger().warning("Debug is disabled in the Denizen config. This is almost always a mistake, and should not be done in the majority of cases.");
        }
        SlowWarning.WARNING_RATE = Settings.warningRate();
    }

    private FileConfiguration scoreboardsConfig = null;
    private File scoreboardsConfigFile = null;

    public WorldFlagHandler worldFlags;

    public void reloadSaves() {
        if (scoreboardsConfigFile == null) {
            scoreboardsConfigFile = new File(getDataFolder(), "scoreboards.yml");
        }
        scoreboardsConfig = YamlConfiguration.loadConfiguration(scoreboardsConfigFile);
        // Reload scoreboards from scoreboards.yml
        ScoreboardHelper._recallScoreboards();
        // Load maps from maps.yml
        DenizenMapManager.reloadMaps();
        // Reload server flags
        serverFlagMap = SavableMapFlagTracker.loadFlagFile(new File(getDataFolder(), "server_flags").getPath());
        if (worldFlags == null) {
            worldFlags = new WorldFlagHandler();
        }
        worldFlags.shutdown();
        worldFlags.init();
        RunLaterCommand.init(new File(getDataFolder(), "run_later.yml").getPath());
        if (new File(getDataFolder(), "saves.yml").exists()) {
            LegacySavesUpdater.updateLegacySaves();
        }
        Bukkit.getServer().getPluginManager().callEvent(new SavesReloadEvent());
    }

    public FileConfiguration getScoreboards() {
        if (scoreboardsConfig == null) {
            reloadSaves();
        }
        return scoreboardsConfig;
    }

    public void saveSaves(boolean canSleep) {
        // Save notables
        notableManager.saveNotables();
        // Save scoreboards to scoreboards.yml
        ScoreboardHelper._saveScoreboards();
        // Save maps to maps.yml
        DenizenMapManager.saveMaps();
        // Save server flags
        serverFlagMap.saveToFile(new File(getDataFolder(), "server_flags").getPath());
        try {
            scoreboardsConfig.save(scoreboardsConfigFile);
        }
        catch (IOException ex) {
            Logger.getLogger(JavaPlugin.class.getName()).log(Level.SEVERE, "Could not save to " + scoreboardsConfigFile, ex);
        }
        PlayerFlagHandler.saveAllNow(canSleep);
        worldFlags.saveAll();
        RunLaterCommand.saveToFile(canSleep);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equals("denizenclickable")) {
            if (args.length != 1 || !(sender instanceof Player)) {
                return false;
            }
            UUID id;
            try {
                id = UUID.fromString(args[0]);
            }
            catch (IllegalArgumentException ex) {
                return false;
            }
            ClickableCommand.runClickable(id, (Player) sender);
            return true;
        }
        String modifier = args.length > 0 ? args[0] : "";
        if (!commandManager.hasCommand(cmd, modifier) && !modifier.isEmpty()) {
            return suggestClosestModifier(sender, cmd.getName(), modifier);
        }

        Object[] methodArgs = {sender};
        return commandManager.executeSafe(cmd, args, sender, methodArgs);

    }

    private boolean suggestClosestModifier(CommandSender sender, String command, String modifier) {
        String closest = commandManager.getClosestCommandModifier(command, modifier);
        if (!closest.isEmpty()) {
            Messaging.send(sender, "<7>Unknown command. Did you mean:");
            Messaging.send(sender, " /" + command + " " + closest);
            return true;
        }
        return false;
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (NMSHandler.getVersion().isAtLeast(NMSVersion.v1_16) && CoreUtilities.toLowerCase(id).equals("void")) {
            return new VoidGenerator();
        }
        return null;
    }
}
