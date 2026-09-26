package mikey.me.staffsystem;

import mikey.me.staffsystem.commands.*;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.listeners.AltBanListener;
import mikey.me.staffsystem.listeners.ChatListener;
import mikey.me.staffsystem.listeners.CommandPreprocessListener;
import mikey.me.staffsystem.listeners.FreezeDamageListener;
import mikey.me.staffsystem.listeners.InteractionListener;
import mikey.me.staffsystem.listeners.JoinQuitListener;
import mikey.me.staffsystem.listeners.PlayerIPLogListener;
import mikey.me.staffsystem.listeners.MoveListener;
import mikey.me.staffsystem.listeners.PlayerInfoListener;
import mikey.me.staffsystem.listeners.ReportsGuiListener;
import mikey.me.staffsystem.listeners.StaffModeToolListener;
import mikey.me.staffsystem.listeners.StaffModeProtectionListener;
import mikey.me.staffsystem.listeners.StaffModeSilentOpenListener;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.managers.InspectManager;
import mikey.me.staffsystem.managers.NotesManager;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.managers.ReportsGuiManager;
import mikey.me.staffsystem.managers.StaffChatManager;
import mikey.me.staffsystem.managers.StaffModeManager;
import mikey.me.staffsystem.managers.TeleportManager;
import mikey.me.staffsystem.managers.VanishManager;
import mikey.me.staffsystem.managers.ReportManager;
import mikey.me.staffsystem.managers.IPManager;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.staffsystem.packets.PacketService;
import mikey.me.staffsystem.packets.PaperPacketService;
import mikey.me.staffsystem.placeholder.StaffPlaceholderExpansion;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.StringJoiner;

public class StaffSystemPlugin extends JavaPlugin {

    private ConfigurationManager configurationManager;
    private SchedulerProvider schedulerProvider;
    private DatabaseManager databaseManager;
    private PacketService packetService;
    private TextUtil textUtil;
    private VelocityMessenger velocityMessenger;
    private VanishManager vanishManager;
    private FreezeManager freezeManager;
    private StaffModeManager staffModeManager;
    private NotesManager notesManager;
    private PunishmentManager punishmentManager;
    private StaffChatManager staffChatManager;
    private TeleportManager teleportManager;
    private InspectManager inspectManager;
    private ReportManager reportManager;
    private ReportsGuiManager reportsGuiManager;
    private IPManager ipManager;
    private NetworkPlayerResolver networkPlayerResolver;
    private Listener unavailableGuard;

    @Override
    public void onEnable() {
        if (!initializeCore()) return;
        initializeManagers();
        freezeManager.initialize().exceptionally(error -> {
            getLogger().severe("Failed to restore active freezes: " + error.getMessage());
            installUnavailableGuard();
            return null;
        });
        registerPlaceholderExpansion();
        registerListeners();
        registerCommands();
        applyCommandPermissions();
    }

    @Override
    public void onDisable() {
        if (velocityMessenger != null) velocityMessenger.unregister();
        shutdownManagers();
        shutdownDatabase();
    }

    public NetworkPlayerResolver getNetworkPlayerResolver() {
        return networkPlayerResolver;
    }

    private boolean initializeCore() {
        this.configurationManager = new ConfigurationManager(this);
        this.schedulerProvider = new SchedulerProvider(this);
        this.textUtil = new TextUtil(configurationManager.getMessages());
        DatabaseManager manager = null;
        try {
            manager = new DatabaseManager(this, configurationManager);
            manager.initialize();
            this.databaseManager = manager;
        } catch (Exception e) {
            if (manager != null) manager.shutdown();
            getLogger().severe("Failed to connect to MySQL: " + e.getMessage());
            installUnavailableGuard();
            return false;
        }
        this.packetService = new PaperPacketService(this);
        this.velocityMessenger = new VelocityMessenger(this, configurationManager.getSettings());
        this.velocityMessenger.register();
        this.velocityMessenger.setRelayDatabase(manager);
        getLogger().info("Network mode: "
                + configurationManager.getSettings().getCommunicationMode().name().toLowerCase(java.util.Locale.ROOT));
        return true;
    }

