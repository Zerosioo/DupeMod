package com.github.zerosioo;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.HashMap;
import java.util.Map;

@Mod(modid = Main.MODID, version = Main.VERSION, name = Main.NAME, clientSideOnly = true)
public class Main {
    public static final String MODID = "keepitems";
    public static final String VERSION = "1.0";
    public static final String NAME = "Keep Items Mod";
    
    private static boolean keepItemsEnabled = false;
    private static boolean coinDupeMode = false; // New mode for coin duping
    private static final int TOGGLE_BUTTON_ID = 999999;
    private static final int MODE_BUTTON_ID = 999998;
    private GuiButton toggleButton;
    private GuiButton modeButton;
    
    private Map<Integer, ItemStack> clickedItems = new HashMap<>();
    private ChannelHandlerContext channelContext = null;
    
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }
    
    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        event.manager.channel().pipeline().addBefore("packet_handler", "keepitems_handler", 
            new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    channelContext = ctx;
                    
                    if (keepItemsEnabled && msg instanceof C0EPacketClickWindow) {
                        C0EPacketClickWindow packet = (C0EPacketClickWindow) msg;
                        
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc.thePlayer != null && mc.thePlayer.openContainer != null) {
                            int slotId = packet.getSlotId();
                            
                            if (slotId >= 0 && slotId < mc.thePlayer.openContainer.inventorySlots.size()) {
                                Slot slot = mc.thePlayer.openContainer.getSlot(slotId);
                                
                                if (slot != null && isPlayerInventorySlot(slot) && slot.getStack() != null) {
                                    ItemStack clickedStack = slot.getStack().copy();
                                    clickedItems.put(slotId, clickedStack);
                                    
                                    // Send original packet
                                    super.write(ctx, msg, promise);
                                    
                                    // Determine how many times to click back based on mode
                                    int clickCount = coinDupeMode ? 2 : 1;
                                    
                                    // Schedule sending the item back
                                    new Thread(() -> {
                                        try {
                                            for (int i = 0; i < clickCount; i++) {
                                                final int clickNumber = i;
                                                Thread.sleep(100 + (i * 50)); // Stagger the clicks
                                                
                                                mc.addScheduledTask(() -> {
                                                    if (mc.thePlayer != null && mc.thePlayer.openContainer != null) {
                                                        try {
                                                            short actionNumber = (short) (packet.getActionNumber() + clickNumber + 1);
                                                            
                                                            C0EPacketClickWindow restorePacket = new C0EPacketClickWindow(
                                                                mc.thePlayer.openContainer.windowId,
                                                                slotId,
                                                                0,
                                                                0,
                                                                clickedStack.copy(),
                                                                actionNumber
                                                            );
                                                            
                                                            if (channelContext != null) {
                                                                channelContext.writeAndFlush(restorePacket);
                                                            }
                                                            
                                                            // Update client-side
                                                            if (slotId < mc.thePlayer.openContainer.inventorySlots.size()) {
                                                                Slot targetSlot = mc.thePlayer.openContainer.getSlot(slotId);
                                                                if (targetSlot != null && isPlayerInventorySlot(targetSlot)) {
                                                                    targetSlot.putStack(clickedStack.copy());
                                                                }
                                                            }
                                                        } catch (Exception e) {
                                                            e.printStackTrace();
                                                        }
                                                    }
                                                });
                                            }
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }).start();
                                    
                                    return;
                                }
                            }
                        }
                    }
                    super.write(ctx, msg, promise);
                }
            }
        );
    }
    
    @SubscribeEvent
    public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiContainer) {
            GuiScreen gui = event.gui;
            
            // Main toggle button
            String toggleText = keepItemsEnabled ? "§a§lKeep Items: ON" : "§c§lKeep Items: OFF";
            toggleButton = new GuiButton(TOGGLE_BUTTON_ID, gui.width - 120, 5, 115, 20, toggleText);
            event.buttonList.add(toggleButton);
            
            // Mode button (only show when enabled)
            if (keepItemsEnabled) {
                String modeText = coinDupeMode ? "§e§lMode: COIN DUPE" : "§b§lMode: ITEM KEEP";
                modeButton = new GuiButton(MODE_BUTTON_ID, gui.width - 120, 28, 115, 20, modeText);
                event.buttonList.add(modeButton);
            }
        }
    }
    
    @SubscribeEvent
    public void onGuiAction(GuiScreenEvent.ActionPerformedEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        
        // Toggle main feature
        if (event.button.id == TOGGLE_BUTTON_ID) {
            keepItemsEnabled = !keepItemsEnabled;
            String buttonText = keepItemsEnabled ? "§a§lKeep Items: ON" : "§c§lKeep Items: OFF";
            event.button.displayString = buttonText;
            
            if (mc.thePlayer != null) {
                String status = keepItemsEnabled ? "§a§lENABLED" : "§c§lDISABLED";
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    "§6§l[Keep Items] " + status
                ));
            }
            
            if (!keepItemsEnabled) {
                clickedItems.clear();
                coinDupeMode = false;
            }
            
            // Refresh GUI to show/hide mode button
            if (mc.currentScreen != null) {
                mc.currentScreen.initGui();
            }
        }
        
        // Toggle mode
        if (event.button.id == MODE_BUTTON_ID) {
            coinDupeMode = !coinDupeMode;
            String modeText = coinDupeMode ? "§e§lMode: COIN DUPE" : "§b§lMode: ITEM KEEP";
            event.button.displayString = modeText;
            
            if (mc.thePlayer != null) {
                String mode = coinDupeMode ? 
                    "§e§lCOIN DUPE §7(2x Clicks)" : 
                    "§b§lITEM KEEP §7(1x Click)";
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    "§6§l[Keep Items] Mode: " + mode
                ));
            }
        }
    }
    
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !keepItemsEnabled) {
            return;
        }
        
        if (mc.currentScreen == null || !(mc.currentScreen instanceof GuiContainer)) {
            clickedItems.clear();
        }
    }
    
    private boolean isPlayerInventorySlot(Slot slot) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && slot.inventory == mc.thePlayer.inventory;
    }
    
    public static boolean isKeepItemsEnabled() {
        return keepItemsEnabled;
    }
    
    public static boolean isCoinDupeMode() {
        return coinDupeMode;
    }
}
