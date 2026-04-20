package com.apocscode.byteblock.block.entity;

import com.apocscode.byteblock.block.PrinterBlock;
import com.apocscode.byteblock.init.ModBlockEntities;
import com.apocscode.byteblock.menu.PrinterMenu;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Printer block entity — holds media (paper/books/clipboards) and an output slot.
 * Receives print jobs from adjacent computers or the print queue API.
 * Slot 0 = input media, Slot 1 = output (printed item).
 */
public class PrinterBlockEntity extends BlockEntity implements MenuProvider {
    private UUID deviceId = UUID.randomUUID();
    private final SimpleContainer container = new SimpleContainer(2);
    private final Deque<PrintJob> printQueue = new ArrayDeque<>();

    public record PrintJob(String title, String content) {}

    public PrinterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PRINTER.get(), pos, state);
    }

    public SimpleContainer getContainer() {
        return container;
    }

    /**
     * Queue a print job (called by computer programs).
     */
    public void queuePrint(String title, String content) {
        printQueue.add(new PrintJob(title, content));
        setChanged();
    }

    /**
     * Server tick — processes one queued print job per tick if media is available.
     */
    public void tick() {
        if (level == null || level.isClientSide()) return;
        BluetoothNetwork.register(level, deviceId, worldPosition, 1, BluetoothNetwork.DeviceType.PRINTER);
        if (level.getGameTime() % 20 == 0) {
            boolean connected = BluetoothNetwork.isComputerInRange(level, worldPosition);
            BlockState current = level.getBlockState(worldPosition);
            if (current.getValue(PrinterBlock.CONNECTED) != connected) {
                level.setBlockAndUpdate(worldPosition, current.setValue(PrinterBlock.CONNECTED, connected));
            }
        }
        if (printQueue.isEmpty()) return;

        ItemStack media = container.getItem(0);
        ItemStack output = container.getItem(1);
        if (media.isEmpty() || !output.isEmpty()) return;

        PrintJob job = printQueue.poll();
        if (job == null) return;

        ItemStack printed = createPrintedItem(media, job.title(), job.content());
        if (!printed.isEmpty()) {
            media.shrink(1);
            container.setItem(1, printed);
            setChanged();
        }
    }

    /**
     * Check if media in the input slot is valid for printing.
     */
    public boolean isValidMedia(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(Items.PAPER)) return true;
        if (stack.is(Items.WRITABLE_BOOK)) return true;
        // Create mod clipboard
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return itemId.toString().equals("create:clipboard");
    }

    private ItemStack createPrintedItem(ItemStack media, String title, String content) {
        if (media.is(Items.PAPER) || media.is(Items.WRITABLE_BOOK)) {
            return createWrittenBook(title, content);
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(media.getItem());
        if (itemId.toString().equals("create:clipboard")) {
            return createFilledClipboard(media.copy(), content);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack createWrittenBook(String title, String content) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();

        // Split content into pages (~256 chars each, max 100 pages)
        int pageSize = 256;
        for (int i = 0; i < content.length() && pages.size() < 100; i += pageSize) {
            String pageText = content.substring(i, Math.min(i + pageSize, content.length()));
            pages.add(new Filterable<>(Component.literal(pageText), Optional.empty()));
        }
        if (pages.isEmpty()) {
            pages.add(new Filterable<>(Component.literal(""), Optional.empty()));
        }

        String clampedTitle = title.length() > 32 ? title.substring(0, 32) : title;
        WrittenBookContent bookContent = new WrittenBookContent(
                new Filterable<>(clampedTitle, Optional.empty()),
                "ByteBlock Printer",
                0,
                pages,
                true
        );
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, bookContent);
        return book;
    }

    /**
     * Attempt to fill a Create mod clipboard with text content.
     * Writes to CUSTOM_DATA in Create's expected NBT format.
     */
    private ItemStack createFilledClipboard(ItemStack clipboard, String content) {
        CompoundTag tag = clipboard.getOrDefault(DataComponents.CUSTOM_DATA,
                CustomData.EMPTY).copyTag();

        ListTag pages = new ListTag();
        CompoundTag page = new CompoundTag();
        ListTag entries = new ListTag();

        String[] lines = content.split("\n");
        for (String line : lines) {
            CompoundTag entry = new CompoundTag();
            entry.putString("text", line);
            entry.putBoolean("checked", false);
            entries.add(entry);
        }
        page.put("Entries", entries);
        pages.add(page);
        tag.put("Pages", pages);
        tag.putInt("CurrentPage", 0);

        clipboard.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return clipboard;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.byteblock.printer");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new PrinterMenu(containerId, playerInv, container,
                ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("DeviceId", deviceId);
        // Save container items
        ListTag items = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                items.add(stack.save(registries, slotTag));
            }
        }
        tag.put("Items", items);

        // Save print queue
        if (!printQueue.isEmpty()) {
            ListTag queueTag = new ListTag();
            for (PrintJob job : printQueue) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.putString("Title", job.title());
                jobTag.putString("Content", job.content());
                queueTag.add(jobTag);
            }
            tag.put("PrintQueue", queueTag);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DeviceId")) deviceId = tag.getUUID("DeviceId");
        // Load container items
        ListTag items = tag.getList("Items", 10);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag slotTag = items.getCompound(i);
            int slot = slotTag.getByte("Slot") & 0xFF;
            if (slot < container.getContainerSize()) {
                container.setItem(slot, ItemStack.parse(registries, slotTag).orElse(ItemStack.EMPTY));
            }
        }

        // Load print queue
        printQueue.clear();
        if (tag.contains("PrintQueue")) {
            ListTag queueTag = tag.getList("PrintQueue", 10);
            for (int i = 0; i < queueTag.size(); i++) {
                CompoundTag jobTag = queueTag.getCompound(i);
                printQueue.add(new PrintJob(jobTag.getString("Title"), jobTag.getString("Content")));
            }
        }
    }
}