    private void installUnavailableGuard() {
        if (unavailableGuard != null) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getGlobalRegionScheduler().execute(this, this::installUnavailableGuard);
            return;
        }
        unavailableGuard = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onPreLogin(AsyncPlayerPreLoginEvent event) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "Can't check bans right now, try again in a sec.");
            }
        };
        Bukkit.getPluginManager().registerEvents(unavailableGuard, this);
    }

    private void initializeManagers() {
        this.networkPlayerResolver = new NetworkPlayerResolver(databaseManager, velocityMessenger);
        this.vanishManager = new VanishManager(configurationManager, schedulerProvider, packetService,
                databaseManager.createVanishLogRepository(), velocityMessenger);
        this.freezeManager = new FreezeManager(configurationManager, schedulerProvider,
                databaseManager.createFreezeLogRepository(), textUtil, velocityMessenger, networkPlayerResolver);
        this.staffModeManager = new StaffModeManager(configurationManager, schedulerProvider,
                databaseManager.createStaffSessionRepository());
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            staffModeManager.recover(player);
        }
        this.notesManager = new NotesManager(databaseManager.createPlayerNoteRepository());
        this.punishmentManager = new PunishmentManager(databaseManager.createPunishmentLogRepository(),
                schedulerProvider, textUtil, velocityMessenger, configurationManager.getSettings(), networkPlayerResolver);
        this.staffChatManager = new StaffChatManager(configurationManager, textUtil, velocityMessenger, schedulerProvider);
        this.teleportManager = new TeleportManager(configurationManager);
        this.reportManager = new ReportManager(databaseManager.createPlayerReportRepository());
        this.ipManager = new IPManager(configurationManager, databaseManager.createPlayerIPLogRepository(),
                databaseManager.createLoginLogRepository());
        this.inspectManager = new InspectManager(punishmentManager, freezeManager, reportManager, ipManager,
                schedulerProvider, textUtil, configurationManager.getSettings());
        if (!configurationManager.getSettings().isProxyless()) {
            schedulerProvider.runSyncTimer(networkPlayerResolver::requestNetworkPlayerList, 40L, 100L);
            schedulerProvider.runSyncTimer(velocityMessenger::flush, 60L, 60L);
            schedulerProvider.runSyncTimer(velocityMessenger::sendHello, 200L, 1200L);
        }
        this.reportsGuiManager = new ReportsGuiManager(reportManager, schedulerProvider, textUtil);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new JoinQuitListener(vanishManager, freezeManager,
                configurationManager.getSettings(), punishmentManager, staffModeManager,
                velocityMessenger, inspectManager, reportsGuiManager, staffChatManager), this);
        Bukkit.getPluginManager().registerEvents(new MoveListener(freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new InteractionListener(freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new FreezeDamageListener(freezeManager), this);
        Bukkit.getPluginManager().registerEvents(new ChatListener(freezeManager, staffChatManager, textUtil,
                configurationManager.getSettings(), punishmentManager, this), this);
        Bukkit.getPluginManager().registerEvents(
                new CommandPreprocessListener(freezeManager, punishmentManager, textUtil, configurationManager.getSettings()), this);
        Bukkit.getPluginManager().registerEvents(new StaffModeToolListener(staffModeManager, teleportManager,
                freezeManager, inspectManager, configurationManager.getSettings(), textUtil), this);
        Bukkit.getPluginManager().registerEvents(new StaffModeProtectionListener(staffModeManager), this);
        Bukkit.getPluginManager().registerEvents(new StaffModeSilentOpenListener(staffModeManager, this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerInfoListener(inspectManager), this);
        Bukkit.getPluginManager().registerEvents(new ReportsGuiListener(reportsGuiManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerIPLogListener(ipManager), this);
        Bukkit.getPluginManager().registerEvents(
                new AltBanListener(punishmentManager, databaseManager.createPlayerIPLogRepository(), textUtil,
                        configurationManager.getSettings(), this, networkPlayerResolver), this);
    }

    private void registerCommands() {
        if (getCommand("staffmode") != null) {
            StaffModeCommand staffModeCommand = new StaffModeCommand(configurationManager, staffModeManager, vanishManager, textUtil);
            getCommand("staffmode").setExecutor(staffModeCommand);
            getCommand("staffmode").setTabCompleter(staffModeCommand);
        }
        if (getCommand("vanish") != null) {
            VanishCommand vanishCommand = new VanishCommand(configurationManager, vanishManager, textUtil);
            getCommand("vanish").setExecutor(vanishCommand);
            getCommand("vanish").setTabCompleter(vanishCommand);
        }
        if (getCommand("vanishlist") != null) {
            VanishListCommand vanishListCommand = new VanishListCommand(configurationManager, vanishManager, textUtil);
            getCommand("vanishlist").setExecutor(vanishListCommand);
            getCommand("vanishlist").setTabCompleter(vanishListCommand);
        }
        if (getCommand("freeze") != null) {
            FreezeCommand freezeCommand = new FreezeCommand(configurationManager, freezeManager, textUtil);
            getCommand("freeze").setExecutor(freezeCommand);
            getCommand("freeze").setTabCompleter(freezeCommand);
        }
        if (getCommand("unfreeze") != null) {
            UnfreezeCommand unfreezeCommand = new UnfreezeCommand(configurationManager, freezeManager, textUtil);
            getCommand("unfreeze").setExecutor(unfreezeCommand);
            getCommand("unfreeze").setTabCompleter(unfreezeCommand);
        }
        if (getCommand("freezelist") != null) {
            FreezeListCommand freezeListCommand = new FreezeListCommand(configurationManager, freezeManager, textUtil,
                    networkPlayerResolver);
            getCommand("freezelist").setExecutor(freezeListCommand);
            getCommand("freezelist").setTabCompleter(freezeListCommand);
        }
        if (getCommand("invsee") != null) {
            InvseeCommand invseeCommand = new InvseeCommand(configurationManager, inspectManager, textUtil);
            getCommand("invsee").setExecutor(invseeCommand);
            getCommand("invsee").setTabCompleter(invseeCommand);
        }
        if (getCommand("ecsee") != null) {
            EcseeCommand ecseeCommand = new EcseeCommand(configurationManager, inspectManager, textUtil);
            getCommand("ecsee").setExecutor(ecseeCommand);
            getCommand("ecsee").setTabCompleter(ecseeCommand);
        }
        if (getCommand("inspect") != null) {
            InspectCommand inspectCommand = new InspectCommand(configurationManager, inspectManager, textUtil);
            getCommand("inspect").setExecutor(inspectCommand);
            getCommand("inspect").setTabCompleter(inspectCommand);
        }
        if (getCommand("note") != null) {
            NoteCommand noteCommand = new NoteCommand(configurationManager, notesManager, textUtil, this);
            getCommand("note").setExecutor(noteCommand);
            getCommand("note").setTabCompleter(noteCommand);
        }
        if (getCommand("notes") != null) {
            NotesCommand notesCommand = new NotesCommand(configurationManager, notesManager, textUtil, this, networkPlayerResolver);
            getCommand("notes").setExecutor(notesCommand);
            getCommand("notes").setTabCompleter(notesCommand);
        }
        if (getCommand("staffchat") != null) {
            StaffChatCommand staffChatCommand = new StaffChatCommand(configurationManager, staffChatManager, textUtil);
            getCommand("staffchat").setExecutor(staffChatCommand);
            getCommand("staffchat").setTabCompleter(staffChatCommand);
        }
        if (getCommand("ban") != null) {
            BanCommand banCommand = new BanCommand(configurationManager, punishmentManager, textUtil,
                    networkPlayerResolver, schedulerProvider);
            getCommand("ban").setExecutor(banCommand);
            getCommand("ban").setTabCompleter(banCommand);
        }
        if (getCommand("unban") != null) {
            UnbanCommand unbanCommand = new UnbanCommand(configurationManager, punishmentManager, textUtil,
                    networkPlayerResolver, schedulerProvider);
            getCommand("unban").setExecutor(unbanCommand);
            getCommand("unban").setTabCompleter(unbanCommand);
        }
        if (getCommand("mute") != null) {
            MuteCommand muteCommand = new MuteCommand(configurationManager, punishmentManager, textUtil,
                    networkPlayerResolver, schedulerProvider);
            getCommand("mute").setExecutor(muteCommand);
            getCommand("mute").setTabCompleter(muteCommand);
        }
        if (getCommand("unmute") != null) {
            UnmuteCommand unmuteCommand = new UnmuteCommand(configurationManager, punishmentManager, textUtil,
                    networkPlayerResolver, schedulerProvider);
            getCommand("unmute").setExecutor(unmuteCommand);
            getCommand("unmute").setTabCompleter(unmuteCommand);
        }
        if (getCommand("kick") != null) {
            KickCommand kickCommand = new KickCommand(configurationManager, punishmentManager, textUtil,
                    networkPlayerResolver, schedulerProvider);
            getCommand("kick").setExecutor(kickCommand);
            getCommand("kick").setTabCompleter(kickCommand);
        }
        if (getCommand("report") != null) {
            ReportCommand reportCommand = new ReportCommand(configurationManager, reportManager, textUtil, this,
                    networkPlayerResolver);
            Bukkit.getPluginManager().registerEvents(reportCommand, this);
            getCommand("report").setExecutor(reportCommand);
            getCommand("report").setTabCompleter(reportCommand);
        }
        if (getCommand("reports") != null) {
            ReportsCommand reportsCommand = new ReportsCommand(configurationManager, reportManager, textUtil,
                    reportsGuiManager, this, networkPlayerResolver);
            getCommand("reports").setExecutor(reportsCommand);
            getCommand("reports").setTabCompleter(reportsCommand);
        }
    }

    // plugin.yml has the default nodes, this keeps custom nodes from settings.yml working
    private void applyCommandPermissions() {
        String[][] commands = {
            {"staffmode", "staffmode.use", "staffmode.reload"}, {"vanish", "vanish.self", "vanish.other"},
            {"vanishlist", "vanish.list"}, {"freeze", "freeze.use"}, {"unfreeze", "freeze.use"},
            {"freezelist", "freeze.list"}, {"invsee", "inventory.invsee"}, {"ecsee", "inventory.ecsee"},
            {"inspect", "inspect"}, {"note", "notes.add", "notes.remove"}, {"notes", "notes.view"},
            {"staffchat", "staffchat"},
            {"ban", "punishments.ban"}, {"unban", "punishments.unban"}, {"mute", "punishments.mute"},
            {"unmute", "punishments.unmute"}, {"kick", "punishments.kick"}, {"report", "reports.report"},
            {"reports", "reports.view"}
        };
        SettingsConfig settings = configurationManager.getSettings();
        for (String[] entry : commands) {
            PluginCommand command = getCommand(entry[0]);
            if (command == null) continue;
            StringJoiner nodes = new StringJoiner(";");
            for (int i = 1; i < entry.length; i++) {
                String node = settings.getPermission(entry[i]);
                if (node == null || node.isEmpty()) {
                    // an empty node means everyone can use it
                    nodes = null;
                    break;
                }
                nodes.add(node);
            }
            command.setPermission(nodes == null ? null : nodes.toString());
        }
    }

    private void shutdownManagers() {
        if (vanishManager != null) vanishManager.shutdown();
        if (freezeManager != null) freezeManager.shutdown();
        if (staffModeManager != null) staffModeManager.shutdown();
        if (notesManager != null) notesManager.shutdown();
        if (punishmentManager != null) punishmentManager.shutdown();
        if (staffChatManager != null) staffChatManager.shutdown();
        if (teleportManager != null) teleportManager.shutdown();
        if (inspectManager != null) inspectManager.shutdown();
        if (reportsGuiManager != null) reportsGuiManager.shutdown();
    }

    private void shutdownDatabase() {
        if (databaseManager != null) databaseManager.shutdown();
    }

    private void registerPlaceholderExpansion() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        new StaffPlaceholderExpansion(this, vanishManager, freezeManager, notesManager, punishmentManager).register();
    }
}
