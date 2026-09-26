package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.database.models.FreezeLog;
import mikey.me.staffsystem.database.models.PlayerReport;
import mikey.me.staffsystem.database.models.PunishmentLog;

import mikey.me.staffsystem.database.models.PlayerIPLog;
import mikey.me.staffsystem.database.models.LoginLog;
import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import mikey.me.staffsystem.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InspectManager {

    private static final String INFO_GUI_TITLE = ChatColor.DARK_AQUA + "Player Info: ";
    private static final int[] PUNISHMENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int PAGE_SIZE = 28;
    private static final String MAIN_MENU_TITLE = ChatColor.DARK_AQUA + "Inspect: ";
    private static final String IP_HISTORY_TITLE = ChatColor.DARK_AQUA + "IP History: ";
    private static final String LOGIN_LOGS_TITLE = ChatColor.DARK_AQUA + "Login Logs: ";
    private static final String ALT_ACCOUNTS_TITLE = ChatColor.DARK_AQUA + "Alt Accounts: ";
    private static final String INVSEE_TITLE = ChatColor.DARK_AQUA + "Invsee: ";
    private static final String ECSEE_TITLE = ChatColor.DARK_AQUA + "Ecsee: ";

    private enum Filter {
        ALL,
        BANS,
        MUTES,
        KICKS,
        FREEZES,
        REPORTS
    }

    private final PunishmentManager punishmentManager;
    private final FreezeManager freezeManager;
    private final ReportManager reportManager;
    private final IPManager ipManager;
    private final SchedulerProvider schedulerProvider;
    private final TextUtil textUtil;
    private final TimeUtil timeUtil;
    private final Map<UUID, InfoSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, AltSession> altSessions = new ConcurrentHashMap<>();
    private final Map<UUID, IPHistorySession> ipHistorySessions = new ConcurrentHashMap<>();
    private final Map<UUID, LoginLogSession> loginLogSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Inventory> readOnlyViews = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> mainMenuTargets = new ConcurrentHashMap<>();

    public InspectManager(PunishmentManager punishmentManager, FreezeManager freezeManager, ReportManager reportManager,
            IPManager ipManager, SchedulerProvider schedulerProvider, TextUtil textUtil) {
        this.punishmentManager = punishmentManager;
        this.freezeManager = freezeManager;
        this.reportManager = reportManager;
        this.ipManager = ipManager;
        this.schedulerProvider = schedulerProvider;
        this.textUtil = textUtil;
        this.timeUtil = new TimeUtil();
    }

    public boolean isFrozen(UUID uuid) {
        return freezeManager.isFrozen(uuid);
    }

    public void openInventory(Player staff, Player target) {
        Inventory copy = copyInventory(staff, target.getInventory(), INVSEE_TITLE + target.getName());
        readOnlyViews.put(staff.getUniqueId(), copy);
        staff.openInventory(copy);
    }

    public void openEnderChest(Player staff, Player target) {
        Inventory copy = copyInventory(staff, target.getEnderChest(), ECSEE_TITLE + target.getName());
        readOnlyViews.put(staff.getUniqueId(), copy);
        staff.openInventory(copy);
    }

    public boolean isReadOnlyInventory(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        for (Inventory view : readOnlyViews.values()) {
            if (view == inventory) {
                return true;
            }
        }
        return false;
    }

    public void handleReadOnlyClose(Inventory inventory) {
        for (Map.Entry<UUID, Inventory> entry : readOnlyViews.entrySet()) {
            if (entry.getValue() == inventory) {
                readOnlyViews.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    public void openInfo(Player staff, Player target) {
        UUID staffId = staff.getUniqueId();
        UUID targetId = target.getUniqueId();
        String targetName = target.getName();

        java.util.concurrent.CompletableFuture<java.util.List<PunishmentLog>> punishmentsFuture = punishmentManager
                .getPunishments(targetId);
        java.util.concurrent.CompletableFuture<java.util.List<FreezeLog>> freezesFuture = freezeManager
                .getLogs(targetId);
        java.util.concurrent.CompletableFuture<java.util.List<PlayerReport>> reportsFuture = reportManager
                .getReports(targetId);

        java.util.concurrent.CompletableFuture.allOf(punishmentsFuture, freezesFuture, reportsFuture)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        schedulerProvider.runFor(staff, () -> sendInspectionError(staff));
                        return;
                    }
                    schedulerProvider.runFor(staff, () -> {
                        java.util.List<PunishmentLog> punishments = punishmentsFuture.join();
                        java.util.List<FreezeLog> freezes = freezesFuture.join();
                        java.util.List<PlayerReport> reports = reportsFuture.join();
                        InfoSession session = new InfoSession(targetId, targetName, punishments, freezes, reports);
                        sessions.put(staffId, session);
                        openInfoPage(staff, session, 0);
                    });
                });
    }

    public boolean handleInfoClick(Player staff, String title, int slot, ItemStack clicked) {
        if (title == null || !title.startsWith(INFO_GUI_TITLE)) {
            return false;
        }
        UUID staffId = staff.getUniqueId();
        InfoSession session = sessions.get(staffId);
        if (session == null) {
            return false;
        }
        if (slot == 49) {
            staff.closeInventory();
            return true;
        }
        if (slot == 0 && clicked.getType() == Material.HOPPER) {
            Filter next;
            Filter current = session.getFilter();
            if (current == Filter.ALL) {
                next = Filter.BANS;
            } else if (current == Filter.BANS) {
                next = Filter.MUTES;
            } else if (current == Filter.MUTES) {
                next = Filter.KICKS;
            } else if (current == Filter.KICKS) {
                next = Filter.FREEZES;
            } else if (current == Filter.FREEZES) {
                next = Filter.REPORTS;
            } else {
                next = Filter.ALL;
            }
            session.setFilter(next);
            openInfoPage(staff, session, 0);
            return true;
        }
        int maxPage = session.getMaxPage();
        int currentPage = session.getPage();
        if (slot == 45 && clicked.getType() == Material.ARROW) {
            if (currentPage > 0) {
                openInfoPage(staff, session, currentPage - 1);
            }
            return true;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            if (currentPage < maxPage) {
                openInfoPage(staff, session, currentPage + 1);
            }
            return true;
        }
        return true;
    }

    private void openInfoPage(Player staff, InfoSession session, int requestedPage) {
        java.util.List<Object> entries = filteredEntries(session);
        int maxPage = entries.isEmpty() ? 0 : (entries.size() - 1) / PAGE_SIZE;
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        session.setPage(page);

        String title = INFO_GUI_TITLE + session.getTargetName() + ChatColor.GRAY + " (" + (page + 1) + "/"
                + (maxPage + 1) + ")";
        Inventory inv = StaffMenuHolder.create(54, title);

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta headMeta = head.getItemMeta();
        if (headMeta instanceof SkullMeta) {
            SkullMeta skullMeta = (SkullMeta) headMeta;
            Player online = Bukkit.getPlayer(session.getTargetUuid());
            if (online != null) {
                skullMeta.setOwningPlayer(online);
            }
            skullMeta.setDisplayName(ChatColor.AQUA + session.getTargetName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "UUID: " + session.getTargetUuid());
            lore.add(ChatColor.GRAY + "Punishments: " + session.getPunishments().size());
            lore.add(ChatColor.GRAY + "Freezes: " + session.getFreezes().size());
            lore.add(ChatColor.GRAY + "Reports: " + session.getReports().size());
            headMeta.setLore(lore);
        }
        head.setItemMeta(headMeta);
        inv.setItem(4, head);

        String filterName;
        Filter filter = session.getFilter();
        if (filter == Filter.ALL) {
            filterName = "All";
        } else if (filter == Filter.BANS) {
            filterName = "Bans";
        } else if (filter == Filter.MUTES) {
            filterName = "Mutes";
        } else if (filter == Filter.KICKS) {
            filterName = "Kicks";
        } else if (filter == Filter.FREEZES) {
            filterName = "Freezes";
        } else {
            filterName = "Reports";
        }
        ItemStack filterItem = createItem(Material.HOPPER, ChatColor.YELLOW + "Filter: " + filterName, null);
        inv.setItem(0, filterItem);

        int start = page * PAGE_SIZE;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= entries.size()) {
                break;
            }
            Object entry = entries.get(index);
            ItemStack item;
            if (entry instanceof PunishmentLog) {
                PunishmentLog log = (PunishmentLog) entry;
                Material material;
                ChatColor color;
                String label;
                switch (log.getType()) {
                    case "BAN":
                        material = Material.REDSTONE_BLOCK;
                        color = ChatColor.RED;
                        label = "Ban";
                        break;
                    case "MUTE":
                        material = Material.PAPER;
                        color = ChatColor.GOLD;
                        label = "Mute";
                        break;
                    case "KICK":
                    default:
                        material = Material.BARRIER;
                        color = ChatColor.DARK_RED;
                        label = "Kick";
                        break;
                }
                List<String> lore = new ArrayList<>();
                String staffName = Bukkit.getOfflinePlayer(log.getStaffUuid()).getName();
                lore.add(ChatColor.GRAY + "Staff: " + (staffName == null ? "Unknown" : staffName));
                String reason = log.getReason();
                lore.add(ChatColor.GRAY + "Reason: " + (reason == null || reason.isEmpty() ? "None" : reason));
                lore.add(ChatColor.GRAY + "Duration: " + formatDuration(log.getDurationSeconds()));
                lore.add(ChatColor.GRAY + "Start: " + dateFormat.format(new Date(log.getStartTime())));
                boolean active = punishmentManager.isActiveNow(log);
                lore.add(ChatColor.GRAY + "Status: "
                        + (active ? ChatColor.GREEN + "Active" : ChatColor.RED + "Inactive"));
                item = createItem(material, color + label, lore);
            } else if (entry instanceof FreezeLog) {
                FreezeLog freeze = (FreezeLog) entry;
                List<String> lore = new ArrayList<>();
                String staffName = Bukkit.getOfflinePlayer(freeze.getStaffUuid()).getName();
                lore.add(ChatColor.GRAY + "Staff: " + (staffName == null ? "Unknown" : staffName));
                String reason = freeze.getReason();
                lore.add(ChatColor.GRAY + "Reason: " + (reason == null || reason.isEmpty() ? "None" : reason));
                lore.add(ChatColor.GRAY + "Duration: " + formatDuration(freeze.getDurationSeconds()));
                lore.add(ChatColor.GRAY + "Start: " + dateFormat.format(new Date(freeze.getStartTime())));
                String status;
                if (freeze.isActive()) {
                    status = ChatColor.GREEN + "Active";
                } else if (freeze.getEndTime() != null && freeze.getDurationSeconds() == 0
                        && "Logout while frozen".equals(reason)) {
                    status = ChatColor.RED + "Logout";
                } else {
                    status = ChatColor.RED + "Ended";
                }
                lore.add(ChatColor.GRAY + "Status: " + status);
                item = createItem(Material.ICE, ChatColor.AQUA + "Freeze", lore);
            } else {
                PlayerReport report = (PlayerReport) entry;
                List<String> lore = new ArrayList<>();
                String reporterName = Bukkit.getOfflinePlayer(report.getReporterUuid()).getName();
                lore.add(ChatColor.GRAY + "Reporter: " + (reporterName == null ? "Unknown" : reporterName));
                lore.add(ChatColor.GRAY + "Reason: " + report.getReason());
                lore.add(ChatColor.GRAY + "Date: " + dateFormat.format(new Date(report.getCreatedAt())));
                String status = report.isSolved() ? ChatColor.GREEN + "Solved" : ChatColor.RED + "Unresolved";
                lore.add(ChatColor.GRAY + "Status: " + status);
                ChatColor color = report.isSolved() ? ChatColor.GREEN : ChatColor.RED;
                item = createItem(Material.PAPER, color + "Report #" + report.getId(), lore);
            }
            inv.setItem(PUNISHMENT_SLOTS[i], item);
        }

        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", null));
        }
        if (page < maxPage) {
            inv.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "Next Page", null));
        }
        inv.setItem(49, createItem(Material.BARRIER, ChatColor.RED + "Close", null));

        staff.openInventory(inv);
    }

    private Inventory copyInventory(Player staff, Inventory source, String title) {
        int sourceSize = source.getSize();
        int size = source instanceof PlayerInventory ? 54 : Math.max(9, ((sourceSize + 8) / 9) * 9);
        size = Math.min(54, size);
        Inventory copy = StaffMenuHolder.create(size, title);
        ItemStack[] contents = source.getContents();
        for (int slot = 0; slot < copy.getSize(); slot++) {
            ItemStack item = slot < contents.length ? contents[slot] : null;
            copy.setItem(slot, item == null ? null : item.clone());
        }
        return copy;
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(name);
            }
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatDuration(long durationSeconds) {
        if (durationSeconds <= 0) {
            return "Permanent";
        }
        return timeUtil.formatDuration(durationSeconds);
    }

    private void sendInspectionError(Player staff) {
        if (staff.isOnline() && Bukkit.getPlayer(staff.getUniqueId()) == staff) {
            staff.sendMessage(textUtil.prefixed("errors.database-error"));
        }
    }

    public void shutdown() {
        sessions.clear();
        altSessions.clear();
        ipHistorySessions.clear();
        loginLogSessions.clear();
        readOnlyViews.clear();
        mainMenuTargets.clear();
    }

    public void handleQuit(UUID staffId) {
        sessions.remove(staffId);
        altSessions.remove(staffId);
        ipHistorySessions.remove(staffId);
        loginLogSessions.remove(staffId);
        readOnlyViews.remove(staffId);
        mainMenuTargets.remove(staffId);
    }

    private java.util.List<Object> filteredEntries(InfoSession session) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        Filter filter = session.getFilter();
        for (PunishmentLog log : session.getPunishments()) {
            if (filter == Filter.ALL) {
                result.add(log);
            } else if (filter == Filter.BANS && "BAN".equalsIgnoreCase(log.getType())) {
                result.add(log);
            } else if (filter == Filter.MUTES && "MUTE".equalsIgnoreCase(log.getType())) {
                result.add(log);
            } else if (filter == Filter.KICKS && "KICK".equalsIgnoreCase(log.getType())) {
                result.add(log);
            }
        }
        if (filter == Filter.ALL || filter == Filter.FREEZES) {
            result.addAll(session.getFreezes());
        }
        if (filter == Filter.ALL || filter == Filter.REPORTS) {
            result.addAll(session.getReports());
        }
        result.sort((a, b) -> {
            long ta;
            long tb;
            if (a instanceof PunishmentLog) {
                ta = ((PunishmentLog) a).getStartTime();
            } else if (a instanceof FreezeLog) {
                ta = ((FreezeLog) a).getStartTime();
            } else {
                ta = ((PlayerReport) a).getCreatedAt();
            }
            if (b instanceof PunishmentLog) {
                tb = ((PunishmentLog) b).getStartTime();
            } else if (b instanceof FreezeLog) {
                tb = ((FreezeLog) b).getStartTime();
            } else {
                tb = ((PlayerReport) b).getCreatedAt();
            }
            return Long.compare(tb, ta);
        });
        return result;
    }

    private static class InfoSession {
        private final UUID targetUuid;
        private final String targetName;
        private final List<PunishmentLog> punishments;
        private final List<FreezeLog> freezes;
        private final List<PlayerReport> reports;
        private Filter filter;
        private int page;

        private InfoSession(UUID targetUuid, String targetName, List<PunishmentLog> punishments,
                List<FreezeLog> freezes, List<PlayerReport> reports) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.punishments = punishments;
            this.freezes = freezes;
            this.reports = reports;
            this.filter = Filter.ALL;
            this.page = 0;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getTargetName() {
            return targetName;
        }

        public List<PunishmentLog> getPunishments() {
            return punishments;
        }

        public List<FreezeLog> getFreezes() {
            return freezes;
        }

        public List<PlayerReport> getReports() {
            return reports;
        }

        public Filter getFilter() {
            return filter;
        }

        public void setFilter(Filter filter) {
            this.filter = filter;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public int getMaxPage() {
            int total = punishments.size() + freezes.size() + reports.size();
            return total == 0 ? 0 : (total - 1) / PAGE_SIZE;
        }
    }

    public void openMainMenu(Player staff, Player target) {
        mainMenuTargets.put(staff.getUniqueId(), target.getUniqueId());
        String title = MAIN_MENU_TITLE + target.getName();
        Inventory inv = StaffMenuHolder.create(54, title);

        ItemStack filler = createItem(Material.BLACK_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta headMeta = head.getItemMeta();
        if (headMeta instanceof SkullMeta) {
            SkullMeta skullMeta = (SkullMeta) headMeta;
            skullMeta.setOwningPlayer(target);
            skullMeta.setDisplayName(ChatColor.GOLD + "✦ " + ChatColor.AQUA + target.getName() + ChatColor.GOLD + " ✦");
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + "UUID: " + ChatColor.WHITE + target.getUniqueId().toString().substring(0, 18)
                    + "...");
            lore.add("");
            headMeta.setLore(lore);
        }
        head.setItemMeta(headMeta);
        inv.setItem(4, head);

        inv.setItem(21, createItem(Material.PAPER, ChatColor.AQUA + "IP History",
                List.of(ChatColor.GRAY + "View unique IPs used")));
        inv.setItem(22, createItem(Material.CLOCK, ChatColor.AQUA + "Login Logs",
                List.of(ChatColor.GRAY + "View all login events")));
        inv.setItem(23, createItem(Material.PLAYER_HEAD, ChatColor.AQUA + "Alt Accounts",
                List.of(ChatColor.GRAY + "View accounts with same IP")));
        inv.setItem(30, createItem(Material.RED_STAINED_GLASS, ChatColor.AQUA + "Punishment History",
                List.of(ChatColor.GRAY + "View bans, mutes, kicks, freezes")));
        inv.setItem(31, createItem(Material.CHEST, ChatColor.AQUA + "Inventory",
                List.of(ChatColor.GRAY + "View player inventory")));
        inv.setItem(32, createItem(Material.ENDER_CHEST, ChatColor.AQUA + "Ender Chest",
                List.of(ChatColor.GRAY + "View ender chest")));
        inv.setItem(49, createItem(Material.BARRIER, ChatColor.RED + "Close", null));

        staff.openInventory(inv);
    }

    public void openIPHistory(Player staff, Player target) {
        ipManager.getPlayerIPs(target.getUniqueId()).whenComplete((ips, error) ->
            schedulerProvider.runFor(staff, () -> {
                if (!staff.isOnline() || Bukkit.getPlayer(staff.getUniqueId()) != staff) return;
                if (error != null || ips == null) {
                    sendInspectionError(staff);
                    return;
                }
                IPHistorySession session = new IPHistorySession(target.getUniqueId(), target.getName(), ips);
                ipHistorySessions.put(staff.getUniqueId(), session);
                openIPHistoryPage(staff, session, 0);
            }));
    }

    private void openIPHistoryPage(Player staff, IPHistorySession session, int page) {
        List<PlayerIPLog> ips = session.getIpLogs();
        int maxPage = ips.isEmpty() ? 0 : (ips.size() - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, maxPage));
        session.setPage(page);

        String title = IP_HISTORY_TITLE + session.getTargetName() + ChatColor.GRAY + " (" + (page + 1) + "/"
                + (maxPage + 1) + ")";
        Inventory inv = StaffMenuHolder.create(54, title);

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        int start = page * PAGE_SIZE;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < PAGE_SIZE && (start + i) < ips.size(); i++) {
            PlayerIPLog ipLog = ips.get(start + i);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "IP Address: " + ChatColor.WHITE + ipLog.getIpAddress());
            lore.add(ChatColor.GRAY + "First Login: " + ChatColor.YELLOW
                    + dateFormat.format(new Date(ipLog.getFirstSeen())));
            lore.add(ChatColor.GRAY + "Last Login: " + ChatColor.YELLOW
                    + dateFormat.format(new Date(ipLog.getLastSeen())));

            boolean isRecent = (System.currentTimeMillis() - ipLog.getLastSeen()) < 86400000;
            Material material = isRecent ? Material.LIME_STAINED_GLASS : Material.GRAY_STAINED_GLASS;
            ChatColor color = isRecent ? ChatColor.GREEN : ChatColor.GRAY;

            inv.setItem(PUNISHMENT_SLOTS[i], createItem(material, color + "IP Login Record", lore));
        }

        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", null));
        }
        if (page < maxPage) {
            inv.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "Next Page", null));
        }
        inv.setItem(49, createItem(Material.ARROW, ChatColor.YELLOW + "Back to Main Menu", null));

        staff.openInventory(inv);
    }

    public void openAltAccounts(Player staff, Player target) {
        ipManager.getAltAccounts(target.getUniqueId()).whenComplete((altUUIDs, error) ->
            schedulerProvider.runFor(staff, () -> {
                if (!staff.isOnline() || Bukkit.getPlayer(staff.getUniqueId()) != staff) return;
                if (error != null || altUUIDs == null) {
                    sendInspectionError(staff);
                    return;
                }
                AltSession altSession = new AltSession(target.getUniqueId(), target.getName(), altUUIDs);
                altSessions.put(staff.getUniqueId(), altSession);

                if (altUUIDs.isEmpty()) {
                    String title = ALT_ACCOUNTS_TITLE + target.getName();
                    Inventory inv = StaffMenuHolder.create(54, title);
                    ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
                    for (int i = 0; i < inv.getSize(); i++) {
                        inv.setItem(i, filler);
                    }
                    inv.setItem(22, createItem(Material.BARRIER, ChatColor.RED + "No Alt Accounts Found",
                            List.of(ChatColor.GRAY + "No accounts share IPs with this player")));
                    inv.setItem(49, createItem(Material.ARROW, ChatColor.YELLOW + "Back to Main Menu", null));
                    staff.openInventory(inv);
                    return;
                }

                openAltAccountsPage(staff, altSession, 0);
            }));
    }

    private void openAltAccountsPage(Player staff, AltSession session, int page) {
        List<UUID> alts = session.getAltUUIDs();
        int maxPage = alts.isEmpty() ? 0 : (alts.size() - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, maxPage));
        session.setPage(page);

        String title = ALT_ACCOUNTS_TITLE + session.getTargetName() + ChatColor.GRAY + " (" + (page + 1) + "/"
                + (maxPage + 1) + ")";
        Inventory inv = StaffMenuHolder.create(54, title);

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        int start = page * PAGE_SIZE;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < PAGE_SIZE && (start + i) < alts.size(); i++) {
            final int slotIndex = i;
            UUID altUUID = alts.get(start + i);
            final String altName = Bukkit.getOfflinePlayer(altUUID).getName() != null
                    ? Bukkit.getOfflinePlayer(altUUID).getName()
                    : "Unknown";

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "UUID: " + ChatColor.WHITE + altUUID);

            ItemStack altHead = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = altHead.getItemMeta();
            if (meta instanceof SkullMeta) {
                ((SkullMeta) meta).setOwningPlayer(Bukkit.getOfflinePlayer(altUUID));
            }
            meta.setDisplayName(ChatColor.GOLD + altName);
            meta.setLore(lore);
            altHead.setItemMeta(meta);

            inv.setItem(PUNISHMENT_SLOTS[slotIndex], altHead);

            ipManager.getPlayerIPs(altUUID).whenComplete((ips, error) -> schedulerProvider.runFor(staff, () -> {
                if (!staff.isOnline() || Bukkit.getPlayer(staff.getUniqueId()) != staff) return;
                if (error != null || ips == null) {
                    sendInspectionError(staff);
                    return;
                }
                if (!ips.isEmpty()) {
                    lore.add(ChatColor.GRAY + "Last IP: " + ChatColor.WHITE + ips.get(0).getIpAddress());
                    lore.add(ChatColor.GRAY + "Last Seen: " + ChatColor.WHITE
                            + dateFormat.format(new Date(ips.get(0).getLastSeen())));
                    meta.setLore(lore);
                    altHead.setItemMeta(meta);
                    inv.setItem(PUNISHMENT_SLOTS[slotIndex], altHead);
                }
            }));
        }

        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", null));
        }
        if (page < maxPage) {
            inv.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "Next Page", null));
        }
        inv.setItem(49, createItem(Material.ARROW, ChatColor.YELLOW + "Back to Main Menu", null));

        staff.openInventory(inv);
    }

    public boolean handleMainMenuClick(Player staff, String title, int slot) {
        if (title == null || !title.startsWith(MAIN_MENU_TITLE)) {
            return false;
        }
        UUID targetId = mainMenuTargets.get(staff.getUniqueId());
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (target == null) {
            staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
            staff.closeInventory();
            return true;
        }

        if (slot == 21) {
            openIPHistory(staff, target);
            return true;
        } else if (slot == 22) {
            openLoginLogs(staff, target);
            return true;
        } else if (slot == 23) {
            openAltAccounts(staff, target);
            return true;
        } else if (slot == 30) {
            openInfo(staff, target);
            return true;
        } else if (slot == 31) {
            openInventory(staff, target);
            return true;
        } else if (slot == 32) {
            openEnderChest(staff, target);
            return true;
        } else if (slot == 49) {
            staff.closeInventory();
            return true;
        }
        return true;
    }

    public boolean handleIPHistoryClick(Player staff, String title, int slot, ItemStack clicked) {
        if (title == null || !title.startsWith(IP_HISTORY_TITLE)) {
            return false;
        }
        IPHistorySession session = ipHistorySessions.get(staff.getUniqueId());
        if (session == null) {
            return false;
        }

        if (slot == 49) {
            String targetName = session.getTargetName();
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                openMainMenu(staff, target);
            } else {
                staff.closeInventory();
            }
            return true;
        }

        int currentPage = session.getPage();
        int maxPage = session.getIpLogs().isEmpty() ? 0 : (session.getIpLogs().size() - 1) / PAGE_SIZE;

        if (slot == 45 && clicked.getType() == Material.ARROW) {
            if (currentPage > 0) {
                openIPHistoryPage(staff, session, currentPage - 1);
            }
            return true;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            if (currentPage < maxPage) {
                openIPHistoryPage(staff, session, currentPage + 1);
            }
            return true;
        }
        return true;
    }

    public boolean handleAltAccountsClick(Player staff, String title, int slot, ItemStack clicked) {
        if (title == null || !title.startsWith(ALT_ACCOUNTS_TITLE)) {
            return false;
        }
        AltSession session = altSessions.get(staff.getUniqueId());
        if (session == null) {
            return true;
        }

        if (slot == 49) {
            String targetName = session.getTargetName();
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                openMainMenu(staff, target);
            } else {
                staff.closeInventory();
            }
            return true;
        }

        int currentPage = session.getPage();
        int maxPage = session.getAltUUIDs().isEmpty() ? 0 : (session.getAltUUIDs().size() - 1) / PAGE_SIZE;

        if (slot == 45 && clicked.getType() == Material.ARROW) {
            if (currentPage > 0) {
                openAltAccountsPage(staff, session, currentPage - 1);
            }
            return true;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            if (currentPage < maxPage) {
                openAltAccountsPage(staff, session, currentPage + 1);
            }
            return true;
        }
        return true;
    }

    private static class AltSession {
        private final UUID targetUuid;
        private final String targetName;
        private final List<UUID> altUUIDs;
        private int page;

        private AltSession(UUID targetUuid, String targetName, List<UUID> altUUIDs) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.altUUIDs = altUUIDs;
            this.page = 0;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getTargetName() {
            return targetName;
        }

        public List<UUID> getAltUUIDs() {
            return altUUIDs;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }
    }

    private static class IPHistorySession {
        private final UUID targetUuid;
        private final String targetName;
        private final List<PlayerIPLog> ipLogs;
        private int page;

        private IPHistorySession(UUID targetUuid, String targetName, List<PlayerIPLog> ipLogs) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.ipLogs = ipLogs;
            this.page = 0;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getTargetName() {
            return targetName;
        }

        public List<PlayerIPLog> getIpLogs() {
            return ipLogs;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }
    }

    public void openLoginLogs(Player staff, Player target) {
        ipManager.getLoginLogs(target.getUniqueId()).whenComplete((logs, error) ->
            schedulerProvider.runFor(staff, () -> {
                if (!staff.isOnline() || Bukkit.getPlayer(staff.getUniqueId()) != staff) return;
                if (error != null || logs == null) {
                    sendInspectionError(staff);
                    return;
                }
                LoginLogSession session = new LoginLogSession(target.getUniqueId(), target.getName(), logs);
                loginLogSessions.put(staff.getUniqueId(), session);
                openLoginLogsPage(staff, session, 0);
            }));
    }

    private void openLoginLogsPage(Player staff, LoginLogSession session, int page) {
        List<LoginLog> logs = session.getLoginLogs();
        int maxPage = logs.isEmpty() ? 0 : (logs.size() - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, maxPage));
        session.setPage(page);

        String title = LOGIN_LOGS_TITLE + session.getTargetName() + ChatColor.GRAY + " (" + (page + 1) + "/"
                + (maxPage + 1) + ")";
        Inventory inv = StaffMenuHolder.create(54, title);

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        int start = page * PAGE_SIZE;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < PAGE_SIZE && (start + i) < logs.size(); i++) {
            LoginLog log = logs.get(start + i);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "IP: " + ChatColor.WHITE + log.getIpAddress());
            lore.add(ChatColor.GRAY + "Time: " + ChatColor.YELLOW + dateFormat.format(new Date(log.getLoginTime())));

            boolean isRecent = (System.currentTimeMillis() - log.getLoginTime()) < 86400000;
            Material material = isRecent ? Material.LIME_CONCRETE : Material.LIGHT_GRAY_CONCRETE;
            ChatColor color = isRecent ? ChatColor.GREEN : ChatColor.GRAY;

            inv.setItem(PUNISHMENT_SLOTS[i], createItem(material, color + "Login Event #" + (start + i + 1), lore));
        }

        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", null));
        }
        if (page < maxPage) {
            inv.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "Next Page", null));
        }
        inv.setItem(49, createItem(Material.ARROW, ChatColor.YELLOW + "Back to Main Menu", null));

        staff.openInventory(inv);
    }

    public boolean handleLoginLogsClick(Player staff, String title, int slot, ItemStack clicked) {
        if (title == null || !title.startsWith(LOGIN_LOGS_TITLE)) {
            return false;
        }
        LoginLogSession session = loginLogSessions.get(staff.getUniqueId());
        if (session == null) {
            return false;
        }

        if (slot == 49) {
            String targetName = session.getTargetName();
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                openMainMenu(staff, target);
            } else {
                staff.closeInventory();
            }
            return true;
        }

        int currentPage = session.getPage();
        int maxPage = session.getLoginLogs().isEmpty() ? 0 : (session.getLoginLogs().size() - 1) / PAGE_SIZE;

        if (slot == 45 && clicked.getType() == Material.ARROW) {
            if (currentPage > 0) {
                openLoginLogsPage(staff, session, currentPage - 1);
            }
            return true;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            if (currentPage < maxPage) {
                openLoginLogsPage(staff, session, currentPage + 1);
            }
            return true;
        }
        return true;
    }

    private static class LoginLogSession {
        private final UUID targetUuid;
        private final String targetName;
        private final List<LoginLog> loginLogs;
        private int page;

        private LoginLogSession(UUID targetUuid, String targetName, List<LoginLog> loginLogs) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.loginLogs = loginLogs;
            this.page = 0;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getTargetName() {
            return targetName;
        }

        public List<LoginLog> getLoginLogs() {
            return loginLogs;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }
    }
}
