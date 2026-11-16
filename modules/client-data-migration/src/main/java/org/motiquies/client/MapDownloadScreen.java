package org.motiquies.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Fits a 320x240 logical viewport (640x480 at GUI scale 2). */
final class MapDownloadScreen extends Screen {
    private final Screen parent;
    private final MapDownloadClient controls;
    private final MapDownloadController controller;
    private final Button[] ranges = new Button[3];
    private Button pause;
    private Button resume;
    private Button refresh;
    private boolean queried;
    private int left;
    private int top;
    private int content;
    private RadiusSlider radius;
    private int chosenRadius=1;
    private boolean radiusInitialized;
    private boolean radiusEdited;

    MapDownloadScreen(Screen parent, MapDownloadClient controls) {
        super(Component.literal("服务器地图下载"));
        this.parent = parent;
        this.controls = controls;
        this.controller = controls.controller();
    }

    @Override
    protected void init() {
        content = Math.min(360, width - 20);
        left = (width - content) / 2;
        top = Math.max(8, (height - 224) / 2);
        int third = (content - 8) / 3;
        radius=addRenderableWidget(new RadiusSlider(left,top+31,content));
        ranges[0] = button("自选下载", left, top + 55, third, () -> controls.requestCustom(chosenRadius));
        ranges[1] = button("当前维度", left + third + 4, top + 55, third, () -> controls.request(MapDownloadController.Command.DIMENSION));
        ranges[2] = button("全服", left + 2 * (third + 4), top + 55, content - 2 * (third + 4), () -> controls.request(MapDownloadController.Command.GLOBAL));
        int half = (content - 4) / 2;
        pause = button("暂停", left, top + 166, half, () -> controls.request(MapDownloadController.Command.PAUSE));
        resume = button("续传", left + half + 4, top + 166, content - half - 4, () -> controls.request(MapDownloadController.Command.RESUME));
        refresh = button("刷新状态", left, top + 190, half, () -> controls.request(MapDownloadController.Command.STATUS));
        button("返回", left + half + 4, top + 190, content - half - 4, this::onClose);
        if (!queried) {
            queried = true;
            controls.request(MapDownloadController.Command.STATUS);
        }
        updateControls();
    }

    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), button -> {
            action.run();
            updateControls();
        }).bounds(x, y, w, 20).build());
    }

    private void updateControls() {
        for (Button range : ranges) range.active = controller.canChoose();
        ranges[0].active&=controller.customSupported();
        radius.active=ranges[0].active;
        if(!radiusInitialized&&!radiusEdited&&controller.customSupported()){
            chosenRadius=controller.reply().radius();radius.refresh();radiusInitialized=true;
        }
        pause.active = controller.canPause();
        resume.active = controller.canResume();
        refresh.active = controller.canQuery();
    }

    @Override
    public void tick() {
        super.tick();
        updateControls();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top + 1, 0xFFFFFF);
        line(graphics, "以玩家所在区域为中心 · 每区域512×512格", top + 17, 0xCCCCCC);
        var reply = controller.reply();
        String mode = reply == null ? "NEARBY" : reply.mode();
        line(graphics, "范围：" + rangeName(mode)+(reply!=null&&mode.equals("CUSTOM")?"（"+(2*reply.radius()+1)+"×"+(2*reply.radius()+1)+"区域）":""), top + 79, 0xFFFFFF);
        if (reply != null) {
            String queued = reply.queuePosition() > 0 ? " · 排队第" + reply.queuePosition() + "位" : "";
            line(graphics, "状态：" + reply.status() + queued, top + 92, 0xEEEEEE);
            line(graphics, "限速 " + reply.playerKiB() + "/" + reply.totalKiB() + " KiB/s · 大型名额 " + reply.largeSlots(), top + 105, 0xCCCCCC);
            line(graphics, "尚需约 " + DownloadDisplay.bytes(reply.remainingBytes())+" / "+DownloadDisplay.bytes(reply.totalBytes()),top+118,0xCCCCCC);
            String eta=reply.mode().equals("PAUSED")?"已暂停":reply.queuePosition()>0?"排队中，开始后估算":reply.status().contains("繁忙")?"服务器繁忙，暂缓":DownloadDisplay.duration(reply.etaSeconds());
            if(reply.remainingBytes()==0&&!reply.mode().equals("PAUSED")&&controls.writesComplete())eta="已完成";
            line(graphics,"预计剩余："+eta,top+131,0xCCCCCC);
        } else {
            line(graphics, "等待服务器确认后可选择范围。", top + 92, 0xCCCCCC);
        }
        List<FormattedCharSequence> notice = font.split(Component.literal(controller.message()), content);
        for (int i = 0; i < Math.min(2, notice.size()); i++) {
            graphics.drawString(font, notice.get(i), left, top + 144 + i * 10, 0xF3D78B);
        }
        if (notice.size() > 2 && mouseX >= left && mouseX < left + content && mouseY >= top + 144 && mouseY < top + 164) {
            setTooltipForNextRenderPass(Component.literal(controller.message()));
        }
        graphics.drawCenteredString(font, "估时不含排队、重试；已完成区域可续传。", width / 2, top + 215, 0xAAAAAA);
    }

    private void line(GuiGraphics graphics, String text, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(text, content), left, y, color);
    }

    private static String rangeName(String mode) {
        return switch (mode) {
            case "DIMENSION" -> "当前维度";
            case "CUSTOM" -> "自选范围";
            case "GLOBAL" -> "全服";
            case "PAUSED" -> "已暂停";
            default -> "默认范围（半径1区域）";
        };
    }
    private final class RadiusSlider extends AbstractSliderButton {
        RadiusSlider(int x,int y,int w){super(x,y,w,20,Component.empty(),chosenRadius/32.0);updateMessage();}
        void refresh(){value=chosenRadius/32.0;updateMessage();}
        @Override protected void updateMessage(){int side=chosenRadius*2+1;setMessage(Component.literal("半径 "+chosenRadius+" 区域 · "+side+"×"+side+"（宽 "+side*512+" 格）"));}
        @Override protected void applyValue(){chosenRadius=(int)Math.round(value*32);radiusEdited=true;updateMessage();}
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
