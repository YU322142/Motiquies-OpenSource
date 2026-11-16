package org.motiquies.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class MigrationScreen extends Screen {
    private final Screen parent;
    private final Path target;
    private final Migration migration;
    private final Set<String> waypoints = new LinkedHashSet<>();
    private final Set<String> history = new LinkedHashSet<>();
    private final Set<String> bookmarks = new LinkedHashSet<>();
    private EditBox folder;
    private String typedPath = "";
    private String status;
    private Path source;
    private Migration.Preview preview;
    private int tab;
    private int page;
    private boolean importKeys;
    private boolean importFiles;
    private boolean importServers;
    private boolean running;

    MigrationScreen(Screen parent, Path target, String status) {
        super(Component.literal("Motiquies 数据迁移"));
        this.parent = parent;
        this.target = target;
        this.migration = new Migration(target);
        this.status = status;
    }

    @Override
    protected void init() {
        int content = Math.min(480, width - 20);
        int x = (width - content) / 2;
        folder = new EditBox(font, x, 35, content - 72, 20, Component.literal("旧实例路径"));
        folder.setMaxLength(4096);
        folder.setValue(typedPath);
        folder.setHint(Component.literal("粘贴包含 mods 和 options.txt 的旧实例目录"));
        folder.setResponder(value -> typedPath = value);
        folder.setEditable(!running);
        addRenderableWidget(folder);
        button("检查", x + content - 67, 35, 67, this::scan).active = !running;

        if (preview != null) {
            int tabWidth = (content - 8) / 3;
            String[] tabs = {"Xaero 路标", "JEI 查询", "JEI 收藏"};
            for (int i = 0; i < tabs.length; i++) {
                int index = i;
                button((tab == i ? "● " : "") + tabs[i], x + i * (tabWidth + 4), 62, tabWidth, () -> {
                    tab = index;
                    page = 0;
                    refresh();
                }).active = !running;
            }
            List<String> routes = currentRoutes();
            Set<String> selected = currentSelection();
            int capacity = rows();
            int start = page * capacity;
            for (int i = start; i < Math.min(start + capacity, routes.size()); i++) {
                String route = routes.get(i);
                String label = (selected.contains(route) ? "[√] " : "[  ] ") + route;
                button(font.plainSubstrByWidth(label, content - 12), x, 88 + (i - start) * 22, content, () -> {
                    if (!selected.remove(route)) selected.add(route);
                    refresh();
                }).active = !running;
            }
            int controlsY = height - 99;
            int quarter = (content - 9) / 4;
            button("全选", x, controlsY, quarter, () -> {
                selected.addAll(routes);
                refresh();
            }).active = !running;
            button("全不选", x + quarter + 3, controlsY, quarter, () -> {
                selected.clear();
                refresh();
            }).active = !running;
            button("上一页", x + (quarter + 3) * 2, controlsY, quarter, () -> {
                page--;
                refresh();
            }).active = !running && page > 0;
            button("下一页", x + (quarter + 3) * 3, controlsY, quarter, () -> {
                page++;
                refresh();
            }).active = !running && start + capacity < routes.size();
            button(flag(importKeys, "键位"), x, height - 74, tabWidth, () -> {
                importKeys = !importKeys;
                refresh();
            }).active = !running && preview.keysAvailable();
            button(flag(importFiles, "存档与个人文件"), x + tabWidth + 4, height - 74, tabWidth, () -> {
                importFiles = !importFiles;
                refresh();
            }).active = !running && preview.filesAvailable();
            button(flag(importServers, "服务器列表"), x + (tabWidth + 4) * 2, height - 74, tabWidth, () -> {
                importServers = !importServers;
                refresh();
            }).active = !running && preview.serversAvailable();
        }
        int half = (content - 4) / 2;
        button(running ? "迁移中…" : "预览并导入", x, height - 48, half, this::reviewImport)
                .active = !running && preview != null;
        button("返回主菜单", x + half + 4, height - 48, half, this::onClose).active = !running;
    }

    private static String flag(boolean enabled, String name) {
        return (enabled ? "[√] " : "[  ] ") + name;
    }

    private int rows() {
        return Math.max(1, (height - 190) / 22);
    }

    private Button button(String caption, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(caption), button -> action.run())
                .bounds(x, y, width, 20).build());
    }

    private void refresh() {
        rebuildWidgets();
    }

    private void scan() {
        preview = null;
        try {
            Path candidate = Path.of(typedPath.strip());
            if (!candidate.isAbsolute() || !Files.isDirectory(candidate)) {
                throw new IllegalArgumentException("请输入旧实例的绝对路径。");
            }
            source = candidate.toRealPath();
            preview = migration.preview(source);
            waypoints.clear();
            waypoints.addAll(preview.waypoints());
            history.clear();
            history.addAll(preview.history());
            bookmarks.clear();
            bookmarks.addAll(preview.bookmarks());
            importKeys = preview.keysAvailable();
            importFiles = preview.filesAvailable();
            importServers = preview.serversAvailable();
            page = 0;
            status = preview.isEmpty() ? "没有检测到可迁移的内容。"
                    : "可分别选择路标、历史与收藏的来源。相同记录去重，有差异的记录并存。";
        } catch (Exception error) {
            status = "无法读取旧实例：" + error.getMessage();
        }
        refresh();
    }

    private List<String> currentRoutes() {
        return switch (tab) {
            case 0 -> preview.waypoints();
            case 1 -> preview.history();
            default -> preview.bookmarks();
        };
    }

    private Set<String> currentSelection() {
        return switch (tab) {
            case 0 -> waypoints;
            case 1 -> history;
            default -> bookmarks;
        };
    }

    private void reviewImport() {
        if (running || preview == null || source == null) return;
        var selection = new Migration.Selection(Set.copyOf(waypoints), Set.copyOf(history), Set.copyOf(bookmarks),
                importKeys, importFiles, importServers);
        try {
            var conflicts = migration.conflicts(source, selection);
            minecraft.setScreen(new ConflictScreen(this, conflicts, excluded -> runImport(selection.withExcluded(excluded))));
        } catch (Exception failure) {
            status = "无法生成预览：" + failure.getMessage();
            refresh();
        }
    }

    private void runImport(Migration.Selection selection) {
        Path old = source;
        running = true;
        status = "正在导入，源文件会保留。";
        if (selection.keys() && !Files.exists(target.resolve("options.txt"))) minecraft.options.save();
        refresh();
        CompletableFuture.runAsync(() -> {
            try {
                migration.migrate(old, selection);
                ServerFiles.merge(target, selection.servers() ? old : null);
                migration.savePreferences(selection);
                migration.markComplete();
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }).whenComplete((ignored, failure) -> minecraft.execute(() -> {
            running = false;
            if (failure == null) {
                if (selection.keys()) minecraft.options.load();
                ClientMigrationMod.enableSharedXaero();
                status = "已导入。名称相同但内容不同的记录已保留。";
            } else {
                status = "导入未完成，可重试：" + failure.getCause().getMessage();
            }
            refresh();
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 13, 0xFFFFFF);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(status, width - 20), width / 2, height - 21, 0xEEEEEE);
        if (preview != null && currentRoutes().isEmpty()) {
            graphics.drawCenteredString(font, "没有检测到此类旧线路记录。", width / 2, 89, 0xAAAAAA);
        }
    }

    @Override
    public void onClose() {
        if (!running) minecraft.setScreen(parent);
    }
}
