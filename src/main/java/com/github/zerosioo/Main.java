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
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
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
    private static final int BUTTON_ID = 999999;
    private GuiButton toggleButton;
    
    private Map<Integer, ItemStack> inventorySnapshot = new HashMap<>();
    private Map<Integer, ItemStack> lastClickedItems = new HashMap<>();
    private int tickCounter = 0;
    
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
                    if (keepItemsEnabled && msg instanceof C0EPacketClickWindow) {
                        C0EPacketClickWindow packet = (C0EPacketClickWindow) msg;
                        
                        // Save the item that's being clicked for restoration
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc.thePlayer != null && mc.thePlayer.openContainer != null) {
                            int slotId = packet.getSlotId();
                            if (slotId >= 0 && slotId < mc.thePlayer.openContainer.inventorySlots.size()) {
                                Slot slot = mc.thePlayer.openContainer.getSlot(slotId);
                                if (slot != null && slot.getStack() != null && isPlayerInventorySlot(slot)) {
                                    lastClickedItems.put(slotId, slot.getStack().copy());
                                }
                            }
                        }
                    }
                    super.write(ctx, msg, promise);
                }
                
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (keepItemsEnabled) {
                        // Intercept server slot update packets
                        if (msg instanceof S2FPacketSetSlot) {
                            S2FPacketSetSlot packet = (S2FPacketSetSlot) msg;
                            Minecraft mc = Minecraft.getMinecraft();
                            
                            if (mc.thePlayer != null && mc.thePlayer.openContainer != null) {
                                int windowId = packet.func_149175_c(); // getWindowId
                                int slotId = packet.func_149173_d(); // getSlot
                                
                                // If this is updating our inventory and we have a saved item
                                if (windowId == mc.thePlayer.openContainer.windowId && 
                                    inventorySnapshot.containsKey(slotId)) {
                                    
                                    ItemStack savedItem = inventorySnapshot.get(slotId);
                                    if (savedItem != null) {
                                        // Create a new packet with our saved item to trick the client
                                        S2FPacketSetSlot newPacket = new S2FPacketSetSlot(
                                            windowId, slotId, savedItem.copy()
                                        );
                                        super.channelRead(ctx, newPacket);
                                        
                                        // Send the item back to server
                                        scheduleItemRestore(slotId, savedItem.copy());
                                        return;
                                    }
                                }
                            }
                        }
                        
                        // Intercept bulk window item updates
                        if (msg instanceof S30PacketWindowItems) {
                            S30PacketWindowItems packet = (S30PacketWindowItems) msg;
                            Minecraft mc = Minecraft.getMinecraft();
                            
                            if (mc.thePlayer != null && mc.thePlayer.openContainer != null) {
                                int windowId = packet.func_148911_c(); // getWindowId
                                
                                if (windowId == mc.thePlayer.openContainer.windowId && !inventorySnapshot.isEmpty()) {
                                    ItemStack[] items = packet.getItemStacks();
                                    ItemStack[] modifiedItems = items.clone();
                                    
                                    // Restore our saved items in the packet
                                    for (Map.Entry<Integer, ItemStack> entry : inventorySnapshot.entrySet()) {
                                        int slotId = entry.getKey();
                                        if (slotId >= 0 && slotId < modifiedItems.length) {
                                            ItemStack saved = entry.getValue();
                                            if (saved != null) {
                                                modifiedItems[slotId] = saved.copy();
                                                scheduleItemRestore(slotId, saved.copy());
                                            }
                                        }
                                    }
                                    
                                    S30PacketWindowItems newPacket = new S30PacketWindowItems(windowId, modifiedItems);
                                    super.channelRead(ctx, newPacket);
                                    return;
                                }
                            }
                        }
                    }
                    super.channelRead(ctx, msg);
                }
            }
        );
    }
    
    private void scheduleItemRestore(int slotId, ItemStack item) {
        // Schedule sending the item back to server on next tick
        new Thread(() -> {
            try {
                Thread.sleep(50); // Small delay
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.thePlayer != null && mc.thePlayer.openContainer != null) {
                    // Click the slot to put item back (creative mode style)
                    mc.addScheduledTask(() -> {
                        if (mc.thePlayer.openContainer != null && slotId < mc.thePlayer.openContainer.inventorySlots.size()) {
                            Slot slot = mc.thePlayer.openContainer.getSlot(slotId);
                            if (slot != null) {
                                slot.putStack(item.copy());
                            }
                        }
                    });
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    @SubscribeEvent
    public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiContainer) {
            GuiScreen gui = event.gui;
            String buttonText = keepItemsEnabled ? "§a§lKeep Items: ON" : "§c§lKeep Items: OFF";
            toggleButton = new GuiButton(BUTTON_ID, gui.width - 120, 5, 115, 20, buttonText);
            event.buttonList.add(toggleButton);
        }
    }
    
    @SubscribeEvent
    public void onGuiAction(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.button.id == BUTTON_ID) {
            keepItemsEnabled = !keepItemsEnabled;
            String buttonText = keepItemsEnabled ? "§a§lKeep Items: ON" : "§c§lKeep Items: OFF";
            event.button.displayString = buttonText;
            
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                String status = keepItemsEnabled ? "§a§lENABLED §7(Server Sync Active)" : "§c§lDISABLED";
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    "§6§l[Keep Items] " + status
                ));
            }
            
            if (!keepItemsEnabled) {
                inventorySnapshot.clear();
                lastClickedItems.clear();
            }
        }
    }
    
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickCounter++;
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !keepItemsEnabled) {
            inventorySnapshot.clear();
            return;
        }
        
        if (mc.currentScreen instanceof GuiContainer) {
            GuiContainer container = (GuiContainer) mc.currentScreen;
            
            // Update inventory snapshot every tick
            if (tickCounter % 5 == 0) { // Update every 5 ticks for performance
                inventorySnapshot.clear();
                for (Slot slot : container.inventorySlots.inventorySlots) {
                    if (slot.getStack() != null && isPlayerInventorySlot(slot)) {
                        inventorySnapshot.put(slot.slotNumber, slot.getStack().copy());
                    }
                }
            }
        } else {
            inventorySnapshot.clear();
        }
    }
    
    private boolean isPlayerInventorySlot(Slot slot) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && slot.inventory == mc.thePlayer.inventory;
    }
    
    public static boolean isKeepItemsEnabled() {
        return keepItemsEnabled;
    }
}