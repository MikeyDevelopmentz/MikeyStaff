package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.database.models.PlayerReport;
import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReportsGuiManager {

    private static final String TITLE_BASE = ChatColor.DARK_AQUA + "Reports";
    private static final String CONFIRM_TITLE_BASE = ChatColor.RED + "Confirm Resolve";
    private static final int PAGE_SIZE = 28;
    private static final int[] ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    public enum Filter {
        UNRESOLVED,
        SOLVED
    }

    private final ReportManager reportManager;
    private final SchedulerProvider schedulerProvider;
    private final TextUtil textUtil;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingResolve = new ConcurrentHashMap<>();

    public ReportsGuiManager(ReportManager reportManager, SchedulerProvider schedulerProvider, TextUtil textUtil) {
        this.reportManager = reportManager;
        this.schedulerProvider = schedulerProvider;
        this.textUtil = textUtil;
    }

    public void openMain(Player staff) {
        UUID staffId = staff.getUniqueId();
        reportManager.getAllReports().whenComplete((list, error) ->
            schedulerProvider.runFor(staff, () -> {
                if (!staff.isOnline() || Bukkit.getPlayer(staffId) != staff) return;
                if (error != null) {
                    staff.sendMessage(ChatColor.RED + "[Reports] Failed to load reports.");
                    return;
                }
                Session session = new Session(list, Filter.UNRESOLVED, 0);
                sessions.put(staffId, session);
                openPage(staff, session, 0);
            }));
    }

    public void handleMainClick(Player staff, String title, int slot, ItemStack clicked, ClickType clickType) {
        if (title == null || !title.startsWith(TITLE_BASE)) {
            return;
        }
        UUID staffId = staff.getUniqueId();
        Session session = sessions.get(staffId);
        if (session == null) {
            return;
        }
        if (slot == 49) {
            staff.closeInventory();
            return;
        }
        if (slot == 45 && clicked.getType() == Material.ARROW) {
            if (session.page > 0) {
                openPage(staff, session, session.page - 1);
            }
            return;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            openPage(staff, session, session.page + 1);
            return;
        }
        if (slot == 4 && clicked.getType() == Material.HOPPER) {
            session.filter = session.filter == Filter.UNRESOLVED ? Filter.SOLVED : Filter.UNRESOLVED;
            openPage(staff, session, 0);
            return;
        }
        if (clicked.getType() == Material.PAPER) {
            List<PlayerReport> filtered = filteredReports(session);
            int start = session.page * PAGE_SIZE;
            for (int i = 0; i < ENTRY_SLOTS.length; i++) {
                if (ENTRY_SLOTS[i] == slot) {
                    int index = start + i;
                    if (index >= filtered.size()) {
                        return;
                    }
                    PlayerReport report = filtered.get(index);
                    if (clickType.isRightClick()) {
                        return;
                    }
                    if (!report.isSolved()) {
                        pendingResolve.put(staffId, report.getId());
                        openConfirm(staff, report);
                    }
                    return;
                }
            }
        }
    }

    public void handleConfirmClick(Player staff, String title, int slot, ItemStack clicked) {
        if (title == null || !title.startsWith(CONFIRM_TITLE_BASE)) {
            return;
        }
        UUID staffId = staff.getUniqueId();
        Long id = pendingResolve.get(staffId);
        if (id == null) {
            staff.closeInventory();
            return;
        }
        if (slot == 3 && clicked.getType() == Material.LIME_WOOL) {
            reportManager.solveReport(id, staff).whenComplete((ignored, solveError) ->
                schedulerProvider.runFor(staff, () -> {
                    if (!staff.isOnline() || Bukkit.getPlayer(staffId) != staff) return;
                    if (solveError != null) {
                        staff.sendMessage(ChatColor.RED + "[Reports] Failed to resolve report.");
                        return;
                    }

                    pendingResolve.remove(staffId);
                    staff.closeInventory();
                    staff.sendMessage(ChatColor.GREEN + "[Reports] Report resolved.");
                    Session session = sessions.get(staffId);
                    if (session != null) {
                        reportManager.getAllReports().whenComplete((list, refreshError) ->
                            schedulerProvider.runFor(staff, () -> {
                                if (!staff.isOnline() || Bukkit.getPlayer(staffId) != staff) return;
                                Session current = sessions.get(staffId);
                                if (current == null) return;
                                if (refreshError != null) {
                                    staff.sendMessage(ChatColor.RED
                                        + "[Reports] Report resolved, but the list could not be refreshed.");
                                    return;
                                }
                                current.reports = list;
                                openPage(staff, current, current.page);
                            }));
                    }
                }));
            return;
        }
        if (slot == 5 && clicked.getType() == Material.RED_WOOL) {
            pendingResolve.remove(staffId);
            staff.closeInventory();
        }
    }

    private void openPage(Player staff, Session session, int requestedPage) {
        List<PlayerReport> filtered = filteredReports(session);
        int maxPage = filtered.isEmpty() ? 0 : (filtered.size() - 1) / PAGE_SIZE;
        int page = requestedPage;
        if (page < 0) {
            page = 0;
        }
        if (page > maxPage) {
            page = maxPage;
        }
        session.page = page;
        String filterName = session.filter == Filter.UNRESOLVED ? "Unresolved" : "Solved";
        String title = TITLE_BASE + ChatColor.GRAY + " [" + filterName + "] (" + (page + 1) + "/" + (maxPage + 1) + ")";
        Inventory inv = StaffMenuHolder.create(54, title);

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        int start = page * PAGE_SIZE;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= filtered.size()) {
                break;
            }
            PlayerReport report = filtered.get(index);
            List<String> lore = new ArrayList<>();
            String targetName = Bukkit.getOfflinePlayer(report.getReportedUuid()).getName();
            String reporterName = Bukkit.getOfflinePlayer(report.getReporterUuid()).getName();
            lore.add(ChatColor.GRAY + "Target: " + (targetName == null ? "Unknown" : targetName));
            lore.add(ChatColor.GRAY + "Reporter: " + (reporterName == null ? "Unknown" : reporterName));
            lore.add(ChatColor.GRAY + "Reason: " + report.getReason());
            lore.add(ChatColor.GRAY + "Date: " + format.format(new Date(report.getCreatedAt())));
            lore.add(ChatColor.GRAY + "Status: " + (report.isSolved() ? ChatColor.GREEN + "Solved" : ChatColor.RED + "Unresolved"));
            if (!report.isSolved()) {
                lore.add(ChatColor.DARK_GRAY + "Left-click to resolve");
            }
            ChatColor color = report.isSolved() ? ChatColor.GREEN : ChatColor.RED;
            ItemStack item = createItem(Material.PAPER, color + "Report #" + report.getId(), lore);
            inv.setItem(ENTRY_SLOTS[i], item);
        }

        ItemStack filterItem = createItem(Material.HOPPER, ChatColor.YELLOW + "Filter: " + filterName, null);
        inv.setItem(4, filterItem);
        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", null));
        }
        if (page < maxPage) {
            inv.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "Next Page", null));
        }
        inv.setItem(49, createItem(Material.BARRIER, ChatColor.RED + "Close", null));

        staff.openInventory(inv);
    }

    private void openConfirm(Player staff, PlayerReport report) {
        String title = CONFIRM_TITLE_BASE + " #" + report.getId();
        Inventory inv = StaffMenuHolder.create(9, title);
        inv.setItem(3, createItem(Material.LIME_WOOL, ChatColor.GREEN + "Confirm", null));
        inv.setItem(5, createItem(Material.RED_WOOL, ChatColor.RED + "Cancel", null));
        staff.openInventory(inv);
    }

    public void shutdown() {
        sessions.clear();
        pendingResolve.clear();
    }

    public void handleQuit(UUID staffId) {
        sessions.remove(staffId);
        pendingResolve.remove(staffId);
    }

    private List<PlayerReport> filteredReports(Session session) {
        List<PlayerReport> result = new ArrayList<>();
        for (PlayerReport report : session.reports) {
            if (session.filter == Filter.UNRESOLVED && !report.isSolved()) {
                result.add(report);
            } else if (session.filter == Filter.SOLVED && report.isSolved()) {
                result.add(report);
            }
        }
        return result;
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

    private static class Session {
        private List<PlayerReport> reports;
        private Filter filter;
        private int page;

        private Session(List<PlayerReport> reports, Filter filter, int page) {
            this.reports = reports;
            this.filter = filter;
            this.page = page;
        }
    }
}
