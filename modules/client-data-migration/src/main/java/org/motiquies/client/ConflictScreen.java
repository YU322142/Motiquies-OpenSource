package org.motiquies.client;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConflictScreen extends Screen {
    private final Screen parent;
    private final List<Migration.Conflict> conflicts;
    private final Consumer<Set<Migration.RecordKey>> onConfirm;
    private final Set<Migration.RecordKey> excluded = new LinkedHashSet<>();
    private int page;

    ConflictScreen(Screen parent, List<Migration.Conflict> conflicts, Consumer<Set<Migration.RecordKey>> onConfirm) {
        super(Component.literal("确认迁移预览"));
        this.parent = parent;
        this.conflicts = conflicts;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(560, width - 20);
        int x = (width - contentWidth) / 2;
        int rows = Math.max(1, (height - 113) / 22);
        int start = page * rows;
        for (int i = start; i < Math.min(start + rows, conflicts.size()); i++) {
            Migration.Conflict item = conflicts.get(i);
            String flag = !item.selectable() ? "[已有] " : excluded.contains(item.key()) ? "[  ] " : "[√] ";
            String label = flag + item.key().category() + "  " + item.description() + "  ←  " + item.source();
            Button button = addRenderableWidget(Button.builder(
                    Component.literal(font.plainSubstrByWidth(label, contentWidth - 12)), pressed -> {
                        if (!item.selectable()) return;
                        if (!excluded.remove(item.key())) excluded.add(item.key());
                        rebuildWidgets();
                    }).bounds(x, 45 + (i - start) * 22, contentWidth, 20).build());
            button.setTooltip(Tooltip.create(Component.literal(label)));
            button.active = item.selectable();
        }
        int half = (contentWidth - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("上一页"), pressed -> {
            page--;
            rebuildWidgets();
        }).bounds(x, height - 65, half, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("下一页"), pressed -> {
            page++;
            rebuildWidgets();
        }).bounds(x + half + 4, height - 65, half, 20).build()).active = start + rows < conflicts.size();
        addRenderableWidget(Button.builder(Component.literal("返回修改来源"), pressed -> onClose())
                .bounds(x, height - 39, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("确认导入"), pressed -> {
            minecraft.setScreen(parent);
            onConfirm.accept(Set.copyOf(excluded));
        }).bounds(x + half + 4, height - 39, half, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 13, 0xFFFFFF);
        String summary = conflicts.isEmpty() ? "所选来源未发现不同内容的同名记录。"
                : "冲突记录默认并存。可逐条取消旧记录，目标已有记录保留。";
        graphics.drawCenteredString(font, font.plainSubstrByWidth(summary, width - 20), width / 2, 28, 0xDDDDDD);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
