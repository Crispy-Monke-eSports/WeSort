package com.porter.wesort.modules;

import com.google.gson.*;
import com.porter.wesort.WeSortAddon;
import com.porter.wesort.compat.InventorySync;
import com.porter.wesort.compat.PhysicalJournalRuntime;
import com.porter.wesort.compat.RightClick;
import com.porter.wesort.craft.AnvilFinder;
import com.porter.wesort.craft.TableFinder;
import com.porter.wesort.data.ShulkerUuid;
import com.porter.wesort.stash.Categories;
import com.porter.wesort.stash.DeliveryBook;
import com.porter.wesort.stash.DeliveryRoom;
import com.porter.wesort.stash.ItemPreviewScreen;
import com.porter.wesort.stash.Kits;
import com.porter.wesort.stash.PlacedShulkerRecovery;
import com.porter.wesort.stash.StackInfo;
import com.porter.wesort.stash.StackInfo.BulkKey;
import com.porter.wesort.stash.Stash;
import com.porter.wesort.stash.Stash.Box;
import com.porter.wesort.stash.StashIndex;
import com.porter.wesort.stash.StashSafety;
import com.porter.wesort.stash.StashWatch;
import com.porter.wesort.utils.BaritoneHelper;
import com.porter.wesort.utils.ChestStack;
import com.porter.wesort.utils.PlaceSearch;
import com.porter.wesort.utils.PortalEntry;
import com.porter.wesort.utils.PortalWorldSession;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Sorts a stash of chests and barrels, either into tiers (shulkers first, then stackables, then singles) or into
 * nearby storage selected by tier policy. The stash is scanned once and saved, then kept up to date
 * whenever a container in it is opened, even with the module off. It can stay on as a sorting bot, and the saved
 * stash can be searched from the module's settings or by whitelisted players over whispers.
 */
public class StashSorter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBot = settings.createGroup("Bot");
    private final SettingGroup sgGround = settings.createGroup("Ground");
    private final SettingGroup sgWatch = settings.createGroup("Watch");
    private final SettingGroup sgShulkers = settings.createGroup("Shulkers");
    private final SettingGroup sgSlots = settings.createGroup("Slots");
    private final SettingGroup sgSearch = settings.createGroup("Search");
    private final SettingGroup sgRender = settings.createGroup("Render");

    /** 6b6t lobby handling: walk through the nether portal to the survival world before working. */
    public enum PortalMode {
        Auto("Auto (6b6t only)"),
        On("On"),
        Off("Off");

        private final String title;

        PortalMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum LogLevel {
        Quiet("Quiet"),
        Normal("Normal"),
        Debug("Debug");

        private final String title;

        LogLevel(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum Pick {
        Off("Off"),
        Exclude("Exclude"),
        DropOff("Drop-off"),
        Input("Input chest"),
        CompactSkip("Compact skip"),
        Protect("Protect stock/staging"),
        Delivery("Delivery"),
        Corner1("Area corner 1"),
        Corner2("Area corner 2");

        private final String title;

        Pick(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // General

    /**
     * The stash this client works in. Only the Manager assigns it (announced over an
     * authenticated link and adopted below); nothing in the game configures it. Without an
     * assignment the local default "stash" is used, exactly like a fresh client before.
     */
    private String assignedStash = null;

    String stashName() {
        return assignedStash != null ? assignedStash : "stash";
    }

    /**
     * Bind the isolated Minecraft 26.3 E2E fixture without adding a user-configurable
     * stash setting back to Meteor. The filesystem test driver itself is marker-gated.
     * This method is unavailable in production and accepts no arbitrary warehouse.
     */
    public void selectE2eFixtureStash(String name) {
        if (!Boolean.getBoolean("wesort.e2e") || !"e2e-fixture".equals(name))
            throw new IllegalStateException("Fixture stash assignment requires isolated E2E");
        if (name.equals(assignedStash)) return;
        assignedStash = name;
        stash = null;
        triedKey = null;
    }

    /**
     * Adopt a Manager-announced stash name when no local stash has any containers. Never
     * overwrites local work. The region itself always comes from the Manager directive.
     */
    boolean adoptAnnouncedStash(String name) {
        if (name == null || name.isEmpty()) return false;
        if (stash != null && !stash.boxes.isEmpty()) return false;
        if (name.equals(assignedStash)) return true;
        assignedStash = name;
        stash = null;
        triedKey = null;
        info("Adopted Manager stash '%s'.", name);
        return true;
    }

    /**
     * Materialize the assigned stash as a local shell (no containers yet) so the link can push
     * a first index and the Manager directive can then fill in the region. Null when nothing is
     * assigned: a clean client waits for the Manager instead of inventing a stash.
     */
    Stash materializeAssignment() {
        if (stash != null) return stash;
        if (assignedStash == null || mc.level == null || mc.player == null) return null;
        stash = new Stash(assignedStash, Stash.currentServer());
        return stash;
    }

    private final Setting<Boolean> sortItems = sgGeneral.add(new BoolSetting.Builder()
            .name("sort-items")
            .description(
                    "Off: scan only. The module still reads containers it doesn't know or that went stale, watches containers, and keeps the saved stash current for wesort-stash-overlay and search, but never sorts: no taking, depositing, compacting, labelling, saved kits or picking up from the ground. Containers are only opened to read them. Orders from wesort-stash-manager still run, they are the only thing that moves items, and what an order leaves over is put back where it came from.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> safeWalk = sgGeneral.add(new BoolSetting.Builder()
            .name("safe-walk")
            .description(
                    "While the sorter walks, Baritone never parkours, never jumps off or falls more than one block, and the sorter sneaks at ledges with a drop of 2 or more blocks. A container with no such way to it is skipped.")
            .defaultValue(true)
            .build());

    private final Setting<Pick> pick = sgGeneral.add(new EnumSetting.Builder<Pick>()
            .name("pick")
            .description(
                    "Right-click containers to mark them. Exclude: never sorted into or out of. Drop-off: emptied into the stash, for others to drop items in. Protect stock/staging: never moved by automation. Compact skip: never used to gather stacks or shulkers for compacting. Delivery: where finished wesort-stash-manager orders are put (one per stash, never sorted into or out of). Right-click again to unmark. Area corner 1 and 2: the block you right-click becomes that corner of the stash perimeter. Works while the module is off.")
            .defaultValue(Pick.Off)
            .build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
            .name("radius")
            .description("How far around you to look for new containers to add to the stash.")
            .defaultValue(8)
            .range(2, 32)
            .sliderRange(2, 24)
            .build());

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
            .name("height")
            .description("How far above and below you to look for new containers.")
            .defaultValue(3)
            .range(0, 16)
            .sliderRange(0, 8)
            .build());

    private final Setting<Boolean> barrels = sgGeneral.add(new BoolSetting.Builder()
            .name("barrels")
            .description("Includes barrels as well as chests.")
            .defaultValue(true)
            .build());

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
            .name("reach")
            .description("How close a container has to be to open it.")
            .defaultValue(4.5)
            .range(1, 6)
            .sliderRange(1, 6)
            .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Ticks to wait between inventory clicks. Raise it if the server rejects clicks.")
            .defaultValue(3)
            .range(0, 20)
            .sliderRange(0, 10)
            .build());

    private final Setting<Integer> containerDelay = sgGeneral.add(new IntSetting.Builder()
            .name("container-delay")
            .description(
                    "Extra ticks of pause around opening containers: a container is not opened until this many ticks after the last one was closed, and the first click in a freshly opened one waits this long too. 0 is as fast as possible. A higher value is slower but gentler on the server; 2b2t can kick or rubber-band when containers are opened very quickly.")
            .defaultValue(0)
            .range(0, 100)
            .sliderRange(0, 40)
            .build());

    private final Setting<Integer> quiet = sgGeneral.add(new IntSetting.Builder()
            .name("quiet")
            .description(
                    "Seconds to wait after you or another module stops using the client (a screen, movement keys, Baritone, auto eat, auto-till, auto-dirt) before carrying on. Doubles for each repeated interruption within 5 minutes, up to 60 seconds.")
            .defaultValue(3)
            .range(0, 60)
            .sliderRange(0, 20)
            .build());

    private final Setting<Boolean> walk = sgGeneral.add(new BoolSetting.Builder()
            .name("walk")
            .description("Uses Baritone to walk to containers that are out of reach.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Faces containers when opening them.")
            .defaultValue(true)
            .build());

    private final Setting<LogLevel> logLevel = sgGeneral.add(new EnumSetting.Builder<LogLevel>()
            .name("log-level")
            .description(
                    "Quiet: what it is doing now and next, errors and alerts (alerts are also whispered to the whisper-search whitelist). Normal: the usual messages. Debug: also why it decides what it does. Everything is always written to the log.")
            .defaultValue(LogLevel.Normal)
            .build());

    // Bot

    private final Setting<Boolean> stayOn = sgBot.add(new BoolSetting.Builder()
            .name("stay-on")
            .description(
                    "Stays on after sorting and keeps the stash sorted: sorts again whenever a container changes, and empties drop-off containers.")
            .defaultValue(false)
            .build());

    private final Setting<Integer> recheck = sgBot.add(new IntSetting.Builder()
            .name("recheck")
            .description(
                    "Seconds between routine container observations (minimum one minute; keep well below stock TTL). Oldest observations are checked first.")
            .defaultValue(60)
            .range(30, 600)
            .sliderRange(30, 600)
            .visible(stayOn::get)
            .build());

    private final Setting<Boolean> smartRecheck = sgBot.add(new BoolSetting.Builder()
            .name("smart-recheck")
            .description(
                    "When enabled, unchanged drop-off containers back off up to ten minutes. Player activity and Manager requests still queue a re-read, subject to the minimum one-minute interval.")
            .defaultValue(true)
            .visible(stayOn::get)
            .build());

    private final Setting<PortalMode> sixb6t = sgBot.add(new EnumSetting.Builder<PortalMode>()
            .name("6b6t-mode")
            .description(
                    "On 6b6t the bot spawns in a lobby and must walk through the nether portal to the survival world first. Auto: only on 6b6t servers. While waiting for the survival world, no sorting, orders or reads start.")
            .defaultValue(PortalMode.Auto)
            .build());

    // Ground

    private final Setting<Boolean> collectGround = sgGround.add(new BoolSetting.Builder()
            .name("collect-ground")
            .description(
                    "Between jobs, walks onto items other players left lying in the stash area (the containers plus the range below, 4 blocks up and down) and sorts them with the rest. Needs Baritone. Never items you dropped or threw, or that came from blocks you broke, only items that have lain still for 3 seconds, and only while no other player is loaded. Not in scan-only mode.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> collectRange = sgGround.add(new IntSetting.Builder()
            .name("collect-range")
            .description("How many blocks around the outermost containers to pick items up from.")
            .defaultValue(8)
            .range(0, 64)
            .sliderRange(0, 32)
            .visible(collectGround::get)
            .build());

    // Watch

    private final Setting<Boolean> watch = sgWatch.add(new BoolSetting.Builder()
            .name("watch")
            .description(
                    "Notices when another player opens a container of the stash and closes it again, flags it for re-read after the delay below, and logs it operator-visible. Works while the module is off.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> watchDelay = sgWatch.add(new IntSetting.Builder()
            .name("watch-delay")
            .description("Seconds a container has to stay closed after someone else used it before it's read again.")
            .defaultValue(5)
            .range(0, 60)
            .sliderRange(0, 60)
            .visible(watch::get)
            .build());

    // Shulkers

    private final Setting<Boolean> compact = sgShulkers.add(new BoolSetting.Builder()
            .name("compact")
            .description(
                    "Tier mode, once everything is sorted: packs a full shulker's worth of one item (27 full stacks, or two of the same gear piece and up) from the category chests into an empty unnamed shulker and files it in the item's chest, so 27 slots become 1. Never mixed shulkers, never a named one, never kit chests. Shulkers are put down beside you for it.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> compactTopUpAt = sgShulkers.add(new IntSetting.Builder()
            .name("compact-top-up-at")
            .description("Tops up a part-full shulker of one item once this many full stacks of it are waiting.")
            .defaultValue(9)
            .range(1, 26)
            .sliderRange(1, 26)
            .visible(compact::get)
            .build());

    private final Setting<List<Item>> compactExclude = sgShulkers.add(new ItemListSetting.Builder()
            .name("compact-exclude")
            .description("Items that are never compacted, like ender pearls.")
            .defaultValue(List.of())
            .visible(compact::get)
            .build());

    private final Setting<Boolean> repack = sgShulkers.add(new BoolSetting.Builder()
            .name("repack")
            .description(
                    "Tier mode: takes items that belong elsewhere out of mixed shulkers, and packs loose items into shulkers that already hold only that item. Places the shulker beside you to do it, then breaks it and picks it back up. Named shulkers, and shulkers when a sign asks for them whole (like \"kits\"), are left as they are.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> newBulkAt = sgShulkers.add(new IntSetting.Builder()
            .name("new-bulk-at")
            .description(
                    "Starts a new bulk shulker from an empty one once carrying this many stacks of one item. 0 only tops up shulkers that already exist. Only used while compact is off, which makes new bulk shulkers itself.")
            .defaultValue(9)
            .range(0, 27)
            .sliderRange(0, 27)
            .visible(repack::get)
            .build());

    // Slots

    private final Setting<String> carrySlots = sgSlots.add(new StringSetting.Builder()
            .name("carry-slots")
            .description(
                    "Slots to carry stash items in, like \"28-36\" or \"1-3, 30\". 1-9 is the hotbar, 10-36 the inventory from top left. Blank uses every slot that's empty when sorting starts.")
            .defaultValue("")
            .build());

    private final Setting<String> keepSlots = sgSlots.add(new StringSetting.Builder()
            .name("keep-slots")
            .description("Slots never to touch, even when they're empty, in the same format as carry-slots.")
            .defaultValue("")
            .build());

    // Search

    private final Setting<Boolean> whisperSearch = sgSearch.add(new BoolSetting.Builder()
            .name("whisper-search")
            .description(
                    "Lets whitelisted players whisper you to search the stash. You whisper back where it is. Works while the module is off.")
            .defaultValue(false)
            .build());

    private final Setting<List<String>> whitelist = sgSearch.add(new StringListSetting.Builder()
            .name("whitelist")
            .description("Players allowed to search the stash by whisper.")
            .visible(whisperSearch::get)
            .build());

    private final Setting<String> prefix = sgSearch.add(new StringSetting.Builder()
            .name("prefix")
            .description(
                    "Whispers starting with this are searches, like \"find diamonds\". Blank treats every whisper from the whitelist as a search.")
            .defaultValue("find")
            .visible(whisperSearch::get)
            .build());

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description(
                    "Highlights the container being worked on, and excluded and drop-off containers while picking or sorting.")
            .defaultValue(true)
            .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .visible(render::get)
            .build());

    private final Setting<SettingColor> areaColor = sgRender.add(new ColorSetting.Builder()
            .name("area-color")
            .description(
                    "The outline of the stash perimeter, drawn while wesort-stash-sorter or wesort-stash-overlay is on.")
            .defaultValue(new SettingColor(80, 255, 120, 255))
            .build());

    private final Setting<SettingColor> workColor = sgRender.add(new ColorSetting.Builder()
            .name("work-color")
            .description("The container being worked on.")
            .defaultValue(new SettingColor(255, 170, 0, 60))
            .visible(render::get)
            .build());

    private final Setting<SettingColor> excludedColor = sgRender.add(new ColorSetting.Builder()
            .name("excluded-color")
            .description("Containers excluded from sorting.")
            .defaultValue(new SettingColor(255, 50, 50, 60))
            .visible(render::get)
            .build());

    private final Setting<SettingColor> compactSkipColor = sgRender.add(new ColorSetting.Builder()
            .name("compact-skip-color")
            .description("Containers never used for compacting.")
            .defaultValue(new SettingColor(180, 100, 255, 60))
            .visible(render::get)
            .build());

    private final Setting<SettingColor> dropOffColor = sgRender.add(new ColorSetting.Builder()
            .name("drop-off-color")
            .description("Drop-off containers.")
            .defaultValue(new SettingColor(50, 150, 255, 60))
            .visible(render::get)
            .build());

    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TIMEOUT = 20 * 4;
    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int PATH_GRACE_TICKS = 10;
    // Whispers back are spaced out so the server doesn't kick us for spam
    private static final int REPLY_GAP_TICKS = 20 * 3;

    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    // Baritone is optional, BaritoneHelper must only be touched when it's installed
    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");

    private static final Pattern[] WHISPER_FORMATS = {
        Pattern.compile("^(\\w{3,16}) whispers(?: to you)?: (.+)$"),
        Pattern.compile("^From (\\w{3,16}): (.+)$"),
        Pattern.compile("^\\[(\\w{3,16}) -> (?:me|you)] (.+)$"),
        Pattern.compile("^(\\w{3,16}) -> (?:me|you): (.+)$")
    };

    private enum Job {
        Survey,
        Take,
        Deposit,
        Dump,
        Fetch,
        Clean,
        Fill,
        Recover,
        Compact,
        Deliver,
        Return,
        KitTake,
        KitUnpack,
        KitFill,
        Gather,
        Ground,
        Explore,
        Unbundle,
        Name,
        CraftTake,
        CraftUnpack,
        Craft,
        AnvilPlace,
        ChestPlace
    }

    private enum Step {
        Walking,
        Opening,
        Working
    }

    // Repacking a shulker in the world: put it down, open it, move items, break it, pick it back up
    private enum Handle {
        Place,
        Wander,
        Open,
        Work,
        Verify,
        Break,
        Collect,
        Return,
        Leave,
        Resume
    }

    // Giving way: running, letting go of what is ours, then waiting for the others to finish and be quiet
    private enum Gate {
        Running,
        Yielding,
        Waiting
    }

    private static final int MAX_JOB_INTERRUPTIONS = 5;
    private static final int CURSOR_TRIES = 40;

    private static final int HANDLE_TIMEOUT = 20 * 10;
    private static final int COLLECT_TIMEOUT = 20 * 120;
    /** Extra bounded settle window after COLLECT_TIMEOUT when the box is visibly held (cursor/magnet delay). */
    private static final int COLLECT_SETTLE_TICKS = 20 * 60;
    /** A lost-shulker latch expires instead of parking the headless bot forever (#198).
     * Drops despawn after 5 minutes, so waiting longer for a drop is pointless. */
    private static final long DROP_LOST_TIMEOUT_MS = 5 * 60_000;

    private static final int PLACE_GAP_TICKS = 20 * 5;
    private static final int PAUSE_TICKS = 20 * 60 * 3;

    private Stash stash;
    private String triedKey;
    private final Set<Integer> carry = new LinkedHashSet<>();

    // Tiers mode: which boxes each tier fills
    private final Map<Integer, List<Box>> tierBoxes = new HashMap<>();

    private Job job;
    private Box target;
    private Step step;
    private int stepTicks;
    // How long the container job in hand has been running, and the most it may
    private int jobTicks;
    private static final int JOB_WATCHDOG_TICKS = 20 * 60 * 4;
    // A read has three short stages (walk at most WALK_TIMEOUT, open at most OPEN_TIMEOUT, read): this is the last
    // resort
    // for one that still hangs, well under the general watchdog
    private static final int SURVEY_WATCHDOG_TICKS = 20 * 90;
    // A walk to a container to read that gets no closer than a block in this long (30 s) is given up on; not a setting
    private static final int SURVEY_WALK_STALL_TICKS = 20 * 30;
    private int wait;
    private int swapStep;
    private int swapCarry, swapSlot;
    private int recoveries;
    private ItemStack swapStack = ItemStack.EMPTY, swapDisplaced = ItemStack.EMPTY;
    private boolean usedBaritone;
    // How often a walk to a container ended with our goal gone from Baritone
    private final Map<Box, Integer> cancelRetries = new HashMap<>();

    // Giving way to the player and other modules
    private Gate gate = Gate.Running;
    private int ticks;
    private int lastOwnAct = -100;
    // The tick the last container of ours was closed, for container-delay
    private int lastCloseTick = -100_000;
    private int cleanupTries;
    private int jobInterruptions;
    private Handle returnTo;
    // The container window the sorter opened, and the Baritone goal it set (an Object so Baritone's classes are only
    // touched when it's installed), with when it was set and let go of
    private AbstractContainerMenu ownedMenu;
    private Object ourGoal;
    private int goalTick = -100, releaseTick = -100;
    // Set while the sorter presses keys itself
    private boolean foreignNow;

    private int openClickTick = -100;
    private static final int OPEN_ANSWER_TICKS = 20 * 5;
    private final StashYield yielder = new StashYield(new StashYield.Owner() {
        @Override
        public boolean ourMenu(AbstractContainerMenu menu) {
            return StashSorter.this.ourMenu(menu);
        }

        @Override
        public boolean baritoneForeign() {
            if (!BARITONE || ticks - goalTick <= 3 || ticks - releaseTick <= 3) return false;
            return BaritoneHelper.controlledByOther((baritone.api.pathing.goals.Goal) ourGoal);
        }

        @Override
        public boolean baritoneActive() {
            return BARITONE && BaritoneHelper.isActive();
        }

        @Override
        public boolean drivingInput() {
            return placer.drivingInput() || ledgeSneaking;
        }

        @Override
        public boolean justReleased() {
            return ticks - releaseTick <= 2;
        }

        @Override
        public boolean walkingOurs() {
            return ourGoal != null;
        }

        @Override
        public boolean ownInventoryActivity() {
            return handle != null || ticks - lastOwnAct < 20;
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public int quietSeconds() {
            return quiet.get();
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void step(String text) {
            StashSorter.this.step(text);
        }
    });
    private int moved;
    // Containers read by surveys since the last summary, for scan-only mode
    private int scanned;

    // The stash perimeter: chunk columns seen loaded since it was set, the ones that couldn't be reached, and the trip
    // in
    // hand. Walking inside it is allowed, and the sorter explores it so its chunks load
    private final LongOpenHashSet exploredChunks = new LongOpenHashSet();
    private final LongOpenHashSet exploreSkipped = new LongOpenHashSet();
    private boolean exploring, exploreRan, areaDirty;
    private int exploreRunStart;
    private static final int EXPLORE_RUN_LIMIT = 20 * 60 * 30;
    private int exploreStanding;
    private long exploreTarget;
    private int exploreX, exploreZ;
    private int exploreTicks, exploreIssue, exploreBestTick;
    private double exploreBest;
    private boolean walkInside, baritoneWarned;
    // Containers already reported as impossible to reach without breaking or placing blocks
    private final Set<BlockPos> unreachableReported = new HashSet<>();
    private static final int EXPLORE_STANDING = 40;
    private static final int EXPLORE_TIMEOUT = 20 * 40;
    private static final int EXPLORE_STUCK = 20 * 15;
    private static final int AREA_MARGIN = 2;

    // Picking up what other players left on the ground
    private final GroundCollector ground = new GroundCollector(new GroundCollector.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean enabled() {
            return groundOn();
        }

        @Override
        public int range() {
            return collectRange.get();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public boolean carries(int slot) {
            return carry.contains(slot);
        }

        @Override
        public int moveToCarry(int slot) {
            int free = StashSorter.this.freeCarrySlot();
            if (free == -1
                    || mc.screen != null
                    || !mc.player.containerMenu.getCarried().isEmpty()) return -1;
            InvUtils.move().from(slot).to(free);
            lastOwnAct = ticks;
            yielder.inventoryChangedByUs();
            return free;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void walkTo(BlockPos pos) {
            if (!BARITONE) return;
            walkNear(pos, 0);
        }

        @Override
        public void releaseGoal() {
            StashSorter.this.releaseGoal();
        }

        @Override
        public void confine() {
            // Every walk of the sorter's confines Baritone itself, see walkNear
        }

        @Override
        public void unconfine() {
            if (BARITONE && ourGoal == null) BaritoneHelper.unconfine();
        }

        @Override
        public void finished(boolean pickedUp) {
            if (job == Job.Ground) job = null;
            wait = delay.get();
            lastOwnAct = ticks;
            if (pickedUp) {
                // What came in is put away like anything else carried
                idle = false;
                clearStalled();
                stuckCarried.clear();
                stuckWarned = false;

                // In batches: while there is room for two more stacks and more is lying there, the next one is fetched
                // before anything is put away
                if (freeCarryCount() >= 2 && groundBatch < GROUND_BATCH_MAX) {
                    groundBatch++;
                    if (startGround()) return;
                }
            }
            groundBatch = 0;
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }

        @Override
        public void step(String text) {
            StashSorter.this.step(text);
        }
    });

    // Containers where the last job got nothing done, skipped until something moves somewhere else
    private final Set<Box> stalled = new HashSet<>();
    private final Set<Box> compacted = new HashSet<>();
    private int actions;

    // Loop protection. What the carry slots and the target container held when the job's window opened and just before
    // it closed, to tell whether the clicks changed anything
    private long startSig, endSig;
    private boolean startSigSet, endSigSet;
    // Consecutive jobs per container that clicked but ended before the result could be read, and containers that closed
    // on us this pass (said once)
    private final Map<Box, Integer> unconfirmedRuns = new HashMap<>();
    private final Set<Box> closedEarlyReported = new HashSet<>();
    // Ticks the working window has been gone or the wrong size in a row: transient hiccups don't end the job
    private int closedEarlyTicks;
    // Jobs at each container since the backlog last reached a new low, the ones given up on for going back and forth,
    // and
    // the ones already reported this pass
    private final Map<Box, Integer> boxVisits = new HashMap<>();
    private final Set<Box> circular = new HashSet<>();
    private final Set<Box> loopReported = new HashSet<>();
    private boolean jobCounted;

    // Watching other players' container lid activity from the client's own world
    private final StashWatch stashWatch = new StashWatch();
    private static final int LID_POLL_TICKS = 4;
    private static final int OWN_TOUCH_TICKS = 20 * 10;
    private final Map<Box, Integer> ownTouch = new HashMap<>();
    // Dirty observations are retained even during cooldown; they are never used as fresh stock.
    private final Set<Box> staleBoxes = new LinkedHashSet<>();
    private final Map<Box, Long> surveyAttemptAt = new HashMap<>();
    private final Queue<Box> surveyBatch = new ArrayDeque<>();
    // Explicit rescans (including Manager revision conflicts) need new receipts,
    // not a five-minute wait on observations from an obsolete index.
    private final Set<Box> forcedSurvey = new HashSet<>();
    private final Set<Box> requestedSurvey = new HashSet<>();
    private long nextIdleSurveyAt, nextBackgroundBatchAt;
    // Jobs that opened a container and clicked nothing, with what the carry slots and the container held then: not
    // repeated until one of them changes, however often stalled is cleared meanwhile
    private final Map<String, Long> futile = new HashMap<>();
    private final Set<String> noOpReported = new HashSet<>();
    private String lastSummary;
    private int passJobs;
    private int passMin = Integer.MAX_VALUE;
    // Containers that could not be read when visited: not tried again until something changes
    private final Set<Box> failedSurvey = new HashSet<>();
    // When each of them is tried again: 1 min, 2 min, then every 5 min
    private final com.porter.wesort.utils.SurveyRetry<Box> surveyRetry = new com.porter.wesort.utils.SurveyRetry<>();
    private final com.porter.wesort.utils.SurveyRetry<Box> returnRetry = new com.porter.wesort.utils.SurveyRetry<>();
    private final com.porter.wesort.utils.WalkProgress walkProgress = new com.porter.wesort.utils.WalkProgress();

    // Carried stacks nothing has room for right now: slot to item id, left out of the plans until something changes
    private final Map<Integer, String> stuckCarried = new HashMap<>();
    private boolean stuckWarned;

    // Carry slots the sorter filled itself, slot to the stack it put there. The player's own gear in a carry slot is
    // never touched unless it's in here
    private final Map<Integer, ItemStack> stashHeld = new HashMap<>();
    private net.minecraft.client.player.LocalPlayer lastPlayer;
    // When stuck gear was last warned about, and when the stuck stacks were last looked at again
    private static final int STUCK_REWARN = 20 * 60 * 3;
    private static final int STUCK_RECHECK = 20 * 5;
    private int lastStuckWarn = -STUCK_REWARN, lastStuckCheck, stuckCheckVersion;
    // Ticks to let the server answer the last click before the result is judged
    private static final int SETTLE_TICKS = 2;
    private boolean settled;

    // Repacking
    private com.porter.wesort.inventory.PhysicalWork repackWork;
    private ItemStack repackOuter = ItemStack.EMPTY;
    private String repackExpected = "";
    private long repackBlockedProof = -1;
    private String repackConflict = "";
    private ItemStack verifiedReturnStack = ItemStack.EMPTY;
    private boolean returningChangedShulker;
    private String lastRepackAlert = "";
    private long lastRepackAlertAt;
    private Map<String, Long> repackConservation;
    private final Map<String, Integer> repackStoreBefore = new HashMap<>();
    private long repackStoreAt;
    private int repackExistingCopies;
    private boolean handlingProof;
    // Verified box observations since the current STORE wait began, and full passes
    // without a match: after a few, the stored shulker is declared missing (fail closed).
    private int repackVerifyReads;
    private final Set<String> repackVerifySeen = new HashSet<>();
    private static final int MAX_REPACK_VERIFY_PASSES = 3;
    // #198: a Collect-timeout re-baselines an unambiguous late pickup at most once per
    // handle (adopt same-colour/same-content box or recount a fingerprint match), so a
    // flapping baseline can never mask a genuinely lost shulker.
    private boolean repackRebaselined;
    // Last pre-gate reason that parked the tick, for the status line and heartbeat. Set on
    // every silent early return, cleared once the tick gets past the gates.
    private String gateReason = "";
    private int lastGateLogClock = -100_000;
    private static final int GATE_LOG_CLOCKS = 200;
    // Wall-clock arm for the resume/STORE watchdog: tick counters freeze behind the
    // recovery gates, so a stuck resume or STORE wait is timed in milliseconds instead.
    private long storeWatchMs;
    private static final long RESUME_MAX_TICKS_MS = 20L * 60 * 10 * 50;
    private int lastResumeLogClock = -100_000;
    // A stack on the cursor with nowhere to go: when it sits too long, say so once and
    // stop clicking until the player frees a slot, instead of spinning silently.
    private int cursorFirstSeen = -1;
    private boolean cursorBlocked;
    // Waiting-timeout escalation for the yield gate: alert once, then step periodically
    // and escalate jobInterruptions so a lingering handle is eventually dropped.
    private static final int WAIT_TIMEOUT_TICKS = 20 * 60 * 5;
    private int lastWaitLogClock = -100_000;
    private int lastWaitEscalation = -100_000;
    private boolean waitTimeoutAlerted;
    // Unexpected toggle combos: sorter on while the Agent toggle is off. Throttled.
    private int lastToggleWarnClock = -100_000;

    public String shulkerPhase() {
        return handle == null ? "" : handle.name();
    }

    public BlockPos repackPosition() {
        return placed;
    }

    public com.google.gson.JsonObject repackProgress() {
        return repackWork == null ? new com.google.gson.JsonObject() : repackWork.json();
    }

    private int wantSlot = -1;
    private ItemStack fetchExpected = ItemStack.EMPTY;
    private int handledSlot;
    // Where the shulker handled came from, before it was moved to the hotbar to be placed
    private int handledOrigin;
    private BulkKey fillKey;
    private Item mainItem;

    // Offline safety net: a shulker of ours standing in the world is written to the stash file with what it is for, so
    // a
    // restart (disconnect, crash, stopping the module) finishes the step instead of losing it
    private static final int RESUME_MAX_TICKS = 20 * 60 * 10;
    private Stash.InFlight pendingResume;
    private boolean resuming, resumeVerify, resumeFinishOnly, resumeDimensionSaid;
    private int resumeDimLogTick = -100_000;
    private boolean recoveringOrphan;
    private ItemStack[] recoveredContents;
    private final Map<BlockPos, Integer> recoveryRetryAt = new HashMap<>();
    private int resumeStarted;
    private String placedItemId = "";
    private BlockPos lastGoodStand;
    private ItemStack fillSample = ItemStack.EMPTY;
    private Handle syncedHandle;
    private BlockPos syncedPlaced;
    private boolean syncedConfirmed;
    // Leaving the world: nothing is whispered
    private boolean offline;
    // Ticks in the world since joining: alert whispers wait until the server has settled
    private int worldTicks;
    private static final int ALERT_JOIN_TICKS = 20 * 15;
    // The dimension we were last in, for a record written while the world is being left
    private String cachedDimension = "";

    // Safe shulker handling: spots where a place failed, how many in a row, when the last was, a pause after problems,
    // and a shulker that could not be picked up (no more are put down)
    private final Map<BlockPos, Integer> placementRetryAt = new HashMap<>();
    private int placeFailures;
    private int lastPlaceTick = -1000;
    private int repackPausedUntil;
    private boolean dropLost;
    /** Wall-clock arm for the lost-shulker latch: it expires instead of parking forever (#198). */
    private long dropLostAtMs;

    private boolean placedConfirmed;
    private int placedStacks;
    private int breakTicks;
    private Predicate<ItemStack> fetchWants = s -> Utils.isShulker(s.getItem());
    private Handle handle;
    private int handleTicks;
    private BlockPos placed;
    // A hotbar slot a shulker was moved into, and the player's item that was in it, now in swapBackFrom
    private int swapBackFrom = -1, swapBackTo = -1;
    private ItemStack hotbarDisplaced = ItemStack.EMPTY;
    private boolean pickaxeSwapped;
    private int shulkersBefore;
    private final Set<Integer> shulkerSlotsBefore = new HashSet<>();
    // Shulkers held the last tick the shulker in the world was still standing, -1 if it never was seen standing
    private int shulkersAtBreak = -1;
    private final Set<BulkKey> packTried = new HashSet<>();

    // Packing a full shulker's worth of one item
    private final Compactor compactor = new Compactor(new Compactor.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean on() {
            return compactOn();
        }

        @Override
        public int topUpAt() {
            return compactTopUpAt.get();
        }

        @Override
        public int newBulkAt() {
            return newBulkAt.get();
        }

        @Override
        public boolean excluded(ItemStack stack) {
            return compactExclude.get().contains(stack.getItem());
        }

        @Override
        public List<Box> destinationsFor(ItemStack stack) {
            return StashSorter.this.destinationsFor(stack);
        }

        @Override
        public boolean isKitBox(Box box) {
            return StashSorter.this.isKitBox(box);
        }

        @Override
        public boolean isKitShulker(ItemStack stack) {
            return StashSorter.this.isKitShulker(stack);
        }

        @Override
        public boolean gatherBlocked(Box box) {
            return blocked(box, Job.Gather);
        }

        @Override
        public boolean fetchBlocked(Box box) {
            return blocked(box, Job.Fetch);
        }

        @Override
        public Box nearest(List<Box> boxes) {
            return StashSorter.this.nearest(boxes);
        }

        @Override
        public boolean closer(Box a, Box b) {
            return StashSorter.this.closer(a, b);
        }

        @Override
        public List<Integer> carriedSlots() {
            return StashSorter.this.carriedSlots();
        }

        @Override
        public List<Integer> activeCarried() {
            return StashSorter.this.activeCarried();
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean kitHolds(int slot) {
            return kitBuilder.holds(slot);
        }

        @Override
        public boolean fetch(Box box, int slot, Predicate<ItemStack> wants) {
            wantSlot = slot;
            fetchWants = wants;
            return start(Job.Fetch, box);
        }

        @Override
        public boolean gather(Box box) {
            return start(Job.Gather, box);
        }

        @Override
        public boolean pack(Compactor.Plan plan) {
            if (plan.placedPos != null) {
                startHandling(Job.Fill, -1, plan.key);
                placed = plan.placedPos;
                placedConfirmed = true;
                shulkersBefore = countShulkers() + 1;
                handle = Handle.Return;
                returnTo = Handle.Open;
                return true;
            }
            int slot = compactor.findPlanShulker();
            if (slot < 0) {
                compactor.abort("the shulker is missing");
                return compactor.next();
            }
            return startHandling(Job.Fill, slot, plan.key);
        }

        @Override
        public boolean recover(Compactor.Plan plan, BlockPos at) {
            startHandling(Job.Fill, -1, plan.key);
            placed = at;
            placedConfirmed = true;
            handle = Handle.Collect;
            repackRebaselined = false;
            shulkersBefore = countShulkers() + 1;
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++)
                if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
            return true;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void moved() {
            moved++;
        }

        @Override
        public void headway() {
            // Real headway: the circle watchdog starts over
            passMin = Integer.MAX_VALUE;
            passJobs = 0;
            boxVisits.clear();
            stalled.removeAll(circular);
            circular.clear();
            clearStalled();
        }

        @Override
        public void clearOrphan(BlockPos at) {
            StashSorter.this.clearOrphan(at);
        }

        @Override
        public void progress(BlockPos at, int stacks) {
            Stash.Orphan old = stash.orphans.stream()
                    .filter(o -> o.pos.equals(at))
                    .findFirst()
                    .orElse(null);
            recordOrphan(at, old != null ? old.item : placedName(), stacks);
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public boolean dropOffsBusy() {
            return stash.boxes.stream()
                    .anyMatch(b -> b.dropOff && b.known() && !b.unreachable && b.free() < b.contents.length);
        }

        @Override
        public int ticks() {
            return ticks;
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }

        @Override
        public void alert(String text) {
            StashSorter.this.alert("%s", text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }

        @Override
        public void step(String text) {
            StashSorter.this.step(text);
        }

        @Override
        public void changed() {
            syncInFlight();
        }

        @Override
        public String craftEmpty(String purpose) {
            return craftEmptyForCompaction(purpose);
        }
    });

    // Building kits
    private final KitBuilder kitBuilder = new KitBuilder(new KitBuilder.Owner() {
        @Override
        public boolean mayTakeFrom(Box box, int slot) {
            return orderRunner.mayTakeFrom(box, slot);
        }

        @Override
        public boolean permitted() {
            return sortingPermitted();
        }

        @Override
        public void packed() {
            PhysicalJournalRuntime.phase("STORE", new JsonObject());
            info("Kit packed exactly.");
        }

        @Override
        public void uncertain(String reason) {
            PhysicalJournalRuntime.block("Kit packing paused for verified automatic recovery");
            warning("Kit packing paused for automatic recovery: %s", reason);
        }

        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean usable(Box box) {
            return !blocked(box, Job.Take) && !box.unreachable;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
            noteOrderHeld(slot, stack);
        }

        @Override
        public boolean keepWhole(Box box) {
            return box.protectedStorage || Kits.isKitBox(box);
        }

        @Override
        public double distance(Box box) {
            return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }

        @Override
        public boolean claims(ItemStack stack) {
            return orderRunner.claims(stack);
        }

        @Override
        public boolean namedShulkers() {
            return namedForOrders();
        }
    });

    // Orders from the WeSort Manager server: kits built from exact items, delivered to the delivery container
    private final OrderRunner orderRunner = new OrderRunner(kitBuilder, new OrderRunner.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean gate() {
            return ordersOn();
        }

        @Override
        public boolean usable(Box box) {
            return !blocked(box, Job.Take) && !box.unreachable;
        }

        @Override
        public boolean keepWhole(Box box) {
            return box.protectedStorage || Kits.isKitBox(box);
        }

        @Override
        public boolean namedShulkers() {
            return namedForOrders();
        }

        @Override
        public boolean rereading() {
            return !staleBoxes().isEmpty();
        }

        @Override
        public int staleCount() {
            return staleBoxes().size();
        }

        @Override
        public boolean cleanupComplete() {
            // Returning a stack is a real inventory operation: wait for
            // native readback and the sorter to finish closing its window.
            if (mc.player == null
                    || !InventorySync.ready()
                    || PhysicalJournalRuntime.blocked()
                    || job != null
                    || pendingResume != null
                    || kitBuilder.active()
                    || crafter.active()) return false;
            leftoverSlots();
            return orderHeld.isEmpty();
        }

        @Override
        public boolean trackedPackage(String fingerprint) {
            if (mc.player == null) return false;
            for (var entry : heldFrom.entrySet()) {
                Box source = entry.getValue();
                int slot = entry.getKey();
                if (source == null
                        || !(source.delivery || source.deliveryExtra)
                        || !orderHeld.containsKey(slot)
                        || slot < 0
                        || slot >= mc.player.getInventory().getContainerSize()) continue;
                ItemStack carried = mc.player.getInventory().getItem(slot);
                if (!carried.isEmpty()
                        && com.porter.wesort.compat.SlotRuntime.snapshot(new ItemStack[] {carried})
                                .fingerprint()
                                .equals(fingerprint)) return true;
            }
            return false;
        }

        @Override
        public double distance(Box box) {
            return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
        }

        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public String anvilProblem() {
            return StashSorter.this.anvilProblem();
        }

        @Override
        public BlockPos anvilPos() {
            return stash == null ? null : stash.anvilPos;
        }

        @Override
        public Crafter crafter() {
            return crafter;
        }

        @Override
        public boolean craftingOn() {
            return StashSorter.this.craftingOn();
        }

        @Override
        public String craftBlocker() {
            return StashSorter.this.craftBlocker();
        }

        @Override
        public boolean anvilMissing() {
            return StashSorter.this.anvilMissing();
        }

        @Override
        public DeliveryRoom deliveryRoom() {
            return deliveryRoomNow();
        }

        @Override
        public int deliveryChests() {
            return Math.max(1, book().chests());
        }

        @Override
        public Box deliveryChest(boolean verify) {
            return deliveryChestFor(verify);
        }

        @Override
        public int xpLevel() {
            return mc.player == null ? -1 : mc.player.experienceLevel;
        }

        @Override
        public int xpBottlesCarried() {
            return StashSorter.this.xpBottlesCarried();
        }

        @Override
        public OrderRunner.XpBottles xpBottleSource() {
            return StashSorter.this.xpBottleSource();
        }
    });
    private int collectedSlot = -1;
    private int seenKits;

    // Bot mode
    private boolean idle;
    private int idleTicks;
    private int seenVersion;
    private int version;

    // Containers the player opens themselves
    private AbstractContainerMenu trackedMenu;
    private Box trackedBox;

    // The last block interact sent, so a menu is only filed under the box it was really opened from
    private static final int INTERACT_WINDOW = 20 * 5;
    private int clock;
    private BlockPos interactPos;
    private AbstractContainerMenu interactMenu;
    private int interactClock;

    // The sorter's own open click, same idea
    private BlockPos openPos;
    private AbstractContainerMenu openMenuBefore;

    private final Queue<String> replies = new ArrayDeque<>();
    private int replyCooldown;

    public StashSorter() {
        super(
                WeSortAddon.CATEGORY,
                "wesort-stash-sorter",
                "Sorts a saved stash by tier and lets you search its inventory.");

        // Always listening: keeping the stash up to date, picking containers and whisper searches all work with the
        // module off
        MeteorClient.EVENT_BUS.subscribe(new Tracker());
        RightClick.listen(this::onRightClick);
    }

    // Manager-only operational settings. The sorter is never added to Meteor's module registry,
    // so these values are exposed through the signed bot settings protocol and its web form.
    private record RemoteControl(String name, String label, String type, double min, double max) {}

    private static final List<RemoteControl> REMOTE_CONTROLS = List.of(
            new RemoteControl("safe-walk", "Safe walking", "bool", 0, 0),
            new RemoteControl("radius", "Search radius", "int", 2, 32),
            new RemoteControl("height", "Search height", "int", 0, 16),
            new RemoteControl("barrels", "Include barrels", "bool", 0, 0),
            new RemoteControl("reach", "Interaction reach", "double", 1, 6),
            new RemoteControl("delay", "Inventory click delay", "int", 0, 20),
            new RemoteControl("container-delay", "Container open delay", "int", 0, 100),
            new RemoteControl("quiet", "Interruption quiet time", "int", 0, 60),
            new RemoteControl("walk", "Allow navigation", "bool", 0, 0),
            new RemoteControl("rotate", "Rotate while interacting", "bool", 0, 0),
            new RemoteControl("log-level", "Log level", "enum", 0, 0),
            new RemoteControl("stay-on", "Stay on when idle", "bool", 0, 0),
            new RemoteControl("recheck", "Recheck interval", "int", 30, 600),
            new RemoteControl("smart-recheck", "Smart recheck", "bool", 0, 0),
            new RemoteControl("collect-ground", "Collect ground items", "bool", 0, 0),
            new RemoteControl("collect-range", "Ground collection range", "int", 0, 64),
            new RemoteControl("watch", "Watch inventory", "bool", 0, 0),
            new RemoteControl("watch-delay", "Watch delay", "int", 0, 60),
            new RemoteControl("compact", "Compact stacks", "bool", 0, 0),
            new RemoteControl("compact-top-up-at", "Compaction top-up threshold", "int", 1, 26),
            new RemoteControl("repack", "Repack shulkers", "bool", 0, 0),
            new RemoteControl("new-bulk-at", "Bulk packing threshold", "int", 0, 27),
            new RemoteControl("carry-slots", "Carry inventory slots", "string", 0, 0),
            new RemoteControl("keep-slots", "Reserved inventory slots", "string", 0, 0),
            new RemoteControl("whisper-search", "Whisper search", "bool", 0, 0),
            new RemoteControl("prefix", "Search prefix", "string", 0, 0));

    public JsonObject managerValues() {
        var result = new JsonObject();
        for (var control : REMOTE_CONTROLS) {
            Object value = settings.get(control.name()).get();
            String path = "sorter." + control.name();
            if (value instanceof Boolean flag) result.addProperty(path, flag);
            else if (value instanceof Number number) result.addProperty(path, number);
            else if (value instanceof Enum<?> choice) result.addProperty(path, choice.name());
            else if (value instanceof String string) result.addProperty(path, string);
        }
        return result;
    }

    public JsonArray managerSchema() {
        var result = new JsonArray();
        for (var control : REMOTE_CONTROLS) {
            var row = new JsonObject();
            row.addProperty("path", "sorter." + control.name());
            row.addProperty("label", control.label());
            row.addProperty("type", control.type());
            row.addProperty("section", "Warehouse engine");
            if (control.type().equals("int") || control.type().equals("double")) {
                row.addProperty("min", control.min());
                row.addProperty("max", control.max());
            }
            if (control.type().equals("enum")) {
                var choices = new JsonArray();
                if (control.name().equals("log-level")) {
                    for (var option : LogLevel.values()) choices.add(option.name());
                }
                row.add("options", choices);
            }
            result.add(row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public boolean applyManagerSetting(String path, JsonElement expected, JsonPrimitive value) {
        if (path == null || !path.startsWith("sorter.")) return false;
        String name = path.substring("sorter.".length());
        RemoteControl control = REMOTE_CONTROLS.stream()
                .filter(option -> option.name().equals(name))
                .findFirst()
                .orElse(null);
        if (control == null) return false;
        var old = managerValues().get(path);
        if (expected != null && !expected.equals(old)) return false;
        Setting<?> setting = settings.get(name);
        try {
            return switch (control.type()) {
                case "bool" -> value.isBoolean() && ((Setting<Boolean>) setting).set(value.getAsBoolean());
                case "int" -> {
                    if (!value.isNumber()) yield false;
                    int number = value.getAsBigDecimal().intValueExact();
                    yield number >= control.min()
                            && number <= control.max()
                            && ((Setting<Integer>) setting).set(number);
                }
                case "double" -> {
                    if (!value.isNumber()) yield false;
                    double number = value.getAsDouble();
                    yield Double.isFinite(number)
                            && number >= control.min()
                            && number <= control.max()
                            && ((Setting<Double>) setting).set(number);
                }
                case "enum" -> {
                    if (!value.isString()) yield false;
                    yield switch (name) {
                        case "log-level" -> ((Setting<LogLevel>) setting).set(LogLevel.valueOf(value.getAsString()));
                        default -> false;
                    };
                }
                case "string" -> {
                    if (!value.isString()) yield false;
                    String string = value.getAsString();
                    if (string.length() > 100 || string.chars().anyMatch(Character::isISOControl)) yield false;
                    if ((name.equals("keep-slots") || name.equals("carry-slots")) && !string.matches("[0-9, \\-]*"))
                        yield false;
                    yield ((Setting<String>) setting).set(string);
                }
                default -> false;
            };
        } catch (IllegalArgumentException | ArithmeticException rejected) {
            return false;
        }
    }

    // Turning on and off

    @Override
    public String getInfoString() {
        if (com.porter.wesort.compat.NavigationRuntime.paused())
            return com.porter.wesort.compat.NavigationRuntime.status();
        String waiting = gateStatus();
        if (waiting != null) return waiting;
        if (!gateReason.isEmpty()) return gateReason;
        return InventorySync.ready()
                ? null
                : (InventorySync.failed() ? "AUTO_RECOVERING" : "awaiting server inventory");
    }

    @Override
    public void onActivate() {
        // Taking over from wesort-stash-manager's driving: that is torn down first, then this starts as usual
        if (driven) {
            driven = false;
            teardown();
        }
        setup();
    }

    /**
     * Everything a start needs. {@code user} (the module was turned on) adds what is for a person: new containers near
     * you, everything marked stale, the checks and the talk. Driven by wesort-stash-manager only the state is reset.
     */
    private void setup() {
        setUp = true;
        clearUuidBatch();
        uuidBatchYield = false;
        // This Agent uses tier-based sorting
        // exclusively; never require or create chest labels to compact UUID boxes.
        boolean user = isActive();
        job = null;
        target = null;
        breakRetries = 0;
        brokenSince = -1;
        moved = 0;
        wait = 0;
        idle = false;
        stalled.clear();
        compacted.clear();
        resetLoopState();
        yielder.reset();
        gate = Gate.Running;
        ownedMenu = null;
        ourGoal = null;
        if (BARITONE) BaritoneHelper.unconfine();
        jobInterruptions = 0;
        returnTo = null;
        tierBoxes.clear();
        handle = null;
        packTried.clear();
        compactor.reset();
        placementRetryAt.clear();
        resetWander();
        placeFailures = 0;
        lastPlaceTick = -1000;
        repackPausedUntil = 0;
        dropLost = false;
        dropLostAtMs = 0;
        placedConfirmed = false;
        kitBuilder.reset();
        crafter.reset();
        craftForOrder = false;
        craftCooldownUntil = 0;
        craftHealth.note(null, null, null);
        collectedSlot = -1;
        scanned = 0;
        unbundleBlocked.clear();
        ground.abort();
        offline = false;
        resuming = false;
        resumeVerify = false;
        resumeFinishOnly = false;
        resumeDimensionSaid = false;
        resumeDimLogTick = -100_000;
        gateReason = "";
        repackVerifyReads = 0;
        repackVerifySeen.clear();
        storeWatchMs = 0;
        cursorFirstSeen = -1;
        cursorBlocked = false;
        waitTimeoutAlerted = false;
        lastWaitEscalation = clock;
        recoveringOrphan = false;
        recoveryRetryAt.clear();
        syncedHandle = null;
        syncedPlaced = null;
        syncedConfirmed = false;

        // What was carried from the stash before the last stop is carried again, before the slots are chosen
        ensureStash();
        restoreHeld();
        startupDepositPending = StashManager.ownsClient() && !user;
        startupDump = false;
        startupStock.clear();
        startupRetryAt = 0;
        waitingForContainers = false;

        // A recovered world shulker may still be in our inventory after a stop. Restore
        // its carry slot even if warehouse orders are disabled, so it is deposited.
        if (sorting()) {
            if (!pickCarrySlots(!startupDepositPending && !keepOn())) {
                turnOff();
                return;
            }
        } else if (ordersWanted() || !stashHeld.isEmpty()) {
            // Orders and unfinished orphan recovery both need tracked carry slots.
            pickCarrySlots(false);
        } else {
            carry.clear();
            pruneHeld();
        }
        orderHeld.clear();
        heldFrom.clear();
        returnGaveUp.clear();
        returnWarned.clear();
        // What an order took before a stop is put back now
        if (!sorting() && (ordersWanted() || !stashHeld.isEmpty())) {
            for (int slot : stashHeld.keySet()) {
                ItemStack there = mc.player.getInventory().getItem(slot);
                if (carry.contains(slot) && !there.isEmpty()) orderHeld.put(slot, there.copy());
            }
        }

        boolean existed = ensureStash() != null;
        if (stash == null) stash = new Stash(stashName(), Stash.currentServer());

        // A shulker step that was under way is finished first
        pendingResume = stash.inFlight;
        repackConflict = pendingResume == null ? "" : pendingResume.repackConflict;
        verifiedReturnStack = pendingResume == null ? ItemStack.EMPTY : pendingResume.verifiedReturnStack.copy();
        returningChangedShulker = !verifiedReturnStack.isEmpty();
        if (pendingResume != null && pendingResume.placed != null) {
            info(
                    "A shulker step was under way at %s when I stopped, finishing it first.",
                    pendingResume.placed.toShortString());
        } else pendingResume = null;
        if (pendingResume != null
                && (!com.porter.wesort.compat.PhysicalJournalRuntime.blocked()
                        || com.porter.wesort.compat.PhysicalJournalRuntime.debtGated())
                && !com.porter.wesort.compat.PhysicalJournalRuntime.awaitingMirror()
                && (repackWork == null
                        || repackWork.phase() == com.porter.wesort.inventory.PhysicalWork.Phase.COMPLETED
                        || repackWork.phase() == com.porter.wesort.inventory.PhysicalWork.Phase.FAILED)) {
            // The journal is not fenced but a restart stranded the in-memory tracking: track
            // the resumed step again instead of leaving the next STORE without verification.
            // A debt-only fence is equivalent here: the journal is healed, and rebuilding
            // the tracking is what lifts the debt.
            repackWork = new com.porter.wesort.inventory.PhysicalWork("Shulker", System.nanoTime());
            debug("Rebuilt shulker tracking for the saved step; the journal is healed.");
        }
        // A debt file with nothing restorable behind it (no saved step, no memory work,
        // terminal journal, no inspection running) would fence forever: lift it loudly.
        // The verified step stays durable in the journal, leftovers are found by orphan
        // recovery, and every new order re-verifies from scratch.
        com.porter.wesort.compat.PhysicalJournalRuntime.clearOrphanedDebt();

        // Driven: reads, orders and leftovers only. New containers, marking everything stale, the checks and the
        // talk
        // are for a person turning the module on
        if (!user) return;

        int added = addNearbyBoxes();
        for (Box box : stash.boxes) {
            box.unreachable = false;
        }
        // Everything given up on or unexplored before gets another try
        forgetExploration();
        orderBoxes();
        checkOrphans();
        // Players may have changed things while it was off or the connection was down: every container is read again,
        // what is known stays in use until each one is
        // (not the ones read within the fresh-minutes window: those are only read again if something marks them stale)
        for (Box box : stash.boxes) {
            box.stale = !box.excluded && (box.stale || !fresh(box));
            if (box.stale && readableBox(box)) forcedSurvey.add(box);
        }

        // With a perimeter the containers come from exploring it, and the checks wait until that is done
        if (!stash.hasArea() && !checkStash()) return;

        int unknown = (int) stash.boxes.stream()
                .filter(b -> !b.excluded && !b.foreign && (!b.known() || b.stale))
                .count();
        if (!sorting())
            info(
                    "Stash \"%s\": %d containers, scan only: reading %d unknown or stale ones, then watching for changes.",
                    stash.name, stash.boxes.size(), unknown);
        else if (!existed)
            info("New stash \"%s\": scanning %d containers, then sorting.", stash.name, stash.boxes.size());
        else if (unknown > 0)
            info("Stash \"%s\": %d containers, scanning %d new ones first.", stash.name, stash.boxes.size(), unknown);
        else info("Stash \"%s\": %d containers, sorting by %s.", stash.name, stash.boxes.size(), "tier");

        // What is going to happen, and in what order
        List<String> plan = new ArrayList<>();
        if (stash.hasArea()) plan.add("explore the area");
        if (unknown > 0) plan.add("read " + unknown + " containers");
        plan.add(!sorting() ? "watch for changes" : "sort by tier");
        StringBuilder next = new StringBuilder();
        for (int i = 0; i < plan.size(); i++)
            next.append(i == 0 ? "" : ", then ").append(plan.get(i));
        step(String.format(
                "starting \"%s\", %d containers; next: %s.",
                stash.name, stash.boxes.stream().filter(b -> !b.excluded).count(), next));

        if (added > 0) stash.save();
    }

    @Override
    public void onDeactivate() {
        PhysicalJournalRuntime.block("Automation stopped during physical work");
        teardown();
    }

    private void teardown() {
        stopPortalNavigation();
        if (!InventorySync.ready() || PhysicalJournalRuntime.blocked()) {
            // Do not drain grids, return the cursor, or close the menu on an unverified inventory.
            setUp = false;
            ledgeGuard(false);
            if (usedBaritone) {
                releaseGoal();
                BaritoneHelper.unprotect();
                usedBaritone = false;
            }
            warning(
                    "Inventory readback is pending or uncertain; automatic recovery will retry when the source is available.");
            gate = Gate.Running;
            yielder.reset();
            return;
        }
        setUp = false;
        // An order stops where it is: its saved plan carries on at the next claim
        orderRunner.reset();

        // A crafting table being made in the inventory's 2x2 grid: what is in the grid is taken back
        if (mc.player != null && crafter.inventoryGrid()) {
            var invMenu = mc.player.inventoryMenu;
            for (int i = 0; i < 30; i++) {
                Crafter.Act back = crafter.drain(invMenu);
                if (back == null || back.kind() != Crafter.Act.CLICK) break;
                mc.gameMode.handleInventoryMouseClick(
                        invMenu.containerId, back.slot(), back.button(), ClickType.PICKUP, mc.player);
            }
            String left = gridLeftovers();
            if (!left.isEmpty())
                alert("Stopped with items left in the inventory's crafting grid, no room to take them back: %s.", left);
        }
        // Only a window of ours, never one the player opened
        if (mc.player != null
                && mc.player.containerMenu != mc.player.inventoryMenu
                && ourMenu(mc.player.containerMenu)) {
            // A crafting table is never closed with items in the grid: they are taken back first
            if (mc.player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu cm) {
                for (int i = 0; i < 30; i++) {
                    Crafter.Act back = crafter.drain(cm);
                    if (back == null || back.kind() != Crafter.Act.CLICK) break;
                    mc.gameMode.handleInventoryMouseClick(
                            cm.containerId, back.slot(), back.button(), ClickType.PICKUP, mc.player);
                }
                if (!crafter.clean(cm))
                    alert(
                            "Stopped with items left in the crafting table, no room to take them back. The server returns them to your inventory.");
            }
            if (!mc.player.containerMenu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(mc.player.containerMenu);
        }
        ownedMenu = null;
        gate = Gate.Running;
        gateReason = "";
        ledgeGuard(false);
        ground.abort();
        // Emptying a bundle: whatever is on the cursor goes into a carry slot
        if (job == Job.Unbundle && mc.player != null) finishUnbundle();

        if (usedBaritone) {
            releaseGoal();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        if (pickaxeSwapped) {
            InvUtils.swapBack();
            pickaxeSwapped = false;
        }
        if (xpSwapped) {
            InvUtils.swapBack();
            xpSwapped = false;
        }
        xpPending = false;
        xpThrowQueued = false;
        xpAttempt++;
        xpMoveTo = -1;
        uuidNaming = false;
        clearUuidBatch();
        reportPlacedShulker();
        restoreHotbar();
        handle = null;
        resuming = false;
        pendingResume = null;
        syncedHandle = null;
        syncedPlaced = null;
        kitBuilder.deactivate();
        // What the craft gathered stays tracked as the stash's, so it goes back later
        if (crafter.active()) crafter.finish();
        crafter.reset();
        craftForOrder = false;
        anvilSession = false;
        tableSession = false;
        placingTable = false;
        anvilSlot = -1;
        placer.abort("stopped");
        placer.restoreHand();
        chestSession = false;
        chestHolding = false;
        recheckOnly = false;

        syncHeld();
        if (stash != null && stash.isDirty()) stash.save();

        if (mc.player != null) {
            int held = sorting()
                    ? carriedSlots().size()
                    : (int) orderHeld.keySet().stream()
                            .filter(slot ->
                                    !mc.player.getInventory().getItem(slot).isEmpty())
                            .count();
            if (held > 0) alert("Stopped while carrying %d stacks from the stash, they're in your inventory.", held);
        }

        job = null;
        target = null;
        if (mc.player != null) pruneHeld();
    }

    /**
     * Drops the current job and anything left to resume, then starts over as if the module had just been turned on. A
     * shulker of ours still standing is left where it is and listed with the orphaned ones, not finished. With the module
     * off, only a saved shulker step is forgotten.
     */
    private void unstuck() {
        if (mc.player == null || mc.level == null) return;
        // Driven by wesort-stash-manager counts as running: it is stopped and started again like the module
        boolean wasOn = isActive();
        if (!isActive() && !driven) {
            if (ensureStash() == null || stash.inFlight == null) {
                info("Nothing to unstick: the sorter is off and no unfinished shulker step is saved.");
                return;
            }
            BlockPos at = stash.inFlight.placed;
            stash.inFlight = null;
            stash.markDirty();
            stash.save();
            info(
                    "Forgot the unfinished shulker step%s, it won't be resumed.",
                    at == null ? "" : " at " + at.toShortString());
            return;
        }

        BlockPos left = standingShulker();
        if (left == null && pendingResume != null) left = pendingResume.placed;
        String what = placedName();
        Compactor.Plan plan = compactor.plan;
        int stacks = plan != null ? plan.placedSize : placedStacks;
        BlockPos wasCollecting = handle == Handle.Collect ? placed : null;

        // Turning off saves a standing shulker to be finished on the next start: that record is dropped before turning
        // on
        turnOff();
        if (stash != null && stash.inFlight != null) {
            stash.inFlight = null;
            stash.markDirty();
            stash.save();
        }
        if (left != null && shulkerAt(left)) {
            recordOrphan(left, what, stacks);
            alert(
                    "Unstuck: left the shulker at %s where it stands, it is listed with the orphaned shulkers.",
                    left.toShortString());
        } else if (wasCollecting != null)
            alert(
                    "Unstuck: the shulker from %s may still be on the ground there, check for it.",
                    wasCollecting.toShortString());
        alert("Unstuck: dropped what I was doing, starting over.");
        // The module goes back on if it was on; driven by wesort-stash-manager it is started again by its next tick
        if (wasOn && !isActive()) toggle();
    }

    /**
     * Works out which slots to carry stash items in, from the settings or, by default, every empty slot.
     */
    private boolean startupDepositPending, startupDump;

    private int startupRetryAt;
    private boolean waitingForContainers;
    private int nextDiscoveryTick;

    private final Map<Integer, ItemStack> startupStock = new LinkedHashMap<>();

    private boolean pickCarrySlots() {
        return pickCarrySlots(true);
    }

    /**
     * @param required false in scan-only mode, where the slots are only for orders: having none is not an error
     */
    private boolean pickCarrySlots(boolean required) {
        carry.clear();
        pruneHeld();
        Set<Integer> keep = parseSlots(keepSlots.get());
        Set<Integer> chosen = parseSlots(carrySlots.get());
        boolean designated = !carrySlots.get().isBlank();

        for (int i = 0; i < 36; i++) {
            if (keep.contains(i)) continue;
            if (designated && !chosen.contains(i)) continue;
            // Stacks from the stash still in the inventory from before (sorting turned off and on) are carried again,
            // so
            // they are put away and not left behind as if they were the player's own
            ItemStack there = mc.player.getInventory().getItem(i);
            ItemStack held = stashHeld.get(i);
            if (there.isEmpty() || (held != null && ItemStack.matches(there, held))) carry.add(i);
        }

        if (designated && carry.size() < chosen.size() && (required || !carry.isEmpty())) {
            warning("%d of your carry-slots have items in them, they'll be left alone.", chosen.size() - carry.size());
        }

        if (carry.isEmpty()) {
            if (!required) return true;
            alertError(
                    designated
                            ? "Your carry-slots are all full. Empty at least one of them."
                            : "Empty at least one inventory slot to carry items in.");
            return false;
        }
        return true;
    }

    /**
     * "1-9, 28-36" into inventory indexes. 1-9 is the hotbar and 10-36 the main inventory, matching index + 1.
     */
    private static Set<Integer> parseSlots(String text) {
        Set<Integer> slots = new HashSet<>();
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                String[] range = part.split("-");
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int n = Math.max(1, Math.min(from, to)); n <= Math.min(36, Math.max(from, to)); n++)
                    slots.add(n - 1);
            } catch (NumberFormatException ignored) {
                // Skip anything that isn't a number or range
            }
        }
        return slots;
    }

    // The sorting loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Reconcile the *reported* physical operation even if the durable journal
        // has already fenced input. The permission gate below must never prevent
        // an in-flight repack from recording NEEDS_RECONCILIATION.
        if (repackWork != null && !repackWork.terminal()) {
            if (InventorySync.failed()) blockRepack("Physical readback failed: " + InventorySync.reason());
            else if (repackWork.expired(System.nanoTime())) blockRepack("Physical phase deadline expired");
        }
        if (!repackConflict.isEmpty()) {
            ledgeGuard(false);
            if (surveyWhileBlocked()) return;
            gateLogThrottled("blocked on external change: " + repackConflict);
            return;
        }
        // A resume or STORE wait makes no progress behind the recovery gates (the tick
        // counters freeze there), so it is watched on the wall clock: after ten minutes
        // with nothing verified the shulker step is abandoned loudly instead of hanging.
        watchResumeStore();
        // A verified re-read of the stored shulker can still complete the work...
        verifyRepackStored();
        // ...and a healed journal heals the stranded in-memory state, so the bot does not
        // sit in NEEDS_RECONCILIATION forever after automatic recovery finished.
        healRepackState();
        // A lost-shulker latch without journal backing expires loudly instead of parking
        // the headless bot forever: the step is quarantined as an orphan and other
        // shulker work resumes. A fenced journal still parks below until inspected.
        expireDropLost();
        // Lobby navigation uses no inventory or physical permit, but still obeys the
        // reconciliation/navigation safety fences below. Sorting itself remains permit-gated.
        if (repackWork != null
                && repackWork.phase() == com.porter.wesort.inventory.PhysicalWork.Phase.NEEDS_RECONCILIATION) {
            ledgeGuard(false);
            if (surveyWhileBlocked()) return;
            gateLogThrottled("waiting for automatic recovery: "
                    + (repackWork.reason().isEmpty() ? "physical work needs reconciliation" : repackWork.reason()));
            return;
        }
        // 6b6t portal transit is navigation-only; unified with Tracker: runs under
        // manager authorization even with a fenced journal, empty cursor required.
        // portalTick() preserves TRANSITING_WITH_FENCED_JOURNAL itself; no pre-set
        // here to avoid flapping. Physical mutation below stays fenced.
        if (portalModeOn() && portalAwaiting()) {
            boolean cursorEmpty = mc.player != null
                    && mc.player.containerMenu == mc.player.inventoryMenu
                    && mc.player.containerMenu.getCarried().isEmpty();
            if (!cursorEmpty) {
                ledgeGuard(false);
                gateLogThrottled("6b6t portal: CURSOR_OCCUPIED");
                return;
            }
            if (portalTick()) {
                ledgeGuard(false);
                gateLogThrottled("6b6t portal: " + (portalBlocker != null ? portalBlocker : "moving to the portal"));
                return;
            }
        }
        if (PhysicalJournalRuntime.blocked()
                || PhysicalJournalRuntime.awaitingMirror()
                || !InventorySync.ready()
                || com.porter.wesort.compat.NavigationRuntime.paused()) {
            ledgeGuard(false);
            // Read-only surveys interleave while only the physical journal blocks us: a
            // repack wait must not freeze re-reads.
            if (surveyWhileBlocked()) {
                gateReason = "surveying while " + preGateReason();
                return;
            }
            gateLogThrottled(preGateReason());
            return;
        }
        if (!sortingPermitted()) {
            if (surveyWhileBlocked()) return;
            gateLogThrottled("sorting paused (navigation "
                    + com.porter.wesort.compat.NavigationRuntime.status()
                    + "): Manager must exclude reservations before physical moves.");
            ledgeGuard(false);
            return;
        }
        if (stash == null) {
            turnOff();
            return;
        }
        gateReason = "";
        if (isActive() && !StashManager.ownsClient() && clock - lastToggleWarnClock >= 1200) {
            lastToggleWarnClock = clock;
            warning(
                    "Sort-items is on but the WeSort-Agent toggle is off: the sorter runs without a Manager link. Turn the Agent on, or sort-items off.");
        }
        ticks++;
        // Track every placed shulker in the loaded portion of the assigned bounding box, including
        // player drop-offs, even while an order keeps the bot busy for a long time.
        if (ticks % 100 == 0) observePlacedShulkers();
        // Whatever the sorter is doing, a skipped container whose time has come is made eligible again
        releaseDueSurveys();
        if (ticks % 20 == 0 && placementRetryAt.values().removeIf(due -> ticks >= due)) idle = false;
        if (repackPausedUntil > 0 && ticks >= repackPausedUntil) {
            repackPausedUntil = 0;
            idle = false;
        }
        if (handle != null) lastOwnAct = ticks;
        // Time spent giving way does not count against the time allowed to finish a saved shulker step
        if (resuming && gate != Gate.Running) resumeStarted++;

        // The Meteor Agent toggle is the sole client-ownership authority. Clear
        // foreign activity history as well as ignoring new courtesy interruptions.
        boolean ownsClient = StashManager.ownsClient();
        StashYield.Reason foreign = yielder.check(ownsClient);
        boolean reclaimed = !ownsClient || reclaimClientScreen();
        // A screen that could not be reclaimed (a foreign menu holding the cursor) parks
        // the bot like any other interruption instead of spinning silently on the tick.
        if (!reclaimed && gate == Gate.Running && !restingIdle()) beginYield(StashYield.Reason.Screen);
        if (!reclaimed && gate == Gate.Running) return;
        foreignNow = foreign != null;
        // The sorter has the controls while it walks or works; a ledge with a drop gets a sneak, which is let go of
        // otherwise
        ledgeGuard(gate == Gate.Running && foreign == null && standingWork());
        switch (gate) {
            case Yielding -> {
                if (cleanupStep()) gate = Gate.Waiting;
                return;
            }
            case Waiting -> {
                if (!tickWaiting(foreign)) return;
            }
            case Running -> {
                // Stay-on mode with nothing to do holds nothing of anyone's, so there's nothing to give way
                if (foreign != null && !restingIdle()) {
                    beginYield(foreign);
                    return;
                }
            }
        }

        if (wait > 0) {
            wait--;
            return;
        }

        if (job == null) {
            if (sorting() && ticks % 100 == 0) reportBlockedDropOff();
            // Never start while a container is open, it might be one the player is using
            if (mc.player.containerMenu != mc.player.inventoryMenu) return;
            pruneHeld();
            if (relocateNamed()) return;
            remindStuck();
            applyStale();
            // The perimeter changed while a job was under way: containers outside it go, the ones inside come in
            if (areaDirty) applyArea();
            // Chunks of the perimeter that loaded since, with containers in them
            if (ticks % 100 == 0 && stash.hasArea()) {
                pruneForeign("periodic check");
                int added = scanArea();
                // Containers that are gone, where the chunk is loaded to tell
                boolean gone = stash.boxes.removeIf(this::blockGone);
                if (gone) stash.markDirty();
                if (added > 0 || gone) {
                    version++;
                    idle = false;
                }
            }
            if (waitingForContainers && ticks >= nextDiscoveryTick) {
                int added = addNearbyBoxes();
                if (added > 0) {
                    orderBoxes();
                    stash.save();
                    version++;
                }
                if (checkStash()) idle = false;
            }
            // Scan-only with no carry slot yet: retry for orders and recovered warehouse items.
            if (!sorting()
                    && (ordersWanted() || !stashHeld.isEmpty())
                    && carry.isEmpty()
                    && ticks % 100 == 0
                    && job == null
                    && mc.player.containerMenu == mc.player.inventoryMenu) pickCarrySlots(false);
            // Not taking orders and nothing of an order held: no carry slots
            else if (!sorting() && !ordersWanted() && stashHeld.isEmpty() && orderHeld.isEmpty() && !carry.isEmpty())
                carry.clear();
            // A one-shot sort reports unusable carry slots immediately. A persistent
            // agent retries in the planner, where it can still do read-only work.
            if (sorting() && carry.isEmpty() && !startupDepositPending) {
                if (!keepOn() && !pickCarrySlots()) {
                    turnOff();
                    return;
                }
                idle = false;
            }
            if (idle && stash.recoveredBox == null && !wakeUp()) {
                // Resting: only something on the ground can still start a job. Looked at every half second, and only
                // when nobody else is busy
                if (ticks % 10 != 0 || foreign != null || !yielder.quietElapsed() || !startGround()) return;
            } else {
                // Nothing is started while someone else is busy, nor until they have been quiet for a while
                if (foreign != null || !yielder.quietElapsed()) return;
                if (!nextJob()) return;
            }
        }

        if (handle != null) {
            tickHandling();
            return;
        }
        if (job == Job.Ground) {
            ground.tick();
            return;
        }
        if (job == Job.Explore) {
            exploreTick();
            return;
        }
        if (job == Job.Unbundle) {
            unbundleTick();
            return;
        }
        if (job == Job.Name) {
            nameTick();
            return;
        }
        if (job == Job.Craft) {
            craftTick();
            return;
        }
        if (job == Job.AnvilPlace) {
            anvilPlaceTick();
            return;
        }
        if (job == Job.ChestPlace) {
            chestPlaceTick();
            return;
        }
        stepTicks++;

        // A container job that has run 4 minutes of its own time (giving way is not counted, nothing runs then) is
        // over.
        // While the server hasn't answered outstanding inventory actions (bad TPS), the clock pauses: the job gets
        // the same amount of answered time, not the same amount of wall ticks.
        if (target != null
                && com.porter.wesort.compat.InventorySync.ready()
                && ++jobTicks > (job == Job.Survey ? SURVEY_WATCHDOG_TICKS : JOB_WATCHDOG_TICKS)) {
            Box stuck = target;
            debug(
                    "Watchdog: %s at %s ran %d s (step %s, %d actions), ending it.",
                    job, xyz(stuck.pos), jobTicks / 20, step, actions);
            if (job == Job.Survey) {
                stuck.stale = true;
                surveyFailed(stuck, "reading it took " + jobTicks / 20 + "s (stuck in step " + step + ")");
            } else stalled.add(stuck);
            // The walk is let go of too
            releaseGoal();
            if (BARITONE) BaritoneHelper.unconfine();
            finishJob();
            return;
        }

        switch (step) {
            case Walking -> tickWalking();
            case Opening -> tickOpening();
            case Working -> tickWorking();
        }
    }

    // Giving way

    /**
     * Whether the sorter is in the middle of a job, so other modules can keep out of its way. False when it's off, resting
     * in stay-on mode, between jobs, or giving way to someone else.
     */
    public boolean busyWithJob() {
        // Between jobs, and while waiting for others, it holds nothing: whoever is working finishes its step first
        return running() && gate == Gate.Running && (job != null || handle != null);
    }

    private boolean restingIdle() {
        return idle && job == null && handle == null;
    }

    // Picking up from the ground

    /**
     * Collecting from the ground is on and possible: the setting, not scan-only, Baritone there, the module running.
     */
    private boolean groundOn() {
        return sorting() && collectGround.get() && BARITONE;
    }

    /**
     * Between jobs, with nothing else to do: starts a trip to something lying on the ground. Never while a shulker is
     * standing in the world, a kit or a compaction is in hand, or a container is open.
     * @return true when a trip has begun
     */
    // Trips to the ground in a row without putting anything away, at the most
    private static final int GROUND_BATCH_MAX = 30;

    private int groundBatch;

    private boolean startGround() {
        if (job != null || handle != null || placed != null || gate != Gate.Running) return false;
        if (compactor.active() || kitBuilder.active() || mc.player.containerMenu != mc.player.inventoryMenu)
            return false;
        if (!mc.player.containerMenu.getCarried().isEmpty() || mc.screen != null) return false;
        if (!ground.next()) return false;

        job = Job.Ground;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        return true;
    }

    /**
     * An active agent need not wait for chat, inventory screens or foreign menus.
     * Never close a menu carrying a stack or interfere with our own transaction.
     * The caller has already checked inventory readback and the physical journal.
     */
    private boolean reclaimClientScreen() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != mc.player.inventoryMenu && ourMenu(menu)) {
            // Chat or another screen over our own menu must not close the transaction.
            return true;
        }
        if (menu != mc.player.inventoryMenu) {
            if (!menu.getCarried().isEmpty()) return false;
            mc.player.closeContainer();
            lastCloseTick = ticks;
            yielder.inventoryChangedByUs();
            return false;
        }
        if (mc.screen != null) mc.setScreen(null);
        return true;
    }

    /**
     * The menu is a container window the sorter opened, or is in the middle of opening.
     */
    private boolean ourMenu(AbstractContainerMenu menu) {
        if (menu == null || mc.player == null || menu == mc.player.inventoryMenu) return false;
        if (menu == ownedMenu) return true;
        if (job == null || job == Job.Ground || job == Job.Explore || job == Job.Unbundle) return false;
        // A chest opened by this client is ours to close, not another player's.
        if (handle != null) return handle == Handle.Open && menu instanceof ShulkerBoxMenu;
        // Whatever opened soon after our own click on the box is ours, whatever it turns out to be (the wrong size, an
        // ender chest, a plugin menu): it is closed and the click made again, never waited on as somebody else's
        return step == Step.Opening
                && openPos != null
                && ticks - openClickTick <= OPEN_ANSWER_TICKS
                && menu != openMenuBefore;
    }

    /**
     * Closes one of our windows without taking a screen of the player's down with it: with another screen on top, the
     * server is told and the menu let go of, and the screen stays.
     */
    private void closeOurWindow(AbstractContainerMenu menu) {
        boolean ourScreen = mc.screen == null
                || (mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu);
        if (ourScreen) mc.player.closeContainer();
        else {
            mc.player.connection.send(new ServerboundContainerClosePacket(menu.containerId));
            mc.player.containerMenu = mc.player.inventoryMenu;
        }
        ownedMenu = null;
        lastOwnAct = ticks;
        lastCloseTick = ticks;
        yielder.inventoryChangedByUs();
    }

    /** True while container-delay still holds back the next open click. */
    private boolean openHeld() {
        return com.porter.wesort.utils.ContainerPacing.openHeld(ticks, lastCloseTick, containerDelay.get());
    }

    /** Ticks to wait before the first click in a window that has just opened. */
    private int firstClickWait() {
        return com.porter.wesort.utils.ContainerPacing.firstClickWait(delay.get(), containerDelay.get());
    }

    /**
     * Lets go of the Baritone goal we set, and only that one: never cancels the player's own paths.
     */
    private void releaseGoal() {
        if (BARITONE && ourGoal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) ourGoal);
        ourGoal = null;
        releaseTick = ticks;
        // Baritone gets its break and place settings back the moment our walk is over
        if (BARITONE) BaritoneHelper.unconfine();
    }

    private void setGoal(Object goal) {
        ourGoal = goal;
        goalTick = ticks;
    }

    /**
     * Walks to within {@code range} blocks of a position with our own Baritone goal. Every walk the sorter makes goes
     * through here, with Baritone not allowed to break or place blocks for as long as the goal is ours: it never
     * digs or bridges its way to a destination. {@link #releaseGoal()} gives the settings back.
     */
    private void walkNear(BlockPos pos, int range) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null)
            walkInside = stash != null
                    && stash.hasArea()
                    && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        debug(() -> String.format("Walking to within %d of %s.", range, xyz(pos)));
        setGoal(BaritoneHelper.moveNear(pos, range));
    }

    /** Navigation-only lobby entrance; never asks Baritone to path through the portal block. */
    private void walkPortalApproach() {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        walkInside = false;
        setGoal(BaritoneHelper.movePortalApproach(portalTarget, portalApproach));
    }

    /**
     * The same walk, but only until the container is within reach: one up high is opened from below, the way a player
     * would, instead of looking for a spot next to it that may not be there.
     */
    private void walkReach(Box box) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null)
            walkInside = stash != null
                    && stash.hasArea()
                    && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        debug(() -> String.format("Walking to within reach (%.1f) of %s.", reach.get(), xyz(box.pos)));
        setGoal(BaritoneHelper.moveInReach(box.parts, reach.get()));
    }

    /**
     * The same walk to a column, at any height: for places whose chunk isn't loaded yet.
     */
    private void walkXZ(int x, int z) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null)
            walkInside = stash != null
                    && stash.hasArea()
                    && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        debug(() -> String.format("Walking to column %d, %d.", x, z));
        setGoal(BaritoneHelper.moveXZ(x, z));
    }

    /**
     * Our walk has left the stash area it started in (plus the margin), by a way that is not to be taken.
     */
    private boolean leftArea() {
        return ourGoal != null
                && walkInside
                && stash != null
                && stash.hasArea()
                && !stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
    }

    // Ledge guard

    private boolean ledgeSneaking;

    /**
     * The sorter is standing still at its work with a window open or a click in the inventory: not while Baritone walks,
     * and never at the clicks that open or use something in the world (the container or the shulker), which
     * are made with the key up.
     */
    private boolean standingWork() {
        if (ourGoal != null) return false;
        if (handle != null) return handle == Handle.Work;
        if (job == null) return false;
        return switch (job) {
            case Unbundle -> true;
            case Ground, Explore -> false;
            default -> step == Step.Working;
        };
    }

    /**
     * While the sorter has the controls (walking, or standing working), sneaks at the edge of a block when the drop on
     * that side is two blocks or more, so a push or a slip can't take it off. Let go as soon as the feet are 0.3 blocks
     * or more from every such edge, or the sorter doesn't have the controls: the key goes back to how it is physically.
     */
    private void ledgeGuard(boolean controlling) {
        boolean want = controlling && safeWalk.get() && mc.player != null && mc.level != null && nearDrop();
        KeyMapping key = mc.options.keyShift;
        if (want && !ledgeSneaking) {
            ledgeSneaking = true;
            key.setDown(true);
        } else if (!want && ledgeSneaking) {
            ledgeSneaking = false;
            key.setDown(Input.isPressed(key));
        }
    }

    /**
     * The feet are within 0.3 blocks of an edge of the block stood on, with a drop of 2 or more blocks beyond it: no solid
     * block in the two cells below the neighbouring cell (the cell beside the feet). Corners are checked diagonally.
     */
    private boolean nearDrop() {
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.getAbilities().flying) return false;

        BlockPos feet = mc.player.blockPosition();
        double fx = mc.player.getX() - feet.getX(), fz = mc.player.getZ() - feet.getZ();
        int dx = fx < LEDGE ? -1 : fx > 1 - LEDGE ? 1 : 0;
        int dz = fz < LEDGE ? -1 : fz > 1 - LEDGE ? 1 : 0;
        if (dx == 0 && dz == 0) return false;

        if (dx != 0 && drops(feet.offset(dx, 0, 0))) return true;
        if (dz != 0 && drops(feet.offset(0, 0, dz))) return true;
        return dx != 0 && dz != 0 && drops(feet.offset(dx, 0, dz));
    }

    private static final double LEDGE = 0.3;

    private boolean drops(BlockPos beside) {
        return !solid(beside.below()) && !solid(beside.below(2));
    }

    private boolean solid(BlockPos pos) {
        // What isn't loaded is no drop to guard against
        return !mc.level.isLoaded(pos)
                || !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    /**
     * The sorter may walk: Baritone is there, and the walk setting is on or a perimeter is set (which implies it).
     */
    private boolean walkOn() {
        return BARITONE && (walk.get() || (stash != null && stash.hasArea()));
    }

    /**
     * Someone else started something. Everything of ours is let go of first: the path, the window, the cursor. The job
     * stays, and is picked up again once they have finished and been quiet for a while.
     */
    private void beginYield(StashYield.Reason reason) {
        gate = Gate.Yielding;
        debug(
                "Giving way to %s%s.",
                reason == null ? "a stack on the cursor" : reason.label, job == null ? "" : " in the middle of " + job);
        cleanupTries = 0;
        cursorFirstSeen = -1;
        cursorBlocked = false;
        waitTimeoutAlerted = false;
        lastWaitLogClock = clock;
        lastWaitEscalation = clock;
        wait = 0;
        releaseGoal();
        ledgeGuard(false);
        if (job == Job.ChestPlace) placer.release();
        if (job != null) {
            yielder.interrupted();
            jobInterruptions++;
        }
        // A trip to the ground is simply dropped, the item is looked at again later
        if (job == Job.Ground) {
            ground.abort();
            job = null;
        }
        // Exploring carries on from the chunks still unseen
        if (job == Job.Explore || job == Job.Unbundle) job = null;
        if (stash.isDirty()) stash.save();
        yielder.startWaiting(reason);
    }

    /**
     * One step of letting go: the stack on the cursor is put away, then our own window is closed.
     * @return true when nothing of ours is left to let go of
     */
    private boolean cleanupStep() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        // The inventory's 2x2 grid of a crafting table being made is emptied too
        if (crafter.inventoryGrid() && menu == mc.player.inventoryMenu && cleanupTries < CURSOR_TRIES * 2) {
            Crafter.Act back = crafter.drain(menu);
            if (back != null && back.kind() == Crafter.Act.CLICK) {
                if (cleanupTries++ % 4 == 0) craftClick(menu, back.slot(), back.button());
                return false;
            }
        }
        if (menu != mc.player.inventoryMenu && ourMenu(menu)) {
            // A crafting table is never closed with items in the grid: they are taken back first
            if (menu instanceof net.minecraft.world.inventory.CraftingMenu
                    && crafter.active()
                    && cleanupTries < CURSOR_TRIES * 2) {
                Crafter.Act back = crafter.drain(menu);
                if (back != null && back.kind() == Crafter.Act.CLICK) {
                    if (cleanupTries++ % 4 == 0) craftClick(menu, back.slot(), back.button());
                    return false;
                }
            }
            // Keep ownership until the server answers with an empty cursor. A full
            // inventory or a rejected click can resolve while the menu stays open.
            if (!menu.getCarried().isEmpty()) {
                if (cursorRecoveryDue()) recoverCursor();
                return false;
            }
            closeOurWindow(menu);
            return false;
        }
        ownedMenu = null;
        return true;
    }

    private boolean cursorRecoveryDue() {
        if (cursorFirstSeen < 0) cursorFirstSeen = clock;
        int elapsed = clock - cursorFirstSeen;
        if (!cursorBlocked && elapsed >= CURSOR_TRIES * 3) {
            cursorBlocked = true;
            step("Waiting for space to put away the cursor stack; retrying automatically.");
        }
        return com.porter.wesort.utils.ContainerPacing.cursorRetryDue(elapsed, CURSOR_TRIES * 3);
    }

    /**
     * Letting go is done: waiting for them to finish, and then to be quiet for a while.
     * @return true when waiting is over and sorting carries on this tick
     */
    private boolean tickWaiting(StashYield.Reason foreign) {
        // A late answer to our own click: a window of ours opened while we were waiting
        if (mc.player.containerMenu != mc.player.inventoryMenu && ourMenu(mc.player.containerMenu)) {
            cleanupStep();
            return false;
        }
        waitingTimeoutTick(foreign);
        if (foreign != null || !yielder.quietElapsed()) return false;

        // A stack left on the cursor with no screen open (the server doesn't always hand it back) goes into a free slot
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            if (mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu && cursorRecoveryDue())
                returnCursor();
            return false;
        }
        cursorFirstSeen = -1;
        cursorBlocked = false;

        yielder.endWaiting();
        gate = Gate.Running;
        yielder.inventoryChangedByUs();
        resumeJob();
        return true;
    }

    /**
     * Every wait says what is awaited every few seconds, and a wait that never ends
     * escalates: one alert, then the backed-off quiet time grows and the parked job counts
     * as interrupted, so a lingering handle is eventually dropped instead of hanging.
     */
    private void waitingTimeoutTick(StashYield.Reason foreign) {
        long waited = yielder.waitingTicks();
        if (waited < 0) return;
        if (clock - lastWaitLogClock >= GATE_LOG_CLOCKS) {
            lastWaitLogClock = clock;
            StashYield.Reason reason = foreign != null ? foreign : yielder.lastReason();
            step(String.format(
                    "waiting %ds for %s; next: carry on after %ds of quiet.",
                    waited / 20,
                    reason == null ? "a stack on the cursor" : reason.label,
                    yielder.quietRemainingTicks() / 20));
        }
        if (waited <= WAIT_TIMEOUT_TICKS) return;
        if (!waitTimeoutAlerted) {
            waitTimeoutAlerted = true;
            StashYield.Reason reason = foreign != null ? foreign : yielder.lastReason();
            alert(
                    "Waiting %d minutes for %s, backing off; the job is parked until they are quiet.",
                    waited / 20 / 60, reason == null ? "a stack on the cursor" : reason.label);
        }
        if (clock - lastWaitEscalation >= 1200) {
            lastWaitEscalation = clock;
            yielder.interrupted();
            jobInterruptions++;
        }
    }

    /**
     * Picks up where we left off. A job on a container is dropped and planned again: the world has changed under it,
     * and the containers are read as they are now. It isn't counted as getting nothing done. Repacking has a shulker in
     * the world, so it carries on from the same step.
     */
    private void resumeJob() {
        wait = delay.get();
        if (job == null) return;
        if (handle != null) {
            resumeHandle();
            return;
        }

        if (job == Job.Compact && target != null) compacted.remove(target);
        if (jobCounted && target != null) {
            passJobs = Math.max(0, passJobs - 1);
            boxVisits.merge(target, -1, Integer::sum);
        }
        job = null;
        target = null;
        openPos = null;
        openMenuBefore = null;
        swapStep = 0;
        recoveries = 0;
        startSigSet = endSigSet = false;
        settled = false;
        jobInterruptions = 0;
    }

    private void resumeHandle() {
        boolean inWorld = placed != null && !mc.level.getBlockState(placed).isAir();

        // Interrupted over and over before anything was put down: plan something else
        if (jobInterruptions > MAX_JOB_INTERRUPTIONS && !inWorld) {
            endHandling();
            jobInterruptions = 0;
            return;
        }

        // The window was closed on the way: open it again
        Handle next = handle == Handle.Work ? Handle.Open : handle;
        if (!placedConfirmed && next != Handle.Place && next != Handle.Open) placedConfirmed = placed != null;
        if (inWorld && (next == Handle.Open || next == Handle.Break) && !inReachOf(placed)) {
            returnTo = next;
            next = Handle.Return;
        }
        handle = next;
        handleTicks = 0;
    }

    /**
     * Back to the shulker we put down, if we were moved away from it.
     */
    private void handleReturn() {
        if (placed == null || mc.level.getBlockState(placed).isAir() || inReachOf(placed)) {
            releaseGoal();
            handle = returnTo != null ? returnTo : Handle.Open;
            returnTo = null;
            handleTicks = 0;
            return;
        }

        if (leftArea()) {
            abandonShulker("Skipping the shulker box at " + at(placed) + ": the way there leaves the stash area.");
            return;
        }

        if (!walkOn() || handleTicks > WALK_TIMEOUT) {
            abandonShulker("Couldn't get back to the shulker I put down at " + placed.toShortString() + ".");
            return;
        }

        boolean idleNow = handleTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (handleTicks == 1 || (idleNow && handleTicks % 40 == 0)) {
            walkNear(placed, 2);
        }
    }

    /**
     * Puts a stack left on the cursor, with no window open, into a free slot of the inventory.
     */
    private void returnCursor() {
        ItemStack cursor = mc.player.containerMenu.getCarried();
        Set<Integer> keep = parseSlots(keepSlots.get());

        // The carry slots first, so the stack is put away with the rest; any other free slot only if there is none
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < 36; slot++) {
                if (keep.contains(slot)
                        || !mc.player.getInventory().getItem(slot).isEmpty()) continue;
                if ((pass == 0) != carry.contains(slot)) continue;

                stashHeld.put(slot, cursor.copy());
                InvUtils.click().slotId(slot < 9 ? 36 + slot : slot);
                lastOwnAct = ticks;
                return;
            }
        }
    }

    private boolean inReachOf(BlockPos pos) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach.get() * reach.get();
    }

    /**
     * Gear, food and the like that came out of the stash and has nowhere to go looks just like the player's own, so it's
     * mentioned again every few minutes for as long as it's stuck.
     */
    private void remindStuck() {
        if (stuckCarried.isEmpty() || clock - lastStuckWarn < STUCK_REWARN) return;

        int own = 0;
        for (int slot : stuckCarried.keySet())
            if (ownKind(mc.player.getInventory().getItem(slot))) own++;
        if (own == 0) return;

        lastStuckWarn = clock;
        alert(
                "Still carrying %d stacks of gear or food from the stash that have nowhere to go, they're in your inventory.",
                own);
    }

    // Smart drop-off backoff applies only to periodic surveys, never to a dirty observation.
    private final Map<Box, Long> dropBackoffUntil = new HashMap<>();

    private long surveyIntervalMs() {
        return Math.max(com.porter.wesort.inventory.SurveyBatch.MIN_INTERVAL_MS, recheck.get() * 1000L);
    }

    private boolean surveyDue(Box box, long now) {
        if (!readableBox(box)) return false;
        // A fresh receipt for an already-started physical operation is not
        // routine polling. Waiting five minutes here could expire the journal.
        boolean requiredReceipt =
                forcedSurvey.contains(box) || (awaitingRepackStorage() && box.stale && box.lastRead < repackStoreAt);
        return com.porter.wesort.inventory.SurveyBatch.due(
                now, box.lastRead, surveyAttemptAt.getOrDefault(box, 0L), surveyIntervalMs(), requiredReceipt);
    }

    private boolean surveyCandidate(Box box, long now, boolean periodic) {
        if (!surveyDue(box, now)) return false;
        if (forcedSurvey.contains(box) || requestedSurvey.contains(box) || !box.known() || box.stale) return true;
        return periodic
                && keepOn()
                && now >= nextBackgroundBatchAt
                && (!smartRecheck.get() || now >= dropBackoffUntil.getOrDefault(box, 0L));
    }

    private boolean queueSurveyBatch(boolean periodic, boolean nearbyOnly) {
        long now = System.currentTimeMillis();
        surveyBatch.removeIf(box -> !stash.boxes.contains(box) || !surveyDue(box, now));
        if (!surveyBatch.isEmpty()) return true;
        surveyAttemptAt.keySet().retainAll(stash.boxes);
        forcedSurvey.retainAll(stash.boxes);
        requestedSurvey.retainAll(stash.boxes);
        dropBackoffUntil.keySet().retainAll(stash.boxes);
        var candidates = stash.boxes.stream()
                .filter(box -> surveyCandidate(box, now, periodic) && (!nearbyOnly || inReach(box)))
                .toList();
        var selected = com.porter.wesort.inventory.SurveyBatch.oldest(
                candidates, box -> box.lastRead, box -> surveyAttemptAt.getOrDefault(box, 0L));
        if (selected.isEmpty()) return false;
        // The selected batch stays stable until every member is attempted or
        // has been refreshed by another verified job in the meantime.
        surveyBatch.addAll(selected);
        info("Checking %d containers, oldest observations first.", selected.size());
        return true;
    }

    private boolean startSurveyBatch(boolean nearbyOnly) {
        if (!queueSurveyBatch(false, nearbyOnly)) return false;
        long now = System.currentTimeMillis();
        Box selected = null;
        for (Box box : surveyBatch) {
            if (surveyDue(box, now) && (!nearbyOnly || inReach(box))) {
                selected = box;
                break;
            }
        }
        if (selected == null) return false;
        surveyBatch.remove(selected);
        // A cancelled walk can be replanned. Consume an explicit request on read or failure.
        // A continuously busy stash still gets background batches, but the
        // next periodic batch leaves a window for ordinary work.
        if (surveyBatch.isEmpty()) nextBackgroundBatchAt = now + 5_000L;
        surveyAttemptAt.put(selected, now);
        if (start(Job.Survey, selected)) return true;
        forcedSurvey.remove(selected);
        requestedSurvey.remove(selected);
        return false;
    }

    private void dropChecked(Box box, boolean differed) {
        if (!box.dropOff) return;
        long now = System.currentTimeMillis();
        long interval = surveyIntervalMs();
        dropBackoffUntil.put(box, now + (differed ? interval : Math.min(600_000L, interval * 2)));
    }

    /**
     * A container the scan could not read: skipped, said so with the reason and when it is tried again, and the scan
     * carries on with the others.
     */
    private void surveyFailed(Box box, String why) {
        forcedSurvey.remove(box);
        requestedSurvey.remove(box);
        box.stale = true;
        box.observationUnreachable = true;
        failedSurvey.add(box);
        // One failure per job: the skip paths and finishJob can both get here for the same read
        int delay = surveyRetry.failOnce(box, ticks, 20 * 300);
        if (delay < 0) return;
        step(String.format(
                "skipped %s at %s: %s; will retry in %s.",
                blockName(box), xyz(box.pos), why, com.porter.wesort.utils.SurveyRetry.describe(delay)));
    }

    /**
     * Containers whose retry time has come are read again. Runs every tick between jobs, so it does not depend on the
     * sorter being idle or on a particular tick.
     */
    private void releaseDueSurveys() {
        if (failedSurvey.isEmpty()) return;
        for (Box box : surveyRetry.due(ticks)) {
            if (!failedSurvey.remove(box)) continue;
            idle = false;
            WeSortAddon.LOG.info(
                    "[wesort-stash-sorter] retrying the read of {} at {} (failed {} times).",
                    blockName(box),
                    xyz(box.pos),
                    surveyRetry.failures(box));
        }
    }

    /**
     * Bot mode, waiting for something to sort: a container that changed, or a drop-off due for a check.
     */
    private boolean wakeUp() {
        idleTicks++;

        // Dirty and periodic reads share one oldest-first scheduler. A recent
        // dirty box does not keep waking the idle loop throughout its cooldown.
        long now = System.currentTimeMillis();
        boolean surveyReady = !surveyBatch.isEmpty();
        if (!surveyReady && now >= nextIdleSurveyAt) {
            nextIdleSurveyAt = now + 1000;
            surveyReady = queueSurveyBatch(true, false);
        }
        if (areaDirty || surveyReady || (sorting() && startupDepositPending && ticks >= startupRetryAt)) {
            idle = false;
            return true;
        }

        if (Kits.version() != seenKits) {
            seenKits = Kits.version();
            version++;
        }

        if (version != seenVersion) {
            seenVersion = version;
            clearStalled();
            compacted.clear();
            packTried.clear();
            futile.clear();
            // Boxes still waiting for their retry time stay skipped: a version bump does not cut the schedule short
            failedSurvey.removeIf(b -> !surveyRetry.waiting(b, ticks));
            unconfirmedRuns.clear();
            stuckCarried.clear();
            stuckWarned = false;
            kitBuilder.wake();
            idle = false;
            return true;
        }

        return false;
    }

    /**
     * Picks the next job. Read every container we don't know yet, then put away what we're carrying, then pick up
     * what's in the wrong place.
     * @return false when there's nothing left to do
     */
    private boolean nextJob() {
        try {
            return pickJob();
        } finally {
            destCache = null;
            misplacedCache = null;
            roomBase = null;
        }
    }

    /**
     * Per-planning-pass memos, only set while nextJob is choosing. Nothing that destinationsFor or misplacedCount read
     * (box contents, labels, flags, mode, tier boxes) changes during a pass, so within it equal inputs give equal
     * answers. Dropped when the pass ends, so later changes can never be served stale.
     */
    private Map<DestKey, List<Box>> destCache;

    private Map<Box, Integer> misplacedCache;
    // bestSigned by item and components, and the room each container has for deposits, both for the pass only
    private Map<Box, Integer> roomBase;

    /**
     * What deterministic tier destinations depend on in a stack: its item and components (name, enchants,
     * potion, food, damage, shulker contents...), not its count.
     */
    private record DestKey(Item item, DataComponentMap components) {
        static DestKey of(ItemStack stack) {
            return new DestKey(stack.getItem(), stack.getComponents());
        }
    }

    /**
     * The nearest container something can be taken from right now, or null. With every carry slot full there is nothing
     * to pick up into: a trip would only open the container and stall it.
     */
    private Box nearestTakeSource() {
        if (freeCarrySlot() == -1) return null;
        return nearest(stash.boxes.stream()
                .filter(b -> b.sortable() && !b.stale && !blocked(b, Job.Take) && takeableSlot(b) != -1)
                .toList());
    }

    private boolean pickJob() {
        if (stash.recoveredBox != null) return startRecoveredBoxReturn();

        // A shulker step that was saved comes before anything else
        if (startResume()) return true;

        // Rollback precedes sorting and the next order. No new physical
        // allocation can run while staged packages or loose items remain.
        if (orderRunner.returning() && scanOnlyOrderStep()) return true;

        // An order in hand comes before the sorter's own work: reading stale containers, exploring, sorting,
        // compacting,
        // labelling, saved kits, the ground. They wait until it is finished or parked
        if (orderRunner.planned() && orderPriorityStep()) return true;

        if (sorting() && keepOn() && carry.isEmpty() && !startupDepositPending) {
            pickCarrySlots(false);
            if (carry.isEmpty()) {
                if (!pendingPhysicalRecovery()) {
                    queueSurveyBatch(true, false);
                    if (startSurveyBatch(false)) return true;
                }
                step("Waiting for a free carry slot; stock checks continue.");
                wait = 20;
                return false;
            }
        }

        if (waitingForContainers) {
            if (startSurveyBatch(false)) return true;
            wait = 20;
            return false;
        }

        // Unclaimed world shulkers use the same stash put-away path as a drop-off.
        if (startOrphanRecovery()) return true;

        // Exploring: whatever unknown or old is within reach is read before moving on to the next waypoint
        if (exploring && stash.hasArea() && startSurveyBatch(true)) return true;

        // With a perimeter, its chunks are loaded first, so its containers are known before anything is sorted
        if (explorePending && exploreRan && !exploring) explorePending = false;
        if ((sorting() || explorePending) && startExplore()) return true;
        if (!running()) return false;

        // Refresh overdue stock even if sorting never reaches idle, without
        // interrupting a funded naming batch or outstanding physical recovery.
        if (uuidBatch.isEmpty() && !pendingPhysicalRecovery()) queueSurveyBatch(true, false);
        if (startSurveyBatch(false)) return true;

        // Initial inventory belongs to the dedicated bot only. Finish all saved work and
        // read the destination before moving anything. No cleanup runs in scan-only mode.
        if (sorting()
                && startupDepositPending
                && ticks >= startupRetryAt
                && !pendingPhysicalRecovery()
                && !orderRunner.holdsOrder()) {
            if (startStartupDeposit()) return true;
        }

        // Scan-only: the containers last seen before a stop are read again, then everything is read, nothing is moved.

        // Scan-only: crafting whose order is over is driven to its end (the grid emptied, what was gathered put back)
        if (!sorting() && crafter.active() && (!craftForOrder || !orderRunner.running()) && runCraft(crafter.next()))
            return true;

        // Scan-only: an order is the only thing that moves items, and what it leaves over goes back where it came from
        if (!sorting() && scanOnlyOrderStep()) return true;

        // Scan-only: everything is read, nothing is moved. A kit in hand is let go of, and a shulker standing in the
        // world is fetched back by finishSorting
        if (!sorting()) {
            if (!orderKit()) kitBuilder.suspend("scan only.");
            finishSorting();
            return false;
        }

        plan();
        destCache = new HashMap<>();
        misplacedCache = new HashMap<>();
        roomBase = null;

        // Jobs started since the backlog (stacks out of place, and carried) last reached a new low: a pass that keeps
        // starting jobs without getting anywhere is going in circles, so say so and stop rather than reopen forever
        int carriedNow = carriedSlots().size();
        int backlog = carriedNow;
        for (Box box : stash.boxes) if (box.sortable()) backlog += misplacedCount(box);
        if (backlog < passMin) {
            passMin = backlog;
            passJobs = 0;
            // Real headway: containers given up on for going back and forth get another chance
            stalled.removeAll(circular);
            circular.clear();
            boxVisits.clear();
        }
        if (passJobs > 6 * stash.boxes.size() + 60) {
            alert(
                    "Sorting went in circles (%d jobs without getting closer to done), stopping this pass. %d stacks out of place, carrying %d.",
                    passJobs, backlog - carriedNow, carriedNow);
            finishSorting();
            return false;
        }

        // A bundle with things in it is emptied first, its contents are then carried like anything else
        if (startUnbundle()) return true;

        // Containers last seen before a stop are read again before anything is taken from them or built from them; what
        // is carried is put away first
        boolean rereading = !staleBoxes().isEmpty();

        // An order kit left over from an order that is over is let go of, so it can't be taken for a saved kit
        if (orderKit() && !orderRunner.running()) kitBuilder.dropOrderKit();

        // Sorting: a shulker an order unpacked goes back to the container it came from, named or not
        if (sorting() && !rereading && !kitBuilder.active() && !orderKit()) {
            List<Integer> back = returnCandidates();
            returning = !back.isEmpty();
            if (returning && returnLeftovers(back)) return true;
        }

        // An order carries on until it is delivered, failed or paused. New work on it waits until what is carried is
        // put away
        if (orderRunner.running()
                && !rereading
                && (kitBuilder.active() || crafter.active() || activeCarried().isEmpty())
                && runOrder(orderRunner.next())) return true;

        // Crafting for the compactor carries on until it is done or given up on
        if (crafter.active() && (!craftForOrder || !orderRunner.running()) && !rereading && runCraft(crafter.next()))
            return true;

        // Kit building was removed: a non-order kit in hand is let go of.
        if (kitBuilder.active() && !rereading && !orderKit()) {
            kitBuilder.suspend("kit building removed.");
        }

        // Compacting carries on until it is done or given up on: nothing else is started meanwhile
        if (compactor.active() && (!rereading || compactor.plan.finishOnly) && compactor.next()) return true;

        // Inventory destinations are computed from tier order and available capacity.

        if (uuidIdleAllowed(rereading) && uuidRenameCarried()) return true;

        if (!rereading && repackOn() && nextRepackJob()) return true;

        // Stacks given up on are tried again every pass: a container read or emptied since can give them a
        // place
        stuckCarried.entrySet().removeIf(held -> {
            ItemStack stack = mc.player.getInventory().getItem(held.getKey());
            return stack.isEmpty() || !idOf(stack).equals(held.getValue());
        });
        // Looked at again when something changed or every few seconds, not on every pick
        if (!stuckCarried.isEmpty() && (stuckCheckVersion != version || clock - lastStuckCheck >= STUCK_RECHECK)) {
            stuckCheckVersion = version;
            lastStuckCheck = clock;
            stuckCarried
                    .entrySet()
                    .removeIf(held -> canPlace(mc.player.getInventory().getItem(held.getKey())));
        }
        if (stuckCarried.isEmpty()) stuckWarned = false;

        List<Integer> carried = activeCarried();
        if (!carried.isEmpty()) {
            List<Box> places = new ArrayList<>();
            for (int slot : carried) {
                for (Box box : destinationsFor(mc.player.getInventory().getItem(slot))) {
                    if (!blocked(box, Job.Deposit)
                            && (roomFor(box, mc.player.getInventory().getItem(slot)) || misplacedCount(box) > 0))
                        places.add(box);
                }
            }

            Box place = nearest(places);

            // A box given up on for going back and forth that is the only home for something carried is still used:
            // the pass watchdog stops a real loop
            if (place == null) {
                for (int slot : carried) {
                    for (Box box : destinationsFor(mc.player.getInventory().getItem(slot))) {
                        if (circular.contains(box)
                                && !blockedByFutility(box, Job.Deposit)
                                && (roomFor(box, mc.player.getInventory().getItem(slot)) || misplacedCount(box) > 0))
                            places.add(box);
                    }
                }
                place = nearest(places);
            }
            if (place != null) {
                // Room for more and something to take nearer than where this goes: take on the way, then put away
                // together. What is taken is only what has room to go (takeableSlot reserves it)
                if (freeCarryCount() >= Math.max(2, carry.size() / 3)) {
                    Box onTheWay = nearestTakeSource();
                    if (onTheWay != null && closer(onTheWay, place)) {
                        debug(
                                "Taking from %s at %s on the way, it is nearer than %s at %s.",
                                home(onTheWay), xyz(onTheWay.pos), home(place), xyz(place.pos));
                        return start(Job.Take, onTheWay);
                    }
                }
                return start(Job.Deposit, place);
            }

            // When its preferred tier is full, use a safe ordinary container with verified free space.
            Box spare = spareBox();
            if (spare != null) return start(Job.Dump, spare);

            // Nothing takes it for now. It stays in the carry slots, out of the plans, while the rest carries on, and
            // is
            // looked at again when something in the stash changes
            for (int slot : carried)
                stuckCarried.put(slot, idOf(mc.player.getInventory().getItem(slot)));
            if (!stuckWarned) {
                stuckWarned = true;
                lastStuckWarn = clock;
                alert(
                        "Found nowhere with room for %d carried stacks in tier storage or safe overflow; leaving them in inventory.",
                        carried.size());
            }
        }

        // Now the containers that were last seen before the stop are read again
        if (rereading && startSurveyBatch(false)) return true;

        // Mixed shulkers sitting in the stash: fetch one to clean out
        if (repackOn() && freeCarryCount() >= 2) {
            Box best = null;
            int bestSlot = -1;
            for (Box box : stash.boxes) {
                if (!box.sortable() || box.stale || blocked(box, Job.Fetch) || isKitBox(box)) continue;
                for (int i = 0; i < box.contents.length; i++) {
                    if (!strays(box.contents[i]).isEmpty() && (best == null || closer(box, best))) {
                        best = box;
                        bestSlot = i;
                        break;
                    }
                }
            }
            if (best != null) return fetch(best, bestSlot);
        }

        // Give UUID registration a bounded chance before generic stock moves:
        // otherwise a busy warehouse continually finds another Take job and
        // its shulker boxes never receive the WeSort identity they need.
        if (uuidIdleAllowed(rereading) && uuidFetchStep()) return true;

        // With every carry slot full there's nothing to pick up into: a trip would only open the container and stall it
        Box source = nearestTakeSource();
        if (source != null) return start(Job.Take, source);

        // Downstack: merge partial stacks of the same item, once per container per run
        Box messy = nearest(stash.boxes.stream()
                .filter(b -> b.sortable()
                        && !b.dropOff
                        && !blocked(b, Job.Compact)
                        && !compacted.contains(b)
                        && mergeable(b) != null)
                .toList());
        if (messy != null) {
            compacted.add(messy);
            return start(Job.Compact, messy);
        }

        // An inventory revision determines whether an operation has made progress

        // Everything is put away: whatever others left on the ground comes before compacting
        if (startGround()) return true;

        // Everything is where it belongs: a full shulker's worth of anything is packed, which frees chest slots
        if (!awaitingRepackStorage() && !kitBuilder.active() && compactor.start() && compactor.next()) return true;
        if (crafter.active() && (!craftForOrder || !orderRunner.running()) && runCraft(crafter.next())) return true;

        finishSorting();
        return false;
    }

    // Bundles

    private int unbundleSlot = -1, unbundleTaken, unbundleStalls, unbundleSize;
    private boolean unbundleClicked;
    private final Map<Integer, Integer> unbundleBlocked = new HashMap<>();
    private int unbundleStartSize;
    private static final int UNBUNDLE_BLOCK_TICKS = 20 * 30;

    private static boolean isBundle(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.BUNDLE_CONTENTS);
    }

    /**
     * A bundle with something in it.
     */
    private static boolean fullBundle(ItemStack stack) {
        if (!isBundle(stack)) return false;
        var contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents != null && !contents.isEmpty();
    }

    private static int bundleSize(ItemStack stack) {
        var contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents == null ? 0 : contents.size();
    }

    /**
     * A bundle with things in it, in a carry slot, is emptied into the other carry slots before anything else is done with
     * it. Needs the plain inventory (no screen, nothing on the cursor) and a free carry slot for what comes out.
     * @return true when emptying has begun
     */
    private boolean startUnbundle() {
        if (!sorting()) return false;
        if (mc.screen != null
                || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        if (freeCarrySlot() == -1) return false;

        Set<Integer> setAside = compactor.reserved();
        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!fullBundle(stack)
                    || kitBuilder.holds(slot)
                    || kitBuilder.material(stack)
                    || setAside.contains(slot)
                    || ticks < unbundleBlocked.getOrDefault(slot, -1)) continue;

            unbundleSlot = slot;
            unbundleTaken = 0;
            unbundleStalls = 0;
            unbundleClicked = false;
            unbundleSize = unbundleStartSize = bundleSize(stack);
            stashHeld.put(slot, stack.copy());

            step(String.format("emptying a bundle; next: put away %d stacks.", unbundleSize));
            job = Job.Unbundle;
            target = null;
            handle = null;
            actions = 0;
            jobCounted = false;
            stepTicks = 0;
            jobInterruptions = 0;
            startSigSet = endSigSet = false;
            settled = false;
            openPos = null;
            openMenuBefore = null;
            return true;
        }
        return false;
    }

    /**
     * Takes the contents of the bundle out one stack at a time. Right-clicking a bundle in the inventory with an empty
     * cursor puts the next stack in it on the cursor (the same in 1.21.4 and 1.21.11), and a left click on a free carry slot
     * puts that stack down. The bundle never leaves its slot, so there's nothing to put back.
     */
    private void unbundleTick() {
        stepTicks++;
        lastOwnAct = ticks;
        AbstractContainerMenu menu = mc.player.inventoryMenu;
        if (mc.screen != null || mc.player.containerMenu != menu) {
            finishUnbundle();
            return;
        }

        ItemStack bundle = mc.player.getInventory().getItem(unbundleSlot);
        ItemStack cursor = menu.getCarried();
        if (!isBundle(bundle) || stepTicks > 20 * 30) {
            finishUnbundle();
            return;
        }

        // A stack is out: down into a free carry slot
        if (!cursor.isEmpty()) {
            int free = freeCarrySlot();
            if (free == -1) {
                // Nowhere to put it: back into the bundle (a left click on a bundle with a stack on the cursor)
                bundleClick(unbundleSlot, 0);
                finishUnbundle();
                return;
            }
            stashHeld.put(free, cursor.copy());
            bundleClick(free, 0);
            unbundleTaken++;
            unbundleClicked = false;
            wait = delay.get();
            return;
        }

        if (!fullBundle(bundle) || freeCarrySlot() == -1) {
            finishUnbundle();
            return;
        }

        // The last take got nothing out of the bundle: the server isn't letting it
        int size = bundleSize(bundle);
        boolean noProgress =
                (unbundleClicked && size >= unbundleSize) || (unbundleTaken > 0 && size >= unbundleStartSize);
        if (noProgress && ++unbundleStalls > 2) {
            finishUnbundle();
            return;
        }
        unbundleSize = size;
        unbundleClicked = true;
        bundleClick(unbundleSlot, 1);
        wait = delay.get();
    }

    /**
     * A click on a carry slot in the player inventory menu: button 0 is a left click, 1 a right click.
     */
    private void bundleClick(int slot, int button) {
        AbstractContainerMenu menu = mc.player.inventoryMenu;
        mc.gameMode.handleInventoryMouseClick(
                menu.containerId, slot < 9 ? 36 + slot : slot, button, ClickType.PICKUP, mc.player);
        yielder.inventoryChangedByUs();
    }

    private void finishUnbundle() {
        ItemStack bundle = unbundleSlot >= 0 ? mc.player.getInventory().getItem(unbundleSlot) : ItemStack.EMPTY;
        if (isBundle(bundle)) stashHeld.put(unbundleSlot, bundle.copy());
        if (unbundleTaken > 0 && !fullBundle(bundle)) info("Emptied a bundle: %d stacks taken out.", unbundleTaken);
        if (fullBundle(bundle) && unbundleSlot >= 0) unbundleBlocked.put(unbundleSlot, ticks + UNBUNDLE_BLOCK_TICKS);
        // A stack still on the cursor, with no screen to take it: into a carry slot
        if (mc.screen == null
                && mc.player.containerMenu == mc.player.inventoryMenu
                && !mc.player.containerMenu.getCarried().isEmpty()) returnCursor();
        unbundleSlot = -1;
        if (job == Job.Unbundle) job = null;
        wait = delay.get();
        moved++;
    }

    // Building kits (local shulker-packing shared with the order runner)

    /**
     * Starts the job for the next step of the kit being built.
     * @return true when a job was started, false when the kit is finished or given up on
     */
    private boolean runKit(KitBuilder.Intent intent) {
        kitBuilder.jobStarted();

        switch (intent) {
            case KitBuilder.Take take -> {
                if (start(Job.KitTake, take.box())) return true;
                kitBuilder.suspend("couldn't reach " + take.box().pos.toShortString() + ".");
            }
            case KitBuilder.Fetch fetch -> {
                if (fetch(fetch.box(), fetch.slot())) return true;
                kitBuilder.suspend("couldn't reach " + fetch.box().pos.toShortString() + ".");
            }
            case KitBuilder.Unpack unpack -> {
                return startHandling(Job.KitUnpack, unpack.slot(), null);
            }
            case KitBuilder.Fill fill -> {
                return startHandling(Job.KitFill, fill.slot(), null);
            }
            case KitBuilder.Done done -> kitBuilder.finish();
            case KitBuilder.Abort abort -> kitBuilder.suspend(abort.reason());
        }
        return false;
    }

    // Order work: the carry slots holding what an order took out of the stash (with a copy of the stack, to tell it
    // from
    // something the player put there since), and the container each came from
    private final Map<Integer, ItemStack> orderHeld = new java.util.LinkedHashMap<>();
    private final Map<Integer, Box> heldFrom = new HashMap<>();
    // Slots nowhere could be found for, so it does not try over and over; told once
    private final Set<Integer> returnGaveUp = new HashSet<>();
    private final Set<Integer> returnWarned = new HashSet<>();
    private int returnGaveUpVersion;
    private boolean returning;
    // When the bot last worked on an order, and last did any job (in ticks of the clock)
    private int lastOrderWork = -100000, lastOwnJob = -100000;

    /** For the info string: leftovers of an order are being put back. */
    boolean returningLeftovers() {
        return returning && !sorting();
    }

    private boolean orderWork() {
        return orderKit() || orderRunner.running() || orderRunner.returning() || crafter.active();
    }

    private void noteOrderHeld(int slot, ItemStack baseline) {
        if (slot < 0 || !orderWork()) return;
        orderHeld.put(slot, baseline.copy());
        if (target != null && (job == Job.KitTake || job == Job.Fetch || job == Job.CraftTake))
            heldFrom.put(slot, target);
    }

    /** A shulker of an order picked up again after it was placed: it keeps where it came from. */
    private void noteCollected(int from, int slot) {
        if (slot < 0 || !orderWork()) return;
        ItemStack there = mc.player.getInventory().getItem(slot);
        if (!there.isEmpty()) orderHeld.put(slot, there.copy());
        Box source = heldFrom.get(from);
        if (source != null) heldFrom.put(slot, source);
        // Kept with what is held from the stash, so it is still known after a restart
        if (!there.isEmpty()) stashHeld.put(slot, there.copy());
    }

    /** Whether the slot still holds what the order put there (shulkers change inside, loose stacks shrink). */
    private boolean stillOurs(int slot) {
        ItemStack now = mc.player.getInventory().getItem(slot);
        if (now.isEmpty()) return false;

        ItemStack was = orderHeld.get(slot);
        if (was == null) {
            orderHeld.put(slot, now.copy());
            return true;
        }
        boolean same = Utils.isShulker(now.getItem())
                ? now.getItem() == was.getItem()
                : ItemStack.isSameItemSameComponents(now, was);
        if (!same) return false;
        orderHeld.put(slot, now.copy());
        return true;
    }

    /**
     * Carry slots with things the order took that nothing is going to use any more: not the shulker being built or the
     * finished one waiting for delivery.
     */
    private List<Integer> leftoverSlots() {
        if (mc.player == null) return List.of();
        orderHeld.keySet().removeIf(slot -> !stillOurs(slot));
        heldFrom.keySet().retainAll(orderHeld.keySet());
        returnGaveUp.retainAll(orderHeld.keySet());
        returnWarned.retainAll(orderHeld.keySet());
        if (returnGaveUpVersion != version) {
            returnGaveUp.clear();
            returnGaveUpVersion = version;
        }

        List<Integer> out = new ArrayList<>();
        for (int slot : orderHeld.keySet()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (orderRunner.claims(stack)
                    || kitBuilder.holds(slot)
                    || crafter.holds(slot)
                    || crafter.reserved(slot)
                    || slot == anvilSlot
                    || returnGaveUp.contains(slot)) continue;
            out.add(slot);
        }
        return out;
    }

    /**
     * What is put back by itself: everything an order left over when not sorting; when sorting only the shulkers taken
     * from a known container (those go back where they came from, the rest is sorted like anything else).
     */
    private List<Integer> returnCandidates() {
        List<Integer> left = leftoverSlots();
        if (!sorting()) return left;
        left.removeIf(slot ->
                !Utils.isShulker(mc.player.getInventory().getItem(slot).getItem()) || !heldFrom.containsKey(slot));
        return left;
    }

    /** An order that is planned: put leftover shulkers back, then the next step of the order. @return true when a job was started */
    private boolean orderPriorityStep() {
        if (!sorting()) return scanOnlyOrderStep();

        List<Integer> back = returnCandidates();
        List<Integer> spent = spentSources(back);
        if (!spent.isEmpty() && returnLeftovers(spent)) {
            returning = true;
            return true;
        }
        returning = !back.isEmpty() && !kitBuilder.active() && !orderKit() && !crafter.active();
        if (returning && returnLeftovers(back)) return true;
        return (kitBuilder.active() || crafter.active() || activeCarried().isEmpty()) && runOrder(orderRunner.next());
    }

    /** Of these held slots, the shulkers the order kit being built is done with. */
    private List<Integer> spentSources(List<Integer> held) {
        if (!kitBuilder.active() || !orderKit()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int slot : held) if (kitBuilder.sourceSpent(slot)) out.add(slot);
        return out;
    }

    /** What may be put back now: while the kit is built only the shulkers it is done with, else every leftover. */
    private List<Integer> returnPool() {
        List<Integer> candidates = returnCandidates();
        return kitBuilder.active() ? spentSources(candidates) : candidates;
    }

    /** Not sorting: carries on with the order, or puts leftovers back. @return true when a job was started */
    private boolean scanOnlyOrderStep() {
        if (orderKit() && !orderRunner.running()) kitBuilder.dropOrderKit();

        List<Integer> left = returnCandidates();
        OrderRunner.Intent recovery = orderRunner.returning() ? orderRunner.recoveryStep() : new OrderRunner.Idle();
        if (orderRunner.returning()) {
            returning = !left.isEmpty();
            // Deposit already recovered cargo before withdrawing another staged
            // package. A full carry inventory must never cause a second fetch.
            if (returning) return returnLeftovers(left);
            return runOrder(recovery);
        }

        // A source shulker with nothing more the kit needs goes back at once, while the kit is still being built
        List<Integer> spent = spentSources(left);
        if (!spent.isEmpty() && returnLeftovers(spent)) {
            returning = true;
            return true;
        }
        returning = !left.isEmpty() && !kitBuilder.active() && !crafter.active();

        if (orderRunner.running()
                && (kitBuilder.active() || crafter.active() || left.isEmpty())
                && runOrder(orderRunner.next())) return true;
        return returning && returnLeftovers(left);
    }

    /** Whether a container could take this stack right now. */
    private boolean roomFor(Box box, ItemStack stack) {
        if (box.contents == null) return false;
        for (ItemStack here : box.contents) {
            if (here.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(here, stack) && here.getCount() < here.getMaxStackSize())
                return true;
        }
        return false;
    }

    private boolean roomForWhole(Box box, ItemStack stack) {
        if (!box.sortable() || box.contents == null) return false;
        long room = 0;
        for (ItemStack here : box.contents) {
            if (here.isEmpty()) room += stack.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(here, stack))
                room += Math.max(0, here.getMaxStackSize() - here.getCount());
        }
        return room >= stack.getCount();
    }

    private boolean returnable(Box box, ItemStack stack) {
        return storageAcceptsReturn(box, stack) && !blocked(box, Job.Return);
    }

    private boolean storageAcceptsReturn(Box box, ItemStack stack) {
        return box != null
                && stash.boxes.contains(box)
                && box.sortable()
                && !box.stale
                && !box.dropOff
                && !isKitBox(box)
                && roomFor(box, stack);
    }

    /**
     * Starts putting the first leftover back: in the container it was taken from if that has room, else the nearest
     * container that has.
     */
    private boolean returnLeftovers(List<Integer> left) {
        int slot = left.get(0);
        ItemStack stack = mc.player.getInventory().getItem(slot);

        Box home = heldFrom.get(slot);
        // A shulker emptied by taking its items does not go back where it came from if it is misplaced there: it goes
        // where
        // empty shulkers belong, else the nearest container that takes it
        boolean emptied =
                Utils.isShulker(stack.getItem()) && !stack.has(DataComponents.CUSTOM_NAME) && emptyShulker(stack);
        if (emptied && home != null && !destinationsFor(stack).contains(home)) home = null;
        if (!returnable(home, stack)) {
            if (emptied) {
                home = nearest(destinationsFor(stack).stream()
                        .filter(b -> returnable(b, stack))
                        .toList());
                if (home == null)
                    home = nearest(stash.boxes.stream()
                            .filter(b -> returnable(b, stack))
                            .toList());
            } else {
                // Shulkers are only ever put back where they came from when sorting; the rest is sorted
                home = sorting()
                        ? null
                        : nearest(stash.boxes.stream()
                                .filter(b -> returnable(b, stack))
                                .toList());
            }
        }
        if (home == null) {
            returnGaveUp.add(slot);
            if (returnWarned.add(slot))
                alert(
                        "Nowhere in the stash has room for %s I'm carrying from an order, keeping it in your inventory.",
                        StackInfo.label(stack));
            return false;
        }
        return start(Job.Return, home);
    }

    private boolean startRecoveredBoxReturn() {
        var pending = stash.recoveredBox;
        Box home = pending.destination() == null ? null : stash.boxAt(pending.destination());
        if (pending.destination() == null) {
            ItemStack stack = pending.stack();
            plan();
            home = nearest(destinationsFor(stack).stream()
                    .filter(b -> storageAcceptsReturn(b, stack) && !returnRetry.waiting(b, ticks))
                    .toList());
            if (home == null)
                home = nearest(stash.boxes.stream()
                        .filter(b -> !b.excluded
                                && !b.foreign
                                && !b.delivery
                                && !b.protectedStorage
                                && !b.unreachable
                                && !b.dropOff
                                && !isKitBox(b)
                                && !failedSurvey.contains(b)
                                && !returnRetry.waiting(b, ticks)
                                && (!b.known() || b.stale || roomFor(b, stack)))
                        .toList());
            if (home == null) {
                // The cached contents may still say "full" after someone makes room.
                // Re-read one eligible return destination on the normal survey clock.
                long now = System.currentTimeMillis();
                Box probe = stash.boxes.stream()
                        .filter(b -> !b.protectedStorage
                                && !b.delivery
                                && !b.dropOff
                                && !isKitBox(b)
                                && !returnRetry.waiting(b, ticks)
                                && surveyDue(b, now))
                        .min(Comparator.comparingLong(b -> b.lastRead))
                        .orElse(null);
                if (probe != null) {
                    surveyAttemptAt.put(probe, now);
                    return start(Job.Survey, probe);
                }
            }
        }
        if (home == null
                || returnRetry.waiting(home, ticks)
                || failedSurvey.contains(home)
                || home.excluded
                || home.foreign
                || home.delivery
                || home.protectedStorage
                || home.dropOff
                || isKitBox(home)) {
            step("Waiting for safe stash storage for the recovered shulker.");
            wait = 20;
            return false;
        }
        return start(Job.Return, home);
    }

    /** Called after the open destination has been read from the server. */
    private boolean depositRecoveredBox() {
        var pending = stash.recoveredBox;
        ItemStack expected = pending.stack();
        ItemStack held = mc.player.getInventory().getItem(pending.slot());
        int copies = 0;
        for (ItemStack item : target.contents)
            if (ItemStack.isSameItemSameComponents(item, expected)) copies += item.getCount();
        if (pending.destination() != null && !target.has(pending.destination())) return false;
        if (PlacedShulkerRecovery.depositConfirmed(held.isEmpty(), pending.before(), copies, expected.getCount())) {
            returnRetry.clear();
            stash.recoveredBox = null;
            stash.markDirty();
            stash.save();
            return false;
        }
        if (PlacedShulkerRecovery.depositUnchanged(ItemStack.matches(held, expected), pending.before(), copies)) {
            // A confirmed read of both inventories proves the attempted transfer had no effect.
            // Release only its destination, retaining custody of the exact carried box.
            returnRetry.failOnce(target, ticks);
            stash.recoveredBox = new Stash.RecoveredBox(pending.slot(), expected, null, -1);
            stash.markDirty();
            stash.save();
            step("Recovered shulker is still carried; choosing another storage container.");
            return false;
        }
        if (returnRetry.waiting(target, ticks)) return false;
        if (!ItemStack.matches(held, expected)
                || !storageAcceptsReturn(target, expected)
                || (pending.before() >= 0 && copies != pending.before())) return false;
        if (pending.destination() == null) {
            stash.recoveredBox = new Stash.RecoveredBox(pending.slot(), expected, target.pos, copies);
            stash.markDirty();
        }
        stash.save();
        if (stash.isDirty()) return false;
        InvUtils.shiftClick().slotId(inventoryToMenu(pending.slot(), target.contents.length));
        return true;
    }

    /** Puts one leftover into the open container. */
    private boolean returnOne() {
        if (!mc.player.containerMenu.getCarried().isEmpty()) return recoverBounded();

        if (stash.recoveredBox != null) return depositRecoveredBox();

        for (int slot : returnPool()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!canFit(stack)) continue;
            InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
            return true;
        }
        return false;
    }

    // Driven by wesort-stash-manager: the loop runs without the module being turned on, to read containers, take orders
    // and
    // put leftovers back. Never to sort.

    private boolean driven;
    private boolean explorePending;

    /** The loop is running: the module is on, or wesort-stash-manager is driving it. */
    private boolean running() {
        return isActive() || driven;
    }

    /**
     * Automatic tier sorting and order intake share the engine. Sorting is allowed
     * only with an actual server-issued physical lease, and never while an order
     * is held or waiting to claim. Scan-only and order work remain available
     * while the Manager denies the lease because an order is open.
     */
    /** Finish a previously started physical move before surrendering the server lease. */
    private boolean finishingPhysicalMaintenance() {
        return !repackConflict.isEmpty()
                || stash != null && stash.recoveredBox != null
                || handle != null
                || compactor.active()
                || (repackWork != null && !repackWork.terminal())
                || (job != null && job != Job.Survey);
    }

    /**
     * True while a started physical sorting or repacking operation must finish before
     * an operator may safely stop the module. Surveys alone are read-only.
     */
    public boolean physicalWorkInProgress() {
        return finishingPhysicalMaintenance();
    }

    private boolean sorting() {
        if (!sortItems.get()) return false;
        var manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return isActive()
                || manager != null
                        && manager.sortingEnabled()
                        && (manager.pendingOrders() == 0 || finishingPhysicalMaintenance())
                        && !orderRunner.holdsOrder()
                        && manager.sortingAllowed();
    }

    public boolean requiresSortingPermit() {
        if (!sortItems.get()
                || mc.player == null
                || mc.level == null
                || stash == null
                || !InventorySync.ready()
                || PhysicalJournalRuntime.blocked()
                || PhysicalJournalRuntime.awaitingMirror()
                || notInStashWorld() != null) return false;
        var manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return isActive()
                || manager != null
                        && manager.sortingEnabled()
                        && (manager.pendingOrders() == 0 || finishingPhysicalMaintenance())
                        && !orderRunner.holdsOrder();
    }

    public boolean sortingPermitted() {
        if (!repackConflict.isEmpty()) return false;
        if (PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror()) return false;
        if (!requiresSortingPermit()) return true;
        // Never continue an already active physical step after the lease expires.
        // Idle scan-only paths can still read and claim orders without a lease.
        if (!isActive()
                && !sorting()
                && job == null
                && handle == null
                && !compactor.active()
                && (repackWork == null || repackWork.terminal())) return true;
        var manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager != null && manager.sortingAllowed();
    }

    // An order run by this client from the Stash Window (LocalOrders), no server: orders are wanted like
    // wesort-stash-manager's accept-orders
    private boolean localOrders;

    void localOrders(boolean on) {
        localOrders = on;
    }

    private boolean ordersWanted() {
        if (localOrders) return true;
        StashManager manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager != null && manager.takingOrders();
    }

    private void turnOff() {
        if (isActive()) toggle();
        else if (driven) {
            driven = false;
            teardown();
        }
    }

    /** Containers waiting to be read: not read yet, or stale. */
    boolean needsReading() {
        if (stash == null) return false;
        if (areaDirty || forcedSurvey.stream().anyMatch(b -> stash.boxes.contains(b) && readableBox(b))) return true;
        if (explorePending && stash.hasArea() && BARITONE) return true;
        return stash.boxes.stream()
                .anyMatch(b -> !b.excluded
                        && !b.foreign
                        && !b.unreachable
                        && !failedSurvey.contains(b)
                        && (!b.known() || b.stale));
    }

    /** wesort-stash-manager is driving the loop. */
    boolean driving() {
        return driven;
    }

    int staleCount() {
        return staleBoxes().size();
    }

    /**
     * wesort-stash-manager's call, every tick: whether it needs the loop. When the module is on it runs by itself and this does
     * nothing. Starting is the same set-up as turning the module on (minus what is only for a person doing it), and
     * stopping the same tear-down, without the module's switch ever moving.
     */
    void drive(boolean want) {
        if (isActive()) {
            driven = false;
            return;
        }
        if (mc.player == null || mc.level == null) {
            driven = false;
            setUp = false;
            return;
        }

        driveBlocked = null;
        if (want) driveBlocked = notInStashWorld();
        // The lobby transit reports through driveBlocked but must keep driving: the portal tick
        // runs inside the driven loop and parks everything else itself.
        if (driveBlocked != null && !portalAwaiting()) want = false;

        if (want && !driven) {
            driven = true;
            setup();
        } else if (!want && (driven || setUp)) {
            driven = false;
            teardown();
        }
    }

    // Set up and not yet torn down, driven or not
    private boolean setUp;
    // Why the loop can't be driven now, for the blocker
    private String driveBlocked;

    /**
     * Why the player is not where the stash is, or null: no stash, another dimension, or (when the stash does not know its
     * dimension yet) too far from every container it knows. While the 6b6t lobby transit is pending, that is the
     * only reason: it surfaces in the order blocker and the heartbeat, so the Manager sees "waiting for survival".
     */
    private String notInStashWorld() {
        if (stash == null) return "no stash";
        if (portalAwaiting()) return "in lobby, entering survival world";
        String dim = !stash.dimension.isEmpty() ? stash.dimension : stash.areaDimension;
        if (!dim.isEmpty() && !Stash.sameDimension(dim, Stash.currentDimension())) return "wrong dimension";

        // A matching dimension is not enough: the 6b6t proxy lobby and a
        // survival warehouse can both be minecraft:overworld, millions of
        // blocks apart. Never path towards a remote Manager region merely
        // because it names the same dimension.
        Vec3 at = mc.player.position();
        if (stash.hasArea() && stash.inArea(at.x, at.y, at.z, 128)) return null;
        for (Box box : stash.boxes) {
            if (box.known() && at.distanceToSqr(Vec3.atCenterOf(box.pos)) <= 128 * 128) return null;
        }
        return "too far from the stash";
    }

    // ---- 6b6t lobby portal ----

    private static final int PORTAL_PATH_STALL_TICKS = 200;
    private static final int PORTAL_ENTRY_TIMEOUT_TICKS = 100;
    private final PortalWorldSession portalSession = new PortalWorldSession();
    private long portalLastScan = 0;
    private long portalWaitWarnedAt = 0;
    private long lastFencedTransitWarnedAt = 0;
    // Retried portal approaches eventually give up with a blocker instead of looping forever.
    private int portalAttempts;
    private long portalGiveUpUntilMs;
    private static final int PORTAL_MAX_ATTEMPTS = 10;
    private static final long PORTAL_GIVE_UP_MS = 10L * 60 * 1000;
    private BlockPos portalTarget;
    private BlockPos portalApproach;
    private Vec3 portalLastPosition;
    private int portalStuckTicks;
    private int portalEntryTicks;
    private int portalTouchTicks;
    private boolean portalEntering;
    private boolean portalOtherSide;
    private boolean portalHoldingForward;
    private String portalBlocker;

    /** The Manager can be disabled during a manual crossing; never leave a synthetic key held. */
    void stopPortalNavigation() {
        portalForward(false);
        if (portalTarget != null || portalEntering) {
            releaseGoal();
            portalTarget = portalApproach = null;
            portalLastPosition = null;
            portalEntryTicks = portalStuckTicks = portalTouchTicks = 0;
            portalEntering = false;
            portalLastScan = 0;
        }
    }

    private void reportPortalBlocker(String reason) {
        if (reason.equals(portalBlocker)) return;
        portalBlocker = reason;
        WeSortAddon.LOG.info("6b6t portal waiting: {}", reason);
    }

    private boolean portalModeOn() {
        return switch (sixb6t.get()) {
            case On -> true;
            case Off -> false;
            case Auto -> Stash.is6b6tServer(Stash.currentServer());
        };
    }

    /**
     * A new 6b6t login near the lobby origin must not run stash navigation
     * while portal chunks are loading. Confirmed survival workers far away
     * can resume normally, subject to the separate stash proximity guard.
     */
    public boolean portalAwaiting() {
        if (mc.player == null || mc.level == null) return false;
        return portalSession.awaitingBootstrap(
                portalModeOn(),
                Stash.is6b6tServer(Stash.currentServer()),
                PortalEntry.in6b6tLobbyRegion(mc.player.blockPosition()));
    }

    /** Releases synthetic movement on completion, failure, yielding and disconnect. */
    private void portalForward(boolean hold) {
        if (portalHoldingForward == hold) return;
        portalHoldingForward = hold;
        if (mc.options != null) mc.options.keyUp.setDown(hold || Input.isPressed(mc.options.keyUp));
    }

    private boolean isPortal(BlockPos pos) {
        return mc.level != null
                && mc.level.isLoaded(pos)
                && mc.level.getBlockState(pos).is(Blocks.NETHER_PORTAL);
    }

    /** A real player touching the portal (including manual assistance) also counts as an entry attempt. */
    private boolean touchingPortal() {
        BlockPos feet = mc.player.blockPosition();
        return isPortal(feet) || isPortal(feet.above()) || isPortal(feet.below());
    }

    private boolean clearPortalApproach(BlockPos stand) {
        BlockPos head = stand.above();
        BlockPos floor = stand.below();
        if (!mc.level.isLoaded(stand) || !mc.level.isLoaded(head) || !mc.level.isLoaded(floor)) return false;
        var floorShape = mc.level.getBlockState(floor).getCollisionShape(mc.level, floor);
        // The actual 6b6t lobby walkway uses slabs and steps near the obsidian
        // frame. A full UP face would incorrectly discard those walkable cells.
        return mc.level.getBlockState(stand).getCollisionShape(mc.level, stand).isEmpty()
                && mc.level
                        .getBlockState(head)
                        .getCollisionShape(mc.level, head)
                        .isEmpty()
                && !floorShape.isEmpty()
                && floorShape.max(Direction.Axis.Y) >= 0.5
                && floorShape.max(Direction.Axis.Y) <= 1.0;
    }

    private record PortalRoute(BlockPos portal, BlockPos approach) {}

    /**
     * A portal is not a walkable Baritone GoalNear(0). Pick a safe adjacent stand on the
     * perpendicular side of the portal plane; the last step across the plane is done separately.
     */
    private PortalRoute nearestPortal(List<BlockPos> found) {
        PortalRoute best = null;
        double bestD = Double.MAX_VALUE;
        Vec3 at = mc.player.position();
        for (BlockPos p : found) {
            // Use the bottom portal cell so an approach aligns with the player's feet.
            if (isPortal(p.below())) continue;
            var state = mc.level.getBlockState(p);
            if (!state.hasProperty(net.minecraft.world.level.block.NetherPortalBlock.AXIS)) continue;
            Direction.Axis axis = state.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS);
            for (int side = 0; side < 2; side++) {
                BlockPos adjacent =
                        PortalEntry.approach(p, axis, at.x, at.z, side == 0 ? portalOtherSide : !portalOtherSide);
                // Lobby walkways can be one block above/below the bottom portal cell.
                for (int dy : new int[] {0, -1, 1}) {
                    BlockPos stand = adjacent.offset(0, dy, 0);
                    if (!clearPortalApproach(stand)
                            || !com.porter.wesort.compat.NavigationRuntime.safePortalApproach(stand)) continue;
                    double distance = at.distanceToSqr(Vec3.atCenterOf(stand));
                    if (distance < bestD) {
                        bestD = distance;
                        best = new PortalRoute(p.immutable(), stand.immutable());
                    }
                }
            }
        }
        return best;
    }

    /** Never leave an agent-held route or a pressed forward key behind after a failed attempt. */
    private void retryPortal() {
        portalForward(false);
        portalSession.cancelAttempt();
        releaseGoal();
        portalTarget = null;
        portalApproach = null;
        portalLastPosition = null;
        portalStuckTicks = 0;
        portalEntryTicks = 0;
        portalTouchTicks = 0;
        portalEntering = false;
        portalOtherSide = !portalOtherSide;
        portalLastScan = 0;
        if (++portalAttempts > PORTAL_MAX_ATTEMPTS && portalGiveUpUntilMs == 0) {
            portalGiveUpUntilMs = System.currentTimeMillis() + PORTAL_GIVE_UP_MS;
            warning(
                    "6b6t lobby: portal transit failed %d times, pausing attempts for %d minutes; cross manually.",
                    portalAttempts, PORTAL_GIVE_UP_MS / 60_000);
        }
    }

    /**
     * Runs only during lobby transit. Keeps all sorting and inventory work parked until a
     * post-entry world/dimension change. Walking into the portal is explicitly controlled
     * rather than relying on Baritone to path onto a non-walkable portal block.
     */
    private boolean portalTick() {
        if (portalSession.arrived() || !portalModeOn()) {
            portalForward(false);
            return false;
        }
        if (mc.player == null || mc.level == null) {
            portalForward(false);
            return true;
        }
        // A latched warehouse pause must not fence the lobby transit forever: with positive
        // lobby evidence it is dropped (a portal-route pause stays latched).
        if (portalSession.inLobby()) com.porter.wesort.compat.NavigationRuntime.clearWarehousePause();
        if (System.currentTimeMillis() < portalGiveUpUntilMs) {
            portalForward(false);
            reportPortalBlocker("portal transit paused after repeated failures; cross manually");
            return true;
        }
        if (portalGiveUpUntilMs != 0) {
            // The pause expired: count fresh attempts from here.
            portalGiveUpUntilMs = 0;
            portalAttempts = 0;
        }
        // Proxy worker switches do not always emit GameLeftEvent. If the
        // player is now in the likely lobby region, revoke an old warehouse
        // goal before any portal evidence is available. Never revoke a
        // currently verified portal-approach goal.
        if (portalAwaiting() && portalTarget == null && ourGoal != null) releaseGoal();

        if (portalSession.observe(mc.level, Stash.currentDimension(), mc.player.getX(), mc.player.getZ())) {
            stopPortalNavigation();
            portalBlocker = null;
            portalAttempts = 0;
            portalGiveUpUntilMs = 0;
            info("6b6t: confirmed survival transfer. Pre-transit readback is stale; a fresh survey is required before physical work.");
            wakeUp();
            return false;
        }

        // Production 6b6t lobby spawns near world origin while survival proxy
        // workers can use the same dimension at x=80k. Do not reinterpret a
        // distant survival portal as the lobby's login portal after reconnect.
        if (!portalSession.inLobby()
                && Stash.is6b6tServer(Stash.currentServer())
                && !PortalEntry.in6b6tLobbyRegion(mc.player.blockPosition())) {
            stopPortalNavigation();
            return false;
        }

        // First determine where the player actually is. The hostname alone
        // is not evidence of being in a lobby: returning users can spawn
        // directly on a survival worker with no portal anywhere nearby.
        long now = System.currentTimeMillis();
        if (portalTarget == null && now - portalLastScan >= 10_000) {
            portalLastScan = now;
            List<BlockPos> nearby = scanLocalPortalBlocks();
            if (!nearby.isEmpty()) {
                boolean firstLobby = !portalSession.inLobby();
                portalSession.lobbySeen(mc.player.getX(), mc.player.getZ());
                if (firstLobby) info("6b6t lobby confirmed: entering the nearby portal before sorting.");
                PortalRoute route = nearestPortal(nearby);
                if (route != null) {
                    portalTarget = route.portal();
                    portalApproach = route.approach();
                    portalLastPosition = mc.player.position();
                    portalStuckTicks = 0;
                } else if (now - portalWaitWarnedAt >= 60_000) {
                    portalWaitWarnedAt = now;
                    warning("6b6t lobby: portal blocks found, but no safe approach cell; inspect the walkway.");
                }
            } else if (portalSession.inLobby() && now - portalWaitWarnedAt >= 60_000) {
                portalWaitWarnedAt = now;
                warning("6b6t lobby: portal temporarily not visible in loaded nearby chunks; retrying.");
            }
        }
        if (!portalSession.inLobby()) {
            // Before chunk loading confirms the lobby we still hold every
            // warehouse route. Only a locally verified portal can authorize
            // the dedicated portal approach; an ordinary stash route cannot
            // sneak through while the chunks are still arriving.
            stopPortalNavigation();
            if (portalAwaiting() && now - portalWaitWarnedAt >= 60_000) {
                portalWaitWarnedAt = now;
                warning("6b6t lobby: waiting for portal chunks; warehouse navigation is blocked.");
            }
            return portalAwaiting();
        }

        if (touchingPortal()) {
            portalSession.crossingAttempted();
            portalForward(false);
            // A manual crossing may contact the portal without a tracked goal.
            // Do not leave an active entering state pointing at a null target if
            // the player steps out again before the proxy transfers worlds.
            portalEntering = false;
            portalEntryTicks = 0;
            if (portalTarget != null) {
                releaseGoal();
                portalTarget = portalApproach = null;
            }
            if (++portalTouchTicks == 400) {
                warning("6b6t lobby: inside the portal for 20 seconds without a world transfer; check the server.");
                portalTouchTicks = 0;
            }
            return true;
        }
        portalTouchTicks = 0;

        // Keep observing portal completion even when ordinary navigation is paused.
        // Never restart movement under a genuine safety pause.
        String blocker = portalMovementBlocker();
        if (blocker != null) {
            portalForward(false);
            reportPortalBlocker(blocker);
            return true;
        }
        // Preserve the fenced-transit diagnostic instead of flapping to null:
        // reportPortalBlocker dedupes, so no log spam while moving fenced.
        if (PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror()) {
            reportPortalBlocker("TRANSITING_WITH_FENCED_JOURNAL");
        } else {
            portalBlocker = null;
        }

        if (portalEntering) {
            if (portalTarget == null) {
                retryPortal();
                return true;
            }
            if (++portalEntryTicks > PORTAL_ENTRY_TIMEOUT_TICKS) {
                warning("6b6t lobby: portal entry did not complete; retrying the other side.");
                retryPortal();
            } else {
                mc.player.setYRot(PortalEntry.yawTowards(portalTarget, mc.player.getX(), mc.player.getZ()));
                portalForward(true);
            }
            return true;
        }

        if (portalTarget != null) {
            if (!isPortal(portalTarget) || !clearPortalApproach(portalApproach)) {
                retryPortal();
            } else if (PortalEntry.atApproach(portalApproach, mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
                releaseGoal();
                portalSession.crossingAttempted();
                portalEntering = true;
                portalEntryTicks = 0;
                mc.player.setYRot(PortalEntry.yawTowards(portalTarget, mc.player.getX(), mc.player.getZ()));
                portalForward(true);
            } else {
                portalForward(false);
                // Real position progress, not Baritone.isMoving(): a stuck path can be "active" forever.
                if (mc.player.position().distanceToSqr(portalLastPosition) > 0.35 * 0.35) {
                    portalLastPosition = mc.player.position();
                    portalStuckTicks = 0;
                } else if (++portalStuckTicks > PORTAL_PATH_STALL_TICKS) {
                    warning("6b6t lobby: no path progress to the portal; retrying from the other side.");
                    retryPortal();
                    return true;
                }
                if (ourGoal == null || !BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) ourGoal)) {
                    walkPortalApproach();
                }
            }
        }
        return true;
    }

    private String portalMovementBlocker() {
        if (com.porter.wesort.compat.NavigationRuntime.paused()) {
            return com.porter.wesort.compat.NavigationRuntime.status();
        }
        if (!BARITONE) return "BARITONE_UNAVAILABLE";
        if (StashManager.ownsClient()) {
            // Lobby navigation is allowed before inventory readback, so only dismiss
            // non-container screens here. Never close an unverified server menu.
            if (mc.player.containerMenu == mc.player.inventoryMenu
                    && mc.player.containerMenu.getCarried().isEmpty()
                    && mc.screen != null) mc.setScreen(null);
            if (mc.screen != null) return "SCREEN_OPEN";
        } else {
            if (mc.screen != null) return "SCREEN_OPEN";
            if (Input.isPressed(mc.options.keyDown)
                    || Input.isPressed(mc.options.keyLeft)
                    || Input.isPressed(mc.options.keyRight)
                    || Input.isPressed(mc.options.keyJump)) return "MANUAL_MOVEMENT";
        }
        if (BaritoneHelper.controlledByOther(ourGoal == null ? null : (baritone.api.pathing.goals.Goal) ourGoal)) {
            return "BARITONE_BUSY";
        }
        return null;
    }

    /** wesort-stash-manager's setting: orders may unpack shulkers with a custom name. */
    private static boolean namedForOrders() {
        StashManager manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager == null || manager.useNamedShulkers();
    }

    /** The kit being built is a transient one made for an order. */
    private boolean orderKit() {
        return kitBuilder.kit() != null && kitBuilder.kit().orderKit;
    }

    private boolean ordersOn() {
        return running() && shulkersOk();
    }

    private boolean runOrder(OrderRunner.Intent intent) {
        return switch (intent) {
            case OrderRunner.Idle idleStep -> false;
            case OrderRunner.Build build -> runKit(build.intent());
            case OrderRunner.Fetch fetch -> fetch(fetch.box(), fetch.slot());
            case OrderRunner.RecoverStage recovery -> {
                wantSlot = recovery.slot();
                fetchWants = stack -> !stack.isEmpty()
                        && com.porter.wesort.compat.SlotRuntime.snapshot(new ItemStack[] {stack})
                                .fingerprint()
                                .equals(recovery.fingerprint());
                yield start(Job.Fetch, recovery.box());
            }
            case OrderRunner.Deliver deliver -> {
                recheckOnly = false;
                lastDeliveryBox = deliver.box();
                yield start(Job.Deliver, deliver.box());
            }
            case OrderRunner.Expand expand -> startExpand();
            case OrderRunner.Recheck recheck -> startRecheck(recheck.box());
            case OrderRunner.Rename rename -> startName(rename);
            case OrderRunner.XpBottles xp -> fetchXp(xp.box(), xp.slot());
            case OrderRunner.Craft craft -> startOrderCraft(craft);
            case OrderRunner.FindAnvil find -> startFindAnvil();
        };
    }

    // Crafting (Job.Craft). The table is no Box: it is never read, sorted or indexed
    private static final int CRAFT_JOB_TICKS = 20 * 180,
            CRAFT_OPEN_TICKS = 20 * 15,
            CRAFT_OPEN_TRIES = 4,
            CRAFT_OPEN_RETRY = 40;
    private static final int CRAFT_COOLDOWN_TICKS = 20 * 60 * 5;
    // The reasons crafting is refused or given up on, worded as the proxy plugin has them
    private static final String TABLE_GONE = "the crafting table is gone",
            TABLE_NOT_REACHED = "couldn't reach the crafting table",
            TABLE_NOT_OPENED = "couldn't open the crafting table",
            TABLE_CLOSED = "the crafting table closed before it was done",
            TABLE_OTHER_DIMENSION = "the crafting table is in another dimension";
    private static final String SHULKER_BOX_ID = "minecraft:shulker_box";
    private BlockPos craftPos;
    private String craftFailure;
    private boolean craftDone, craftForOrder;
    private int craftJobTicks, craftOpenTries, craftOpenClickAt, craftDrainTries;
    // A table that failed, for which order: later crafts of that order don't try it again
    private final com.porter.wesort.craft.CraftHealth craftHealth = new com.porter.wesort.craft.CraftHealth();
    // How long the window has been kept open for lack of room to empty the grid, and whether the order was told
    private int craftStuckTicks;
    private boolean craftStuckReported;
    // Compaction asked for a craft that failed: not asked again for a while
    private int craftCooldownUntil;
    private String craftCooldownWhy = "";

    private boolean craftingOn() {
        StashManager manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager != null && manager.craftingEnabled();
    }

    private boolean isTable(BlockPos pos) {
        return mc.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    /** Why nothing can be crafted now (no table, it is gone, it failed for this order), or null. */
    private String craftBlocker() {
        if (stash == null) return TableFinder.NONE;
        if (mc.level == null || mc.player == null) return "no world";
        if (!stash.dimension.isEmpty() && !stash.dimension.equals(Stash.currentDimension()))
            return TABLE_OTHER_DIMENSION;
        // A table that is set or remembered and still there: only trouble it caused for this order stops the craft
        if (!tableMissing()) return craftHealth.problem(orderRunner.orderId(), stash.craftingTablePos);
        // None, or it is gone: crafting goes on when one can be found, taken from the stash or crafted (nothing is done
        // here)
        TableFinder.Decision d = tablePreCheck.get(System.currentTimeMillis(), TABLE_PRECHECK_MS, this::decideTable);
        return d.step() == TableFinder.Step.FAIL ? d.failure() : null;
    }

    /** No crafting table is set or remembered, or the one that was is gone. */
    private boolean tableMissing() {
        if (stash == null || stash.craftingTablePos == null) return true;
        return mc.level != null && mc.level.isLoaded(stash.craftingTablePos) && !isTable(stash.craftingTablePos);
    }

    /** For the heartbeat: a crafting table is set and, where its chunk is loaded, it is one. */
    boolean craftingTableReady() {
        Stash s = ensureStash();
        if (s == null || s.craftingTablePos == null || mc.level == null) return false;
        return !mc.level.isLoaded(s.craftingTablePos) || isTable(s.craftingTablePos);
    }

    BlockPos craftingTablePos() {
        Stash s = ensureStash();
        return s == null ? null : s.craftingTablePos;
    }

    /** For the heartbeat: where the table in use came from: "set", "found", "placed", "crafted" or "none". */
    String craftingTableSource() {
        Stash s = ensureStash();
        if (s == null || s.craftingTablePos == null) return "none";
        return s.craftingTableSource.isEmpty() ? "set" : s.craftingTableSource;
    }

    /**
     * For wesort-stash-manager: sets the crafting table, or clears it if it is this one.
     * @return 1 when set, 0 when cleared, -1 when the block is no crafting table, -2 when no stash is loaded
     */
    int toggleCraftingTable(BlockPos pos) {
        if (mc.level == null || ensureStash() == null) return -2;
        if (stash.craftingTablePos != null && stash.craftingTablePos.equals(pos)) {
            stash.craftingTablePos = null;
            stash.craftingTableSource = "";
            stash.save();
            return 0;
        }
        if (!isTable(pos)) return -1;
        stash.craftingTablePos = pos.immutable();
        stash.craftingTableSource = "set";
        tableChanged();
        stash.save();
        return 1;
    }

    /** For wesort-stash-manager: no crafting table any more. @return false when there was none or no stash */
    boolean clearCraftingTable() {
        if (ensureStash() == null || stash.craftingTablePos == null) return false;
        stash.craftingTablePos = null;
        stash.craftingTableSource = "";
        stash.save();
        return true;
    }

    // Finding a crafting table to craft at (before a craft that needs one). In order: the one picked by hand, a
    // remembered one, a
    // placed table found in the stash area, one taken from the stash and placed, one crafted in the inventory's 2x2
    // grid and placed.
    // Nothing works: the craft fails with TableFinder.NONE. The placing is the anvil's (Job.AnvilPlace) with a table in
    // hand.
    private enum TableResume {
        Order,
        Compaction,
        Expand,
        Anvil
    }

    private enum TablePrep {
        READY,
        GATHERING,
        PLACING,
        FAILED
    }

    private final TableFinder.Throttle tableThrottle = new TableFinder.Throttle();
    private boolean tableSession, placingTable, tableCrafted;
    private TableResume tableResume = TableResume.Order;
    // Why the table could not be had, as the failure text ("no crafting table available: <cause>"), and why placing
    // failed
    private String tableWhy = TableFinder.NONE, placeWhy = "";
    // Slots that held a crafting table before the table session began: never the one placed
    private final Set<Integer> tablePre = new HashSet<>();
    // The pre-check of a craft (can a table be had?) is kept for a few seconds, the world and the stash are not combed
    // on every ask
    private static final long TABLE_PRECHECK_MS = 3000;
    private final TableFinder.Memo<TableFinder.Decision> tablePreCheck = new TableFinder.Memo<>();
    private int tableWaitTicks;

    /** The table position, or what it can be had from, changed: the kept answers are dropped. */
    private void tableChanged() {
        tableThrottle.reset();
        tablePreCheck.reset();
    }

    private TableFinder.World tableWorld() {
        return new TableFinder.World() {
            @Override
            public boolean isTable(TableFinder.Pos p) {
                BlockPos at = new BlockPos(p.x(), p.y(), p.z());
                return !mc.level.isLoaded(at) || StashSorter.this.isTable(at);
            }

            @Override
            public TableFinder.Pos search() {
                long now = System.currentTimeMillis();
                TableFinder.Pos from =
                        new TableFinder.Pos(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
                java.util.function.Supplier<TableFinder.Pos> look = () -> {
                    PlaceSearch.View view = placeView();
                    List<TableFinder.Pos> tables = new ArrayList<>();
                    for (BlockPos t : scanArea(st -> st.is(Blocks.CRAFTING_TABLE), true))
                        tables.add(new TableFinder.Pos(t.getX(), t.getY(), t.getZ()));
                    return TableFinder.nearest(tables, from, p -> {
                        BlockPos t = new BlockPos(p.x(), p.y(), p.z());
                        if (stash.hasArea() && !stash.inArea(t)) return false;
                        // One the bot can stand next to
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dz = -1; dz <= 1; dz++)
                                for (int dy = -1; dy <= 1; dy++) {
                                    if ((dx != 0 || dz != 0)
                                            && view.standable(t.getX() + dx, t.getY() + dy, t.getZ() + dz)) return true;
                                }
                        return false;
                    });
                };
                TableFinder.Pos hit = tableThrottle.get(now, look);
                // An answer from the last 10 seconds that is not a table any more is looked for again
                if (hit != null && !isTable(hit)) {
                    tableThrottle.reset();
                    hit = tableThrottle.get(now, look);
                }
                return hit;
            }

            @Override
            public boolean stockHasTable() {
                return orderRunner.craftPool().getOrDefault(TableFinder.TABLE_ID, 0L) > 0;
            }

            @Override
            public TableFinder.Material craftMaterial() {
                StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get()
                        .get(StashManager.class);
                return TableFinder.material(
                        Crafter.recipes(),
                        orderRunner.craftPool(),
                        manager == null ? Set.of() : manager.craftNeverUse());
            }
        };
    }

    private TableFinder.Decision decideTable() {
        TableFinder.Memory memory = stash.craftingTablePos == null
                ? null
                : new TableFinder.Memory(
                        new TableFinder.Pos(
                                stash.craftingTablePos.getX(),
                                stash.craftingTablePos.getY(),
                                stash.craftingTablePos.getZ()),
                        TableFinder.Source.of(stash.craftingTableSource.isEmpty() ? "set" : stash.craftingTableSource));
        return TableFinder.decide(memory, tableWorld());
    }

    /** What is in the inventory window's crafting grid and on its cursor, as text ("" when nothing). */
    private String gridLeftovers() {
        if (mc.player == null) return "";
        var menu = mc.player.inventoryMenu;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty())
                sb.append(sb.length() > 0 ? ", " : "")
                        .append(s.getCount())
                        .append("x ")
                        .append(s.getHoverName().getString());
        }
        ItemStack c = menu.getCarried();
        if (!c.isEmpty())
            sb.append(sb.length() > 0 ? ", " : "")
                    .append(c.getCount())
                    .append("x ")
                    .append(c.getHoverName().getString())
                    .append(" (cursor)");
        return sb.toString();
    }

    private static boolean tableItem(ItemStack s) {
        return !s.isEmpty() && s.is(Items.CRAFTING_TABLE);
    }

    /** The inventory slot of a crafting table item gathered or crafted for placing, or -1. */
    private int tableItemSlot(boolean fromSession) {
        var inv = mc.player.getInventory();
        if (placingTable && anvilSlot >= 0 && tableItem(inv.getItem(anvilSlot))) return anvilSlot;
        if (!fromSession) return -1;
        // Only what the table session itself held, and never a slot that held a table before it began (an order line
        // may need those)
        for (int slot : crafter.lastHeld()) {
            if (tableItem(inv.getItem(slot))
                    && !tablePre.contains(slot)
                    && (stashHeld.containsKey(slot) || orderHeld.containsKey(slot))) return slot;
        }
        return -1;
    }

    /**
     * A craft needs a table and none is set or remembered: finds one placed, or starts getting one to place. The caller drives what
     * was started: GATHERING is a crafter session to be run, PLACING a job already started. When it is over {@link #tableAcquired}
     * tells whoever asked.
     */
    private TablePrep prepareTable(TableResume resume) {
        if (stash == null || mc.level == null || mc.player == null) return TablePrep.FAILED;
        tableResume = resume;
        // A table taken out or crafted earlier and not yet placed (the job was interrupted)
        int held = tableItemSlot(false);
        if (held >= 0) tableCrafted = false;
        if (held >= 0 && startTablePlace(held)) return TablePrep.PLACING;

        TableFinder.Decision decision = decideTable();
        if (decision.forget()) {
            info("The crafting table I knew is gone, looking for another.");
            stash.craftingTablePos = null;
            stash.craftingTableSource = "";
            stash.save();
            tableChanged();
        }
        tablePre.clear();
        var invNow = mc.player.getInventory();
        for (int i = 0; i < invNow.getContainerSize(); i++) if (tableItem(invNow.getItem(i))) tablePre.add(i);
        switch (decision.step()) {
            case USE -> {
                return TablePrep.READY;
            }
            case FOUND -> {
                stash.craftingTablePos = new BlockPos(
                        decision.pos().x(), decision.pos().y(), decision.pos().z());
                stash.craftingTableSource = "found";
                stash.save();
                info("Found a crafting table in the stash area, using it for crafting from now on.");
                return TablePrep.READY;
            }
            case PLACE_FROM_STOCK -> {
                String why = crafter.begin(new Crafter.Request(
                        TableFinder.TABLE_ID,
                        1,
                        "placing",
                        false,
                        0,
                        orderRunner::craftPool,
                        orderRunner::lotKeys,
                        true));
                if (why != null) {
                    warning("No crafting table to place: %s.", why);
                    tableWhy = TableFinder.failure("couldn't take one from the stash: " + why);
                    return TablePrep.FAILED;
                }
                tableCrafted = false;
                info("No crafting table in the area, taking one out of the stash to place.");
            }
            case CRAFT -> {
                String why = crafter.begin(new Crafter.Request(
                        TableFinder.TABLE_ID,
                        1,
                        "placing",
                        false,
                        0,
                        orderRunner::craftPool,
                        orderRunner::lotKeys,
                        false));
                if (why != null) {
                    warning("No crafting table to craft: %s.", why);
                    tableWhy = TableFinder.failure("couldn't craft one: " + why);
                    return TablePrep.FAILED;
                }
                crafter.useInventoryGrid();
                tableCrafted = true;
                info(
                        "No crafting table in the area or the stash, crafting one from %s in the inventory grid.",
                        decision.material() == TableFinder.Material.LOGS ? "logs" : "planks");
            }
            default -> {
                warning("%s.", decision.failure());
                tableWhy = decision.failure();
                return TablePrep.FAILED;
            }
        }
        craftForOrder = resume == TableResume.Order;
        tableSession = true;
        return TablePrep.GATHERING;
    }

    private boolean startTablePlace(int slot) {
        return startPlaceItem(slot, true);
    }

    /** The table is there (or not): whoever asked is told, and carries on. */
    private void tableAcquired(boolean ok) {
        switch (tableResume) {
            case Order -> {
                if (ok) orderRunner.tableReady();
                else orderRunner.craftEnded(new Crafter.Outcome(SHULKER_BOX_ID, 0, tableWhy));
            }
            case Compaction -> {
                if (!ok) {
                    craftCooldownUntil = ticks + CRAFT_COOLDOWN_TICKS;
                    craftCooldownWhy = tableWhy;
                }
            }
            case Expand -> {
                if (ok) startExpand(true);
                else expandFailed("no chests left in the stash, and " + tableWhy);
            }
            case Anvil -> {
                if (!ok) orderRunner.anvilFound(false);
                else startAnvilCraft();
            }
        }
    }

    private final Crafter crafter = new Crafter(new Crafter.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean usable(Box box) {
            return !blocked(box, Job.Take) && !blocked(box, Job.CraftTake) && !box.unreachable;
        }

        @Override
        public boolean mayTakeFrom(Box box, int slot) {
            return orderRunner.mayTakeFrom(box, slot);
        }

        @Override
        public boolean keepWhole(Box box) {
            return box.protectedStorage || Kits.isKitBox(box);
        }

        @Override
        public boolean namedShulkers() {
            return namedForOrders();
        }

        @Override
        public boolean claimed(ItemStack shulker) {
            return Kits.claims(shulker) || orderRunner.claims(shulker) || kitBuilder.heldKit(shulker) != null;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
            noteOrderHeld(slot, stack);
        }

        @Override
        public double distance(Box box) {
            return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
        }

        @Override
        public Set<String> neverUse() {
            StashManager manager =
                    meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
            return manager == null ? Set.of() : manager.craftNeverUse();
        }

        @Override
        public com.porter.wesort.craft.CraftPlanner.Caps caps() {
            StashManager manager =
                    meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
            return manager == null ? com.porter.wesort.craft.CraftPlanner.Caps.NONE : manager.craftCaps();
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }
    });

    /** Starts the step the crafter asks for. @return true when a job was started */
    private boolean runCraft(Crafter.Intent intent) {
        switch (intent) {
            case Crafter.Take take -> {
                if (start(Job.CraftTake, take.box())) return true;
                crafter.abort("couldn't reach " + take.box().pos.toShortString());
            }
            case Crafter.Fetch fetch -> {
                if (fetch(fetch.box(), fetch.slot())) return true;
                crafter.abort("couldn't reach " + fetch.box().pos.toShortString());
            }
            case Crafter.Unpack unpack -> {
                if (shulkersOk()) return startHandling(Job.CraftUnpack, unpack.slot(), null);
                crafter.abort("placing shulkers failed lately, so no shulker work for a few minutes");
            }
            case Crafter.Table table -> {
                return startCraftTable();
            }
            case Crafter.Done done -> {}
            case Crafter.Abort abort -> crafter.abort(abort.reason());
        }
        endCraft();
        return false;
    }

    /** The crafting session is over: the order (or the compactor) is told how it went. */
    private void endCraft() {
        Crafter.Outcome outcome = crafter.finish();
        if (tableSession) {
            // The crafting table was only gathered or crafted: it is placed now
            tableSession = false;
            craftForOrder = false;
            int slot = outcome.failure() == null ? tableItemSlot(true) : -1;
            if (slot < 0 || !startTablePlace(slot)) {
                String cause = outcome.failure() == null ? "the crafting table couldn't be taken" : outcome.failure();
                warning("No crafting table to place: %s.", cause);
                tableWhy = TableFinder.failure(cause);
                tableAcquired(false);
            }
            // What the session held is not looked at again
            crafter.clearLastHeld();
            version++;
            idle = false;
            return;
        }
        if (chestSession) {
            // The chests for the delivery storage were only gathered or crafted: they are put up now
            chestSession = false;
            craftForOrder = false;
            ChestStack.Result res = outcome.failure() == null ? stackPlan() : null;
            if (outcome.failure() != null) {
                crafter.clearLastHeld();
                expandFailed("no chests: " + outcome.failure());
            } else if (res.plan() == null) expandFailed(res.blocked());
            else if (!startChestPlace(res.plan())) expandFailed("it couldn't be put up");
            version++;
            idle = false;
            return;
        }
        if (anvilSession) {
            // The anvil was only gathered: it is placed now
            anvilSession = false;
            craftForOrder = false;
            int slot = outcome.failure() == null ? anvilItemSlot() : -1;
            if (slot < 0 || !startAnvilPlace(slot)) {
                crafter.clearLastHeld();
                warning(
                        "No anvil to place: %s.",
                        outcome.failure() == null ? "the anvil couldn't be taken" : outcome.failure());
                orderRunner.anvilFound(false);
            }
            version++;
            idle = false;
            return;
        }
        if (craftForOrder) orderRunner.craftEnded(outcome);
        else {
            if (outcome.failure() != null) {
                craftCooldownUntil = ticks + CRAFT_COOLDOWN_TICKS;
                craftCooldownWhy = outcome.failure();
                warning("Couldn't craft a shulker box: %s.", outcome.failure());
            } else if (outcome.crafted() > 0) info("Crafted %d Shulker Box.", outcome.crafted());
        }
        craftForOrder = false;
        crafter.clearLastHeld();
        version++;
        idle = false;
    }

    /** Craft a pinned order product, or produce an empty shulker for packing. */
    private boolean startOrderCraft(OrderRunner.Craft craft) {
        if (!crafter.active()) {
            boolean manufacturing = orderRunner.manufacturing();
            String item = craft.item();
            java.util.function.Supplier<Map<String, Long>> pool =
                    manufacturing ? orderRunner::manufacturingPool : orderRunner::craftPool;
            if (manufacturing) {
                // A selected Manager workbench must already exist: never spend
                // unreserved materials manufacturing a replacement table.
                if (stash == null || tableMissing()) {
                    orderRunner.craftEnded(new Crafter.Outcome(item, 0, "the selected workbench is missing"));
                    return false;
                }
                orderRunner.manufacturingIntent();
            }
            // For shulker packing, retain the existing fallback to find/place a
            // missing table. For new product manufacturing, the table is pinned.
            if (!manufacturing && stash != null && tableMissing()) {
                var preview = crafter.preview(item, craft.count(), orderRunner.crafted(), pool.get(), false);
                if (!preview.ok()) {
                    orderRunner.craftEnded(new Crafter.Outcome(item, 0, preview.refusal()));
                    return false;
                }
                switch (prepareTable(TableResume.Order)) {
                    case GATHERING -> {
                        return runCraft(crafter.next());
                    }
                    case PLACING -> {
                        return true;
                    }
                    case FAILED -> {
                        orderRunner.craftEnded(new Crafter.Outcome(item, 0, tableWhy));
                        return false;
                    }
                    default -> {}
                }
            }
            String why = crafter.begin(new Crafter.Request(
                    item,
                    craft.count(),
                    "order " + orderRunner.orderId(),
                    !manufacturing,
                    manufacturing ? 0 : orderRunner.crafted(),
                    pool,
                    orderRunner::lotKeys,
                    false,
                    manufacturing && orderRunner.manufacturingHasProgress()));
            if (why != null) {
                orderRunner.craftEnded(new Crafter.Outcome(item, 0, why));
                return false;
            }
            craftForOrder = true;
        }
        return runCraft(crafter.next());
    }

    /** Compaction has no empty shulker: crafts one. @return null when started, "" when crafting is off, else why not */
    private String craftEmptyForCompaction(String purpose) {
        if (!craftingOn()) return "";
        if (ticks < craftCooldownUntil) return craftCooldownWhy;
        if (crafter.active()) return null;
        String why = craftBlocker();
        if (why == null && tableMissing()) {
            // The craft itself must be possible before a table is put in the world for it
            var preview = crafter.preview(SHULKER_BOX_ID, 1, 0, crafter.survey(), false);
            if (!preview.ok()) why = preview.refusal();
            else {
                switch (prepareTable(TableResume.Compaction)) {
                    case GATHERING, PLACING -> {
                        return null;
                    }
                    case FAILED -> why = tableWhy;
                    default -> {}
                }
            }
        }
        if (why == null)
            why = crafter.begin(new Crafter.Request(SHULKER_BOX_ID, 1, purpose, false, 0, crafter::survey));
        if (why != null) {
            craftCooldownUntil = ticks + CRAFT_COOLDOWN_TICKS;
            craftCooldownWhy = why;
            return why;
        }
        craftForOrder = false;
        return null;
    }

    private boolean startCraftTable() {
        if (crafter.inventoryGrid()) return startInventoryCraft();
        String bad = craftBlocker();
        BlockPos table = stash == null ? null : stash.craftingTablePos;
        // The table was cleared (from the website) or forgotten while the session ran
        if (table == null) {
            crafter.tableEnded(TableFinder.failure("it was cleared while crafting"));
            return false;
        }
        if (bad == null && tableMissing()) bad = TABLE_GONE;
        if (bad == null && !walkOn() && !inReachOf(table)) bad = TABLE_NOT_REACHED;
        if (bad != null) {
            crafter.tableEnded(bad);
            return false;
        }

        craftPos = table;
        craftFailure = null;
        craftDone = false;
        craftJobTicks = craftOpenTries = craftOpenClickAt = craftDrainTries = 0;
        craftStuckTicks = 0;
        craftStuckReported = false;

        job = Job.Craft;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step(
                craftForOrder
                        ? "order: crafting at the crafting table; next: carry on with the order."
                        : "crafting: at the crafting table; next: carry on.");

        if (inReachOf(craftPos)) step = Step.Opening;
        else {
            walkNear(craftPos, 2);
            // The table may be outside the stash area: that is not a reason to refuse the way
            walkInside = false;
            step = Step.Walking;
        }
        return true;
    }

    /** Crafts in the 2x2 grid of the inventory window: no table, no walking, no window to open. */
    private boolean startInventoryCraft() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            if (!ourMenu(mc.player.containerMenu)) {
                crafter.tableEnded("another window is open");
                return false;
            }
            if (!mc.player.containerMenu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(mc.player.containerMenu);
        }
        craftPos = null;
        craftFailure = null;
        craftDone = false;
        craftJobTicks = craftOpenTries = craftOpenClickAt = craftDrainTries = 0;
        craftStuckTicks = 0;
        tableWaitTicks = 0;
        craftStuckReported = false;

        job = Job.Craft;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step("crafting: a crafting table in the inventory grid; next: place it.");
        step = Step.Working;
        wait = delay.get();
        return true;
    }

    private void craftTick() {
        stepTicks++;
        lastOwnAct = ticks;
        if (step == Step.Working) craftJobTicks++;
        if (craftJobTicks > CRAFT_JOB_TICKS + 400) {
            if (craftFailure == null) craftFailure = "crafting didn't finish";
            craftEnd();
            return;
        }
        if (craftJobTicks > CRAFT_JOB_TICKS && craftFailure == null) craftFailure = "crafting didn't finish";
        switch (step) {
            case Walking -> craftWalk();
            case Opening -> craftOpen();
            case Working -> craftWork();
        }
    }

    private void craftWalk() {
        if (mc.level.isLoaded(craftPos) && !isTable(craftPos)) {
            craftFailure = TABLE_GONE;
            craftEnd();
            return;
        }
        if (inReachOf(craftPos)) {
            releaseGoal();
            step = Step.Opening;
            stepTicks = 0;
            craftOpenTries = 0;
            return;
        }
        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (stopped || stepTicks > WALK_TIMEOUT) {
            craftFailure = TABLE_NOT_REACHED;
            craftEnd();
        }
    }

    private void craftOpen() {
        if (!isTable(craftPos)) {
            craftFailure = TABLE_GONE;
            craftEnd();
            return;
        }
        AbstractContainerMenu open = mc.player.containerMenu;
        if (open != mc.player.inventoryMenu) {
            if (openPos != null
                    && open != openMenuBefore
                    && open instanceof net.minecraft.world.inventory.CraftingMenu) {
                ownedMenu = open;
                step = Step.Working;
                stepTicks = 0;
                wait = firstClickWait();
                return;
            }
            // Something else opened (one left over, a plugin window): closed, and the click made again
            if (!open.getCarried().isEmpty()) recoverCursor();
            mc.player.closeContainer();
            lastCloseTick = ticks;
        }

        if (openHeld()) {
            stepTicks--;
            return;
        }

        if (stepTicks > CRAFT_OPEN_TICKS
                || (craftOpenTries >= CRAFT_OPEN_TRIES && stepTicks - craftOpenClickAt > CRAFT_OPEN_RETRY)) {
            craftFailure = TABLE_NOT_OPENED;
            craftEnd();
            return;
        }

        if (craftOpenTries < CRAFT_OPEN_TRIES
                && (craftOpenTries == 0 || stepTicks - craftOpenClickAt >= CRAFT_OPEN_RETRY)) {
            craftOpenTries++;
            craftOpenClickAt = stepTicks;
            BlockPos pos = craftPos;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> {
                if (gate != Gate.Running || job != Job.Craft) return;
                openPos = pos;
                openClickTick = ticks;
                openMenuBefore = mc.player.containerMenu;
                // W-061: the exact container this job opens, nothing else. TTL-bound (rotation
                // callbacks run ticks later), exact position + block + job, revoked by expiry.
                com.porter.wesort.compat.WorldInteractionGuard.authorizeUse(pos);
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    /** One click in the open crafting window: always PICKUP, never a shift-click. */
    private void craftClick(AbstractContainerMenu menu, int slot, int button) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType.PICKUP, mc.player);
        yielder.inventoryChangedByUs();
        wait = Math.max(delay.get(), 4);
    }

    private void craftWork() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean grid = crafter.inventoryGrid();
        if (grid
                ? menu != mc.player.inventoryMenu
                : (!(menu instanceof net.minecraft.world.inventory.CraftingMenu) || menu != ownedMenu)) {
            craftEnd();
            return;
        }
        if (craftFailure == null && crafter.failure() != null) craftFailure = crafter.failure();
        if (craftFailure != null || craftDone) {
            craftEnd();
            return;
        }

        Crafter.Act act = crafter.window(menu);
        switch (act.kind()) {
            case Crafter.Act.CLICK -> craftClick(menu, act.slot(), act.button());
            case Crafter.Act.DONE -> {
                craftDone = true;
                craftEnd();
            }
            case Crafter.Act.FAIL -> {
                craftFailure = act.why();
                craftEnd();
            }
            default -> {}
        }
    }

    /**
     * Ends the job at the table. The window is never closed with something in the grid or on the cursor: it is taken back
     * first, and when there is no room for it the window stays open and the player is told.
     */
    private void craftEnd() {
        if (ourGoal != null) releaseGoal();
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean grid = crafter.inventoryGrid();
        // The inventory window can't be drained while another window is open: it is waited for (our own is closed), up
        // to 10 seconds
        if (grid && menu != mc.player.inventoryMenu) {
            if (ourMenu(menu)) {
                if (!menu.getCarried().isEmpty()) recoverCursor();
                closeOurWindow(menu);
                return;
            }
            if (++tableWaitTicks <= 20 * 10) return;
            if (craftFailure == null) craftFailure = "another window is open";
            String left = gridLeftovers();
            if (!left.isEmpty())
                alert(
                        "Another window is open, so I left items in the inventory's crafting grid: %s. They come back to your inventory when you close it.",
                        left);
        }
        boolean mine = grid
                ? menu == mc.player.inventoryMenu
                : menu instanceof net.minecraft.world.inventory.CraftingMenu && menu == ownedMenu;
        if (mine && !crafter.clean(menu)) {
            Crafter.Act back = crafter.drain(menu);
            if (back != null && back.kind() == Crafter.Act.CLICK) {
                craftDrainTries++;
                craftClick(menu, back.slot(), back.button());
                return;
            }
            if (craftDrainTries++ % 200 == 0)
                alert(
                        grid
                                ? "No room for what is in the inventory's crafting grid. Please make room in your inventory."
                                : "No room for what is in the crafting table, keeping the window open. Please make room in your inventory.");
            // After about two minutes the order step ends with a clear reason; the window stays open and the alert goes
            // on
            if (++craftStuckTicks > 20 * 120 && !craftStuckReported) {
                craftStuckReported = true;
                crafter.abort(com.porter.wesort.craft.CraftHealth.NO_ROOM);
                if (craftForOrder)
                    orderRunner.abortOrder(
                            com.porter.wesort.craft.CraftHealth.NO_ROOM + " (the crafting window is still open)");
            }
            return;
        }
        if (!grid && menu != mc.player.inventoryMenu && ourMenu(menu)) closeOurWindow(menu);
        ownedMenu = null;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;

        String why = craftFailure;
        if (why == null && !craftDone) why = TABLE_CLOSED;
        if (why != null) {
            WeSortAddon.LOG.info("[wesort-stash-sorter] crafting given up: {}", why);
        }
        // Table trouble is remembered for the order the way the plugin does: no result, not taken, couldn't reach or
        // open, or
        // two closes in a row. Not after a single close
        if (!grid) craftHealth.note(orderRunner.orderId(), craftPos, why);
        // A found, placed or crafted table that can't be reached or opened is forgotten, so the next craft searches
        // again; a hand-set one never
        if (!grid
                && (TABLE_NOT_REACHED.equals(why) || TABLE_NOT_OPENED.equals(why))
                && stash.craftingTablePos != null
                && stash.craftingTablePos.equals(craftPos)
                && !stash.craftingTableSource.isEmpty()
                && !stash.craftingTableSource.equals("set")) {
            stash.craftingTablePos = null;
            stash.craftingTableSource = "";
            stash.save();
            tableChanged();
        }
        crafter.tableEnded(why);
        craftFailure = null;
        craftDone = false;
        job = null;
        target = null;
        wait = delay.get();
    }

    // Naming a shulker at the anvil (Job.Name). Not a container job: the anvil is no Box, it is never read, sorted or
    // indexed
    private static final int NAME_RESULT_TICKS = 20 * 30,
            NAME_JOB_TICKS = 20 * 40,
            NAME_SETTLE_TICKS = 6,
            NAME_COST_WAIT_TICKS = 20 * 3;
    private static final int NAME_CHECK_TICKS = 20 * 5, NAME_MAX_TAKES = 3, NAME_TOO_DEAR = 40;
    private static final int NAME_OPEN_TICKS = 20 * 15, NAME_OPEN_TRIES = 4, NAME_OPEN_RETRY = 40;
    // The reasons a shulker goes unnamed, worded as the proxy plugin has them
    private static final String NO_ANVIL = "no anvil set",
            ANVIL_GONE = "the anvil is gone (the block there isn't an anvil)",
            OTHER_DIMENSION = "the anvil is in another dimension",
            TOO_FEW_LEVELS = "too few XP levels",
            NOT_REACHED = "couldn't reach the anvil",
            NOT_OPENED = "couldn't open the anvil",
            CLOSED = "the anvil closed before it was done",
            TOO_DEAR = "the anvil said it is too expensive",
            NO_RESULT = "no result from the anvil in time",
            NOT_TAKEN = "the anvil didn't give the shulker back named",
            NAME_UNFINISHED = "naming didn't finish";
    private int nameTakes, nameCheckTicks, nameOpenTries, nameOpenClickAt, nameDrainTries, nameClosedStreak;
    private boolean nameSucceeded;
    private String nameStreakOrder;
    // Why the anvil could not be used (reached, opened), for which order and anvil: later shulkers of that order go
    // unnamed
    // at once instead of walking there again
    private String anvilFailure, anvilFailedOrder;
    private BlockPos anvilFailedFor;
    private BlockPos namePos;
    private String nameText;
    private String nameFailure;
    private int nameSlot = -1, nameSentAt, nameJobTicks, nameResultSeen;
    private boolean nameSent, namePlaced;
    private ItemStack nameExpected = ItemStack.EMPTY;
    private boolean nameStashOwned, nameOrderOwned;
    // Topping up XP with bottles of enchanting before the rename: thrown standing still, then the walk starts
    private boolean xpPending, xpSwapped;
    private int xpThrows, xpLastThrow, xpMoveTo = -1;
    private long xpAttempt;
    private boolean xpThrowQueued;
    private static final int XP_THROW_GAP = 40, XP_TIMEOUT_TICKS = 20 * 120, XP_MAX_THROWS = 32;

    /** Whether the rename must wait for thrown bottles: short on levels, stash bottles in hand. */
    private boolean needsXpTopUp() {
        return mc.player != null
                && !mc.player.hasInfiniteMaterials()
                && mc.player.experienceLevel < xpTargetLevels
                && xpBottleSlot() >= 0;
    }
    // The carry slot the shulker came from: it is put back there, and found again if it landed elsewhere
    private int relocateSlot = -1;

    private boolean isAnvil(BlockPos pos) {
        return mc.level.getBlockState(pos).is(net.minecraft.tags.BlockTags.ANVIL);
    }

    /** Why shulkers can't be named now, or null. */
    private String anvilProblem() {
        if (stash == null || stash.anvilPos == null) return NO_ANVIL;
        if (mc.level == null || mc.player == null) return "no world";
        if (!stash.dimension.isEmpty() && !stash.dimension.equals(Stash.currentDimension())) return OTHER_DIMENSION;
        if (mc.level.isLoaded(stash.anvilPos) && !isAnvil(stash.anvilPos)) return ANVIL_GONE;
        if (anvilFailure != null
                && stash.anvilPos.equals(anvilFailedFor)
                && java.util.Objects.equals(anvilFailedOrder, orderRunner.orderId())) return anvilFailure;
        if (!mc.player.hasInfiniteMaterials() && mc.player.experienceLevel < 1 && !xpTopUpAvailable())
            return TOO_FEW_LEVELS;
        return null;
    }

    /** For the heartbeat: an anvil is set and, where its chunk is loaded, it is one. */
    boolean anvilReady() {
        Stash s = ensureStash();
        if (s == null || s.anvilPos == null || mc.level == null) return false;
        return !mc.level.isLoaded(s.anvilPos) || isAnvil(s.anvilPos);
    }

    /** For the heartbeat: the bot's XP level, or -1. */
    int xpLevels() {
        return mc.player == null ? -1 : mc.player.experienceLevel;
    }

    BlockPos anvilPos() {
        Stash s = ensureStash();
        return s == null ? null : s.anvilPos;
    }

    /**
     * For wesort-stash-manager: sets the anvil, or clears it if it is this one.
     * @return 1 when set, 0 when cleared, -1 when the block is no anvil, -2 when no stash is loaded
     */
    int toggleAnvil(BlockPos pos) {
        if (mc.level == null || ensureStash() == null) return -2;
        if (stash.anvilPos != null && stash.anvilPos.equals(pos)) {
            stash.anvilPos = null;
            stash.anvilSource = "";
            stash.save();
            return 0;
        }
        if (!isAnvil(pos)) return -1;
        stash.anvilPos = pos.immutable();
        stash.anvilSource = "set";
        stash.save();
        return 1;
    }

    /** For wesort-stash-manager: no anvil any more. @return false when there was none or no stash */
    boolean clearAnvil() {
        if (ensureStash() == null || stash.anvilPos == null) return false;
        stash.anvilPos = null;
        stash.anvilSource = "";
        stash.save();
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Delivery storage (0.4.0-dev.5; the proxy plugin 1.9.0 has the same): the delivery container and the double chests
    // put up above
    // it (DeliveryBook keeps which are full). With auto-expand-delivery on, when every chest is full another double
    // chest is taken
    // from the stash (or crafted, with crafting on) and put up one block above the top row by ChestPlacer: the first
    // half by a
    // sneaking click on the top of the chest below, the second by a sneaking click on the side of the first (ChestStack
    // has the
    // rules). A chest a player emptied is found by its lid (StashWatch, the lid animation) and looked at once; where
    // the lid was
    // out of sight, by looking again now and then while the order waits for space. The chests are boxes flagged
    // delivery: never
    // sorted into, read, taken from, indexed or searched, saved with the stash.

    private static final String CHEST_ID = "minecraft:chest";
    private static final long EXPAND_RETRY_MS = 60_000L, EXPAND_EVAL_MS = 5_000L;
    // Chests are being gathered or crafted for the storage, and are carried until they are put up
    private boolean chestSession, chestHolding;
    // The Deliver job is a look at a full chest (Recheck), not a delivery; the chest the last shulker went into
    private boolean recheckOnly;
    private Box lastDeliveryBox;
    private ChestStack.Plan chestPlanNow;
    // Why another chest could not be put up the last time (no chests to be had, placing failed), and when; the last
    // evaluation
    private String expandFailure, expandEval;
    private long expandFailureAt, expandEvalAt;
    private final StashWatch deliveryWatch = new StashWatch(true);

    private final ChestPlacer placer = new ChestPlacer(new ChestPlacer.Owner() {
        @Override
        public Set<Integer> keep() {
            return parseSlots(keepSlots.get());
        }

        @Override
        public boolean rotate() {
            return rotate.get();
        }

        @Override
        public int delay() {
            return delay.get();
        }

        @Override
        public boolean walk() {
            return walkOn();
        }

        @Override
        public void walkTo(BlockPos stand) {
            if (!BARITONE) return;
            walkNear(stand, 0);
            // The stand may be outside the stash area: that is not a reason to refuse the way
            walkInside = false;
        }

        @Override
        public void releaseGoal() {
            StashSorter.this.releaseGoal();
        }

        @Override
        public boolean moving() {
            return BARITONE && BaritoneHelper.isMoving();
        }

        @Override
        public boolean leftArea() {
            return StashSorter.this.leftArea();
        }

        @Override
        public boolean running() {
            return gate == Gate.Running && orderRunner.running() && !orderRunner.calledOff();
        }

        @Override
        public void moved(int from, int to, ItemStack was) {
            // The tracking of the stack goes with it, or what is left of it would count as the player's own
            stashHeld.remove(from);
            orderHeld.remove(from);
            Box source = heldFrom.remove(from);
            stashHeld.put(to, was);
            if (orderWork()) orderHeld.put(to, was.copy());
            if (source != null) heldFrom.put(to, source);
        }

        @Override
        public void pause(int ticks) {
            wait = ticks;
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }
    });

    private static BlockPos bp(DeliveryBook.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static DeliveryBook.Pos dp(BlockPos p) {
        return new DeliveryBook.Pos(p.getX(), p.getY(), p.getZ());
    }

    private DeliveryBook book() {
        return new DeliveryBook(stash == null ? new ArrayList<>() : stash.deliveryStorage);
    }

    private static boolean autoExpand() {
        StashManager manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        return manager != null && manager.autoExpandDelivery();
    }

    private boolean plainChest(ItemStack s) {
        return s.is(net.minecraft.world.item.Items.CHEST) && !s.has(DataComponents.CONTAINER);
    }

    /** The chests carried to be put up. */
    private int carriedChests() {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (plainChest(s) && (stashHeld.containsKey(i) || orderHeld.containsKey(i))) n += s.getCount();
        }
        return n;
    }

    /** A chest put up for the delivery storage is turned into something else by hand: it leaves the storage. */
    private void forgetExtra(Box box) {
        if (!box.deliveryExtra || stash == null) return;
        DeliveryBook book = book();
        DeliveryBook.Entry e = book.find(dp(box.pos));
        if (e != null && !e.primary) book.remove(e);
        box.deliveryExtra = false;
        stash.markDirty();
    }

    /**
     * A box stops being a delivery box: every flag delivery set goes back (including noCompact), and a box that was never
     * read is removed instead of being kept as an ordinary container nobody has seen.
     */
    private void unflagDelivery(Box b) {
        b.delivery = false;
        b.deliveryExtra = false;
        b.excluded = b.wasExcluded;
        b.dropOff = b.wasDropOff;
        b.wasExcluded = false;
        b.wasDropOff = false;
        b.noCompact = false;
        if (b.neverRead()) {
            stash.boxes.remove(b);
            surveyRetry.succeed(b);
            failedSurvey.remove(b);
            WeSortAddon.LOG.info(
                    "[wesort-stash-sorter] removed the never-read container at {} that stopped being a delivery container.",
                    xyz(b.pos));
        }
        stash.markDirty();
    }

    /**
     * Keeps the stash's boxes for the delivery storage in step with the book: the delivery container (the book follows it; a
     * different one forgets the chests put up above the old one) and the chests put up above it, flagged delivery and excluded.
     */
    private void syncDelivery() {
        Stash s = stash;
        if (s == null || mc.level == null) return;
        s.ensureDelivery();
        Box primary = null;
        for (Box b : s.boxes) if (b.delivery && !b.deliveryExtra) primary = b;
        DeliveryBook book = book();
        if (primary == null) {
            for (Box b : new ArrayList<>(s.boxes)) if (b.deliveryExtra) unflagDelivery(b);
            if (!s.deliveryStorage.isEmpty()) {
                book.syncPrimary(null);
                s.markDirty();
            }
            return;
        }
        int before = s.deliveryStorage.size();
        List<DeliveryBook.Pos> parts = new ArrayList<>();
        for (BlockPos p : primary.parts) parts.add(dp(p));
        book.syncPrimary(parts);
        if (book.dedupe() || s.deliveryStorage.size() != before) s.markDirty();

        Set<Box> mine = new HashSet<>();
        mine.add(primary);
        for (DeliveryBook.Entry e : book.sorted()) {
            if (e.primary) continue;
            BlockPos at = bp(e.first());
            Box eb = s.boxAt(at);
            // A chest that is gone (broken by a player) leaves the storage
            if (mc.level.isLoaded(at) && !(mc.level.getBlockEntity(at) instanceof ChestBlockEntity)) {
                book.remove(e);
                if (eb != null) s.boxes.remove(eb);
                s.markDirty();
                continue;
            }
            if (eb == null) {
                eb = mc.level.isLoaded(at) ? makeBox(at) : new Box(at, new ArrayList<>(List.of(at)));
                s.boxes.add(eb);
                s.markDirty();
            }
            // A single chest that became a double (or half of one broken): the halves are what the world says
            if (mc.level.isLoaded(at)) {
                Box fresh = makeBox(at);
                if (fresh.parts.size() != eb.parts.size() || !fresh.parts.containsAll(eb.parts)) {
                    eb.parts.clear();
                    eb.parts.addAll(fresh.parts);
                    eb.contents = null;
                    List<DeliveryBook.Pos> p2 = new ArrayList<>();
                    for (BlockPos p : fresh.parts) p2.add(dp(p));
                    e.at = new DeliveryBook(new ArrayList<>()).add(p2).at;
                    s.markDirty();
                }
            }
            if (!eb.delivery || !eb.deliveryExtra || !eb.excluded || eb.dropOff) {
                // What the user had set on it comes back when it leaves the storage
                if (!eb.delivery) {
                    eb.wasExcluded = eb.excluded;
                    eb.wasDropOff = eb.dropOff;
                }
                eb.delivery = true;
                eb.deliveryExtra = true;
                eb.excluded = true;
                eb.dropOff = false;
                eb.noCompact = true;
                s.markDirty();
            }
            mine.add(eb);
        }
        int dropped = 0;
        for (Box b : new ArrayList<>(s.boxes)) {
            if (!b.deliveryExtra || mine.contains(b)) continue;
            // The same chest as one of the storage's (a duplicate box): gone; any other is an ordinary container again
            boolean twin = false;
            for (Box m : mine) for (BlockPos q : b.parts) if (m.parts.contains(q)) twin = true;
            if (twin) {
                s.boxes.remove(b);
                s.markDirty();
                continue;
            }
            dropped++;
            unflagDelivery(b);
        }
        if (dropped > 0)
            alert(
                    "%d extra delivery chest%s now ordinary stash containers; their contents will be sorted.",
                    dropped, dropped == 1 ? " is" : "s are");
    }

    /** A different delivery container, or none: the chests put up above the old one are an ordinary stash again. */
    private void dropDeliveryExtras() {
        if (stash == null) return;
        int n = 0;
        for (Box b : new ArrayList<>(stash.boxes))
            if (b.deliveryExtra) {
                n++;
                unflagDelivery(b);
            }
        if (n > 0)
            alert(
                    "%d extra delivery chest%s now ordinary stash containers; their contents will be sorted.",
                    n, n == 1 ? " is" : "s are");
        stash.deliveryStorage.clear();
        stash.markDirty();
    }

    private final ChestStack.View stackView = new ChestStack.View() {
        @Override
        public boolean loaded(int x, int z) {
            return mc.level.isLoaded(new BlockPos(x, 0, z));
        }

        @Override
        public boolean air(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            BlockPos feet = mc.player.blockPosition();
            boolean body = feet.getX() == x && feet.getZ() == z && (feet.getY() == y || feet.getY() + 1 == y);
            return mc.level.getBlockState(p).isAir() && !body;
        }

        @Override
        public boolean conductor(int x, int y, int z) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState st = mc.level.getBlockState(p);
            return !st.isAir() && st.isRedstoneConductor(mc.level, p);
        }

        @Override
        public String name(int x, int y, int z) {
            return BuiltInRegistries.BLOCK
                    .getKey(mc.level.getBlockState(new BlockPos(x, y, z)).getBlock())
                    .getPath();
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return placeView().standable(x, y, z);
        }

        @Override
        public int minY() {
            return mc.level.getMinSectionY() << 4;
        }

        @Override
        public int maxY() {
            return (mc.level.getMaxSectionY() + 1) << 4;
        }
    };

    /** Where the next row goes, or why it can't. */
    private ChestStack.Result stackPlan() {
        List<DeliveryBook.Entry> row = book().topRow();
        if (row.isEmpty()) return new ChestStack.Result(null, "no delivery container is set");
        DeliveryBook.Entry top = row.get(0);
        List<DeliveryBook.Pos> parts = top.parts();
        BlockPos p0 = bp(parts.get(0));
        if (!mc.level.isLoaded(p0)) return new ChestStack.Result(null, "the delivery chest isn't loaded");
        BlockState st = mc.level.getBlockState(p0);
        int fx = 0, fz = 0;
        if (st.hasProperty(ChestBlock.FACING)) {
            Direction d = st.getValue(ChestBlock.FACING);
            fx = d.getStepX();
            fz = d.getStepZ();
        }
        return ChestStack.next(
                parts,
                fx,
                fz,
                !(st.getBlock() instanceof BarrelBlock),
                book().sorted().get(0).y(),
                dp(mc.player.blockPosition()),
                stackView);
    }

    private String expandBlockerNow() {
        long now = System.currentTimeMillis();
        String failed = expandFailure;
        if (failed != null && now - expandFailureAt < EXPAND_RETRY_MS) return failed;
        if (now - expandEvalAt < EXPAND_EVAL_MS) return expandEval;
        expandEval = computeExpandBlocker();
        expandEvalAt = now;
        return expandEval;
    }

    private String computeExpandBlocker() {
        ChestStack.Result res = stackPlan();
        if (res.plan() == null) return res.blocked();
        int need = res.plan().second() != null ? 2 : 1;
        int have = carriedChests();
        if (have >= need) return null;
        Map<String, Long> pool = orderRunner.craftPool();
        if (pool.getOrDefault(CHEST_ID, 0L) >= need - have) return null;
        if (!craftingOn()) return "no chests left in the stash (crafting is off)";
        String blocker = craftBlocker();
        if (blocker != null) return "no chests left in the stash, and " + blocker;
        com.porter.wesort.craft.CraftPlanner.Result plan = crafter.preview(CHEST_ID, need - have, 0, pool, false);
        return plan.ok() ? null : "no chests left in the stash, and " + plan.refusal();
    }

    /** What the delivery storage can take now, as the order runner asks. */
    private DeliveryRoom deliveryRoomNow() {
        if (stash == null || mc.level == null) return DeliveryRoom.ROOM;
        syncDelivery();
        DeliveryBook book = book();
        if (book.chests() == 0) return DeliveryRoom.ROOM;
        // Only worked out when every chest is full (the supply check reads the whole stash)
        String blocker = autoExpand() && book.firstUsable() == null ? expandBlockerNow() : null;
        return book.room(autoExpand(), blocker, System.currentTimeMillis(), DeliveryBook.RECHECK_MS);
    }

    /** The chest the next shulker goes into: the lowest with room, or (verify) the one the last went into. */
    private Box deliveryChestFor(boolean verify) {
        if (stash == null) return null;
        if (verify && lastDeliveryBox != null && lastDeliveryBox.delivery && stash.boxes.contains(lastDeliveryBox))
            return lastDeliveryBox;
        DeliveryBook book = book();
        for (DeliveryBook.Entry e : book.sorted()) {
            if (e.full) continue;
            Box b = stash.boxAt(bp(e.first()));
            if (b == null || !b.delivery) continue;
            // A chest put up above the container that can't be reached is passed over like a full one
            if (b.deliveryExtra && b.unreachable) {
                book.markFull(e, System.currentTimeMillis());
                continue;
            }
            return b;
        }
        return null;
    }

    private void deliveryChestFull(Box box) {
        if (box == null || stash == null) return;
        DeliveryBook book = book();
        DeliveryBook.Entry e = book.find(dp(box.pos));
        if (e == null) return;
        book.markFull(e, System.currentTimeMillis());
        stash.markDirty();
    }

    /** The heartbeat's deliveryStorage (null when no delivery container is set): chests, full ones, whether another can go up. */
    StashIndex.DeliveryStorage deliveryStorage() {
        try {
            Stash s = stash;
            if (s == null || mc.level == null || mc.player == null || s.deliveryPos == null) return null;
            DeliveryBook book = new DeliveryBook(s.deliveryStorage);
            int chests = book.chests(), full = book.fullCount();
            boolean auto = autoExpand();
            String blocked = null;
            if (chests > 0) {
                if (!auto) blocked = full >= chests ? "auto expand is off" : null;
                else if (expandFailure != null && System.currentTimeMillis() - expandFailureAt < EXPAND_RETRY_MS)
                    blocked = expandFailure;
                else if (book.firstUsable() == null) blocked = storageBlocker();
            }
            return new StashIndex.DeliveryStorage(auto, chests, full, auto && blocked == null && chests > 0, blocked);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String storageCached;
    private long storageAt;

    /** The geometry blocker, only worked out when every chest is full and cached for a few seconds (asked for every tick). */
    private String storageBlocker() {
        long now = System.currentTimeMillis();
        if (now - storageAt < EXPAND_EVAL_MS) return storageCached;
        storageAt = now;
        try {
            ChestStack.Result res = stackPlan();
            storageCached = res.plan() == null ? res.blocked() : expandEval;
        } catch (RuntimeException e) {
            storageCached = null;
        }
        return storageCached;
    }

    /** "3 chests, 1 full, can expand" or "... blocked: why". */
    String deliveryStorageLine() {
        StashIndex.DeliveryStorage d = deliveryStorage();
        if (d == null) return "no delivery container set";
        String tail =
                d.blocked() != null ? "blocked: " + d.blocked() : d.autoExpand() ? "can expand" : "auto expand is off";
        return d.chests() + (d.chests() == 1 ? " chest, " : " chests, ") + d.full() + " full, " + tail;
    }

    /** Another player opened a delivery chest and closed it again (StashWatch): flagged for re-read. */
    private void deliveryTouched(Box box) {
        if (stash == null) return;
        if (book().lidClosedByOther(dp(box.pos))) {
            info("Another player used the delivery chest at %s; flagged for re-read.", box.pos.toShortString());
            stash.markDirty();
        }
    }

    // Looking at a full chest again

    private boolean startRecheck(Box box) {
        if (box == null || !box.delivery) return false;
        recheckOnly = true;
        step("order: looking at a delivery chest that may have been emptied.");
        boolean started = start(Job.Deliver, box);
        if (!started) {
            recheckOnly = false;
            // Not tried again every tick: it counts as looked at now, and the next look is due later
            deliveryChestFull(box);
        }
        return started;
    }

    /** The open delivery chest was read: it has room again, or it is still full. */
    private void recheckRead() {
        recheckOnly = false;
        if (target == null) return;
        DeliveryBook book = book();
        DeliveryBook.Entry e = book.find(dp(target.pos));
        if (e == null) return;
        boolean room = false;
        for (ItemStack s : target.contents) if (s.isEmpty()) room = true;
        if (room) book.markRoom(e, System.currentTimeMillis());
        else book.markFull(e, System.currentTimeMillis());
        stash.markDirty();
        info("Order: a delivery chest was looked at again: %s.", room ? "it has room" : "still full");
    }

    // Putting a double chest up

    /** Chests could not be had or put up: said, and remembered for a minute before it is tried again. */
    private void expandFailed(String why) {
        expandFailure = why;
        expandFailureAt = System.currentTimeMillis();
        expandEvalAt = 0;
        chestHolding = false;
        warning("Delivery storage: couldn't put another chest up (%s). The order waits for space.", why);
        step("delivery storage: couldn't put another chest up: " + why + "; the order waits for space.");
    }

    /** Starts putting another row of chests up (OrderRunner.Expand). @return true when a job was started */
    private boolean startExpand() {
        return startExpand(false);
    }

    /** @param again the crafting table it waited for is ready: not counted as another try */
    private boolean startExpand(boolean again) {
        if (stash == null || mc.level == null || crafter.active() || kitBuilder.active()) return false;
        syncDelivery();
        ChestStack.Result res = stackPlan();
        if (res.plan() == null) {
            expandFailed(res.blocked());
            return false;
        }
        ChestStack.Plan plan = res.plan();
        if (!again) orderRunner.expandStarted();
        // Chests already standing in the new row (a try cut short before) are taken as they are
        boolean firstUp = mc.level.getBlockState(bp(plan.first())).getBlock() instanceof ChestBlock;
        boolean secondUp = plan.second() != null
                && mc.level.getBlockState(bp(plan.second())).getBlock() instanceof ChestBlock;
        int toPlace = (firstUp ? 0 : 1) + (plan.second() == null || secondUp ? 0 : 1);
        if (toPlace == 0) {
            registerChests(plan);
            return false;
        }
        int have = carriedChests();
        chestHolding = true;
        if (have >= toPlace) return startChestPlace(plan);

        int need = toPlace - have;
        Map<String, Long> pool = orderRunner.craftPool();
        boolean inStock = pool.getOrDefault(CHEST_ID, 0L) >= need;
        if (!inStock && !craftingOn()) {
            expandFailed("no chests left in the stash (crafting is off)");
            return false;
        }
        String why = inStock ? null : craftBlocker();
        if (why == null && !inStock && tableMissing()) {
            // The craft itself must be possible before a table is put in the world for it
            var preview = crafter.preview(CHEST_ID, need, 0, pool, false);
            if (!preview.ok()) why = preview.refusal();
            else {
                switch (prepareTable(TableResume.Expand)) {
                    case GATHERING -> {
                        return runCraft(crafter.next());
                    }
                    case PLACING -> {
                        return true;
                    }
                    case FAILED -> why = tableWhy;
                    default -> {}
                }
            }
        }
        if (why == null)
            why = crafter.begin(new Crafter.Request(
                    CHEST_ID,
                    need,
                    "delivery storage",
                    false,
                    0,
                    orderRunner::craftPool,
                    orderRunner::lotKeys,
                    inStock));
        if (why != null) {
            expandFailed(inStock ? why : "no chests left in the stash, and " + why);
            return false;
        }
        chestSession = true;
        craftForOrder = false;
        info("Delivery storage: taking %d chest%s out of the stash to put up.", need, need == 1 ? "" : "s");
        boolean started = runCraft(crafter.next());
        if (!started && chestSession && job == null && !crafter.active()) {
            chestSession = false;
            chestHolding = false;
            expandFailed("taking the chests out of the stash didn't start");
        }
        return started;
    }

    private boolean startChestPlace(ChestStack.Plan plan) {
        chestPlanNow = plan;
        chestJobTicks = 0;
        boolean firstUp = mc.level.getBlockState(bp(plan.first())).getBlock() instanceof ChestBlock;
        placer.begin(plan, firstUp);
        job = Job.ChestPlace;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step("order: putting another chest up for the delivery storage"
                + (plan.second() != null ? " (a double chest)" : "") + "; next: carry on with the order.");
        return true;
    }

    private int chestJobTicks;
    private static final int CHEST_JOB_TICKS = 20 * 90;

    private void chestPlaceTick() {
        stepTicks++;
        lastOwnAct = ticks;
        // The whole job is bounded: whatever the placer is stuck on, it ends
        if (++chestJobTicks > CHEST_JOB_TICKS) placer.abort("it took too long");
        ChestPlacer.Outcome out = placer.tick();
        if (out != null) chestPlaceDone(out);
    }

    private void chestPlaceDone(ChestPlacer.Outcome out) {
        ChestStack.Plan plan = chestPlanNow;
        releaseGoal();
        job = null;
        target = null;
        wait = delay.get();
        chestHolding = false;
        chestPlanNow = null;
        if (out.placed() && plan != null) {
            if (out.partial())
                warning(
                        "Delivery storage: the second half of the chest couldn't be put up (%s), the first stands alone.",
                        out.failure());
            registerChests(plan);
        } else expandFailed(out.failure() == null ? "it couldn't be put up" : out.failure());
        version++;
        idle = false;
    }

    /** The chests of the row stand: the book and the stash know them, the delivery storage has more. */
    private void registerChests(ChestStack.Plan plan) {
        DeliveryBook book = book();
        addPlacedChest(book, plan.first());
        if (plan.second() != null) addPlacedChest(book, plan.second());
        expandFailure = null;
        expandEvalAt = 0;
        syncDelivery();
        stash.markDirty();
        stash.save();
        version++;
        info("Delivery storage: %d chests now.", book.chests());
    }

    private void addPlacedChest(DeliveryBook book, DeliveryBook.Pos at) {
        BlockPos p = bp(at);
        if (!(mc.level.getBlockState(p).getBlock() instanceof ChestBlock)) return;
        Box one = makeBox(p);
        List<DeliveryBook.Pos> parts = new ArrayList<>();
        for (BlockPos q : one.parts) parts.add(dp(q));
        // A double chest put up half by half is one chest: the second half merges into the entry of the first
        book.addOrMerge(parts);
    }

    // Finding an anvil to name at (Job.AnvilPlace). In order: the one picked by hand, a remembered one, a placed anvil
    // found in
    // the stash area, one taken from the stash and placed. Nothing works: the rename fails with AnvilFinder.NONE
    private static final String[] ANVIL_IDS = {"minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil"};
    private static final int ANVIL_PLACE_TICKS = 20 * 90, ANVIL_TRIES = 3;
    private boolean anvilSession, anvilPlaced, anvilWalking;
    private int anvilSlot = -1, anvilPlaceTries, anvilPlacedAt, anvilWalkStart, anvilRestarts;
    private BlockPos anvilSpot, anvilStand;
    private final Set<PlaceSearch.Pos> anvilSkip = new HashSet<>();

    /** No anvil is set or remembered, or the one that was is gone. */
    boolean anvilMissing() {
        if (stash == null || stash.anvilPos == null) return true;
        return mc.level != null && mc.level.isLoaded(stash.anvilPos) && !isAnvil(stash.anvilPos);
    }

    private static boolean isXpBottle(ItemStack s) {
        return !s.isEmpty() && s.is(Items.EXPERIENCE_BOTTLE);
    }

    /** A carried slot usable for an XP top-up: stash stock tracked for the leftover pass, not reserved. */
    private boolean xpBottleUsable(int slot) {
        if (mc.player == null || slot < 0 || slot >= 36) return false;
        if (slot == anvilSlot || kitBuilder.holds(slot) || crafter.holds(slot) || crafter.reserved(slot)) return false;
        if (parseSlots(keepSlots.get()).contains(slot)) return false;
        // Only stash stock: what the bot took from the stash, never the player's own bottles
        ItemStack stack = mc.player.getInventory().getItem(slot);
        ItemStack tracked = stashHeld.get(slot);
        if (tracked == null || !ItemStack.isSameItemSameComponents(tracked, stack)) return false;
        return isXpBottle(stack);
    }

    /** Experience bottles carried from the stash, for an XP top-up. */
    private int xpBottlesCarried() {
        if (mc.player == null) return 0;
        int n = 0;
        for (int s = 0; s < 36; s++)
            if (xpBottleUsable(s)) n += mc.player.getInventory().getItem(s).getCount();
        return n;
    }

    /** Biggest carried stack of stash bottles, or -1. */
    private int xpBottleSlot() {
        if (mc.player == null) return -1;
        int best = -1, bestCount = 0;
        for (int s = 0; s < 36; s++) {
            if (!xpBottleUsable(s)) continue;
            int count = mc.player.getInventory().getItem(s).getCount();
            if (count > bestCount) {
                best = s;
                bestCount = count;
            }
        }
        return best;
    }

    /** Loose experience bottles in the stash to top up XP with: biggest stack, or null. */
    private OrderRunner.XpBottles xpBottleSource() {
        if (stash == null || mc.player == null) return null;
        Box best = null;
        int bestSlot = -1, bestCount = 0;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.stale || box.unreachable) continue;
            if (blocked(box, Job.Fetch)) continue;
            if (isKitBox(box) || box.contents == null) continue;
            for (int i = 0; i < box.contents.length; i++) {
                ItemStack s = box.contents[i];
                if (!isXpBottle(s) || !orderRunner.mayTakeFrom(box, i)) continue;
                if (s.getCount() > bestCount) {
                    best = box;
                    bestSlot = i;
                    bestCount = s.getCount();
                }
            }
        }
        return best == null ? null : new OrderRunner.XpBottles(best, bestSlot);
    }

    /** Bottles of enchanting from the stash (or already carried) to buy the XP a rename costs. */
    private boolean xpTopUpAvailable() {
        return xpBottlesCarried() > 0 || xpBottleSource() != null;
    }

    // WeSort UUID naming of shulker boxes (idle work): a box without a valid WeSort-UUID name gets one,
    // so the registry can track boxes across chests. Internal bookkeeping only, never shown to users.
    // Empty shulkers stay unnamed on purpose: kit bases and the empty pool require no custom name.
    // A UUID shared by two boxes is renamed on one of them.
    private boolean uuidNaming;
    private final Map<Integer, ItemStack> uuidBatch = new LinkedHashMap<>();
    private boolean uuidBatchSealed, uuidBatchFunded, uuidBatchYield;
    private int uuidBatchRefills;
    private final Map<String, Integer> uuidCopies = new HashMap<>();
    private static final int UUID_MAX_XP_REFILLS = 3;
    private int xpTargetLevels = OrderRunner.XP_TARGET_LEVELS;
    /** UUIDs seen on more than one box: one copy keeps it, the rest are renamed. Recomputed on every stash scan. */
    private Set<String> uuidDupes = new HashSet<>();

    private long uuidScanAt;
    private long uuidAnvilAt = -1000000;
    /** Boxes (by content key) a UUID rename gave up on: tried again later, not every tick. */
    private final Map<String, Long> uuidCooldown = new HashMap<>();

    private static final long UUID_SCAN_EVERY = 20 * 10L;
    private static final long UUID_ANVIL_RETRY = 20 * 60 * 5L;
    private static final long UUID_RETRY_COOLDOWN = 20 * 60 * 10L;

    private static boolean isEmptyShulker(ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;
        for (ItemStack in : Kits.inside(stack)) if (in != null && !in.isEmpty()) return false;
        return true;
    }

    /** The WeSort UUID carried by a shulker name, or null. */
    private static String uuidOf(ItemStack stack) {
        var custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom == null) return null;
        java.util.UUID uuid = ShulkerUuid.parse(custom.getString());
        return uuid == null ? null : uuid.toString();
    }

    /** Whether the shulker needs a WeSort UUID name: non-empty without a valid one, or a duplicated one. */
    private boolean needsUuidName(ItemStack stack, Set<String> dupes) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem()) || isEmptyShulker(stack) || isKitShulker(stack))
            return false;
        var name = stack.get(DataComponents.CUSTOM_NAME);
        return ShulkerUuid.needsAutomaticName(name == null ? null : name.getString(), dupes);
    }

    private static String newUuidName() {
        return ShulkerUuid.label(java.util.UUID.randomUUID());
    }

    private void pruneUuidCooldown() {
        uuidCooldown.entrySet().removeIf(e -> e.getValue() <= ticks);
    }

    /** Whether idle UUID work may run now: no order in the way, nothing else going on, none waiting to be claimed. */
    private boolean awaitingRepackStorage() {
        return repackWork != null && repackWork.phase() == com.porter.wesort.inventory.PhysicalWork.Phase.STORE;
    }

    private boolean uuidIdleAllowed(boolean rereading) {
        if (awaitingRepackStorage()) return false;
        if (orderRunner.running() || orderKit() || kitBuilder.active() || crafter.active() || compactor.active())
            return false;
        if (rereading) return false;
        StashManager manager = Modules.get().get(StashManager.class);
        return manager == null || manager.pendingOrders() == 0;
    }

    private boolean uuidEligibleSlot(int slot) {
        ItemStack stack = mc.player.getInventory().getItem(slot);
        ItemStack tracked = stashHeld.get(slot);
        if (tracked == null
                || !ItemStack.matches(tracked, stack)
                || parseSlots(keepSlots.get()).contains(slot)
                || kitBuilder.holds(slot)
                || crafter.holds(slot)
                || crafter.reserved(slot)
                || slot == anvilSlot
                || stuckCarried.containsKey(slot)
                || !needsUuidName(stack, uuidDupes)) return false;
        String key = StashIndex.groupKey(stack);
        return key != null && uuidCooldown.getOrDefault(key, 0L) <= ticks;
    }

    private void uuidCooldownSlot(int slot) {
        if (mc.player == null || mc.level == null || slot < 0) return;
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (stack.isEmpty()) return;
        String key = StashIndex.groupKey(stack);
        if (key != null) uuidCooldown.put(key, ticks + UUID_RETRY_COOLDOWN);
    }

    private void clearUuidBatch() {
        uuidBatch.clear();
        uuidBatchSealed = false;
        uuidBatchFunded = false;
        uuidBatchRefills = 0;
    }

    private void finishUuidBatch() {
        clearUuidBatch();
        // Give normal deposit work a chance before collecting another batch.
        uuidBatchYield = true;
    }

    private void refreshUuidDuplicates() {
        Map<String, Integer> counts = new HashMap<>();
        for (Box box : stash.boxes) {
            if (!boxKnownReadable(box)) continue;
            for (ItemStack stack : box.contents) {
                if (stack.isEmpty() || !Utils.isShulker(stack.getItem()) || isEmptyShulker(stack)) continue;
                String uuid = uuidOf(stack);
                if (uuid != null) counts.merge(uuid, 1, Integer::sum);
            }
        }
        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!Utils.isShulker(stack.getItem())) continue;
            String uuid = uuidOf(stack);
            if (uuid != null) counts.merge(uuid, 1, Integer::sum);
        }
        uuidCopies.clear();
        uuidCopies.putAll(counts);
        uuidDupes = new HashSet<>();
        for (var entry : counts.entrySet()) if (entry.getValue() > 1) uuidDupes.add(entry.getKey());
    }

    private boolean uuidBatchCanAdd(ItemStack stack) {
        String uuid = uuidOf(stack);
        if (uuid == null) return true;
        long selected =
                uuidBatch.values().stream().filter(s -> uuid.equals(uuidOf(s))).count();
        return selected < uuidCopies.getOrDefault(uuid, 0) - 1L;
    }

    private void pruneDuplicateKeepers() {
        Map<String, Integer> selected = new HashMap<>();
        uuidBatch.entrySet().removeIf(entry -> {
            String uuid = uuidOf(entry.getValue());
            return uuid != null && selected.merge(uuid, 1, Integer::sum) >= uuidCopies.getOrDefault(uuid, 0);
        });
    }

    private int uuidRenameCost(ItemStack stack) {
        return com.porter.wesort.inventory.RenameBatchBudget.renameOnlyCost(
                stack.getOrDefault(DataComponents.REPAIR_COST, 0));
    }

    private List<Integer> uuidBatchCosts() {
        return uuidBatch.values().stream().map(this::uuidRenameCost).toList();
    }

    private int uuidBatchLevels() {
        return com.porter.wesort.inventory.RenameBatchBudget.totalLevels(uuidBatchCosts());
    }

    private boolean trimUuidBatchToXp() {
        int affordable = com.porter.wesort.inventory.RenameBatchBudget.affordablePrefix(
                uuidBatchCosts(), mc.player.experienceLevel);
        if (affordable == 0) return false;
        if (affordable < uuidBatch.size()) {
            List<Integer> slots = new ArrayList<>(uuidBatch.keySet());
            for (int i = affordable; i < slots.size(); i++) uuidBatch.remove(slots.get(i));
            info("XP budget covers %d shulkers; postponing the remaining boxes.", affordable);
        }
        uuidBatchFunded = true;
        return true;
    }

    private boolean uuidRenameCarried() {
        if (uuidBatchYield) {
            uuidBatchYield = false;
            return false;
        }
        if (mc.player == null || stash == null) return false;
        if (uuidBatch.isEmpty()
                && carriedSlots().stream()
                        .noneMatch(slot -> Utils.isShulker(
                                mc.player.getInventory().getItem(slot).getItem()))) return false;
        pruneUuidCooldown();
        refreshUuidDuplicates();
        uuidBatch
                .entrySet()
                .removeIf(entry -> !ItemStack.matches(
                                entry.getValue(), mc.player.getInventory().getItem(entry.getKey()))
                        || !uuidEligibleSlot(entry.getKey()));
        pruneDuplicateKeepers();
        if (uuidBatch.isEmpty() && uuidBatchSealed) {
            finishUuidBatch();
            return false;
        }
        if (!uuidBatchSealed) {
            for (int slot : carriedSlots()) {
                if (uuidBatch.size() >= com.porter.wesort.inventory.RenameBatchBudget.MAX_BOXES) break;
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (!uuidBatch.containsKey(slot) && uuidEligibleSlot(slot) && uuidBatchCanAdd(stack))
                    uuidBatch.put(slot, stack.copy());
            }
        }
        if (uuidBatch.isEmpty()) return false;
        int slot = uuidBatch.keySet().iterator().next();
        if (anvilMissing()) {
            if (ticks - uuidAnvilAt < UUID_ANVIL_RETRY) {
                finishUuidBatch();
                return false;
            }
            uuidAnvilAt = ticks;
            if (startFindAnvil()) return true;
            finishUuidBatch();
            return false;
        }
        if (anvilProblem() != null) {
            finishUuidBatch();
            return false;
        }
        if (!uuidBatchSealed) {
            // Reserve one carry slot for XP bottles when they may be needed.
            boolean needBottleSlot = !mc.player.hasInfiniteMaterials()
                    && mc.player.experienceLevel < uuidBatchLevels() + 39
                    && xpBottlesCarried() == 0;
            int reserve = needBottleSlot ? 1 : 0;
            // Keep a free hotbar slot for throwing bottles from the main inventory.
            int freeHotbar = 0;
            boolean bottleInHotbar = false;
            for (int i = 0; i < 9; i++) {
                if (parseSlots(keepSlots.get()).contains(i)) continue;
                if (mc.player.getInventory().getItem(i).isEmpty()) freeHotbar++;
                if (xpBottleUsable(i)) bottleInHotbar = true;
            }
            boolean preserveHotbar = !mc.player.hasInfiniteMaterials()
                    && !bottleInHotbar
                    && mc.player.experienceLevel < uuidBatchLevels() + 39;
            if (uuidBatch.size() < com.porter.wesort.inventory.RenameBatchBudget.MAX_BOXES
                    && freeCarryCount() > reserve
                    && (!preserveHotbar || freeCarrySlot() >= 9 || freeHotbar > 1)
                    && fetchUuidCandidate()) return true;
            uuidBatchSealed = true;
            info("Planned UUID batch: %d shulkers, %d XP levels.", uuidBatch.size(), uuidBatchLevels());
        }
        if (mc.player.hasInfiniteMaterials() || mc.player.experienceLevel >= uuidBatchLevels()) {
            uuidBatchFunded = true;
        } else if (!uuidBatchFunded && xpBottlesCarried() == 0) {
            OrderRunner.XpBottles xp = xpBottleSource();
            if (xp != null
                    && freeCarryCount() > 0
                    && uuidBatchRefills < UUID_MAX_XP_REFILLS
                    && fetchXp(xp.box(), xp.slot())) {
                uuidBatchRefills++;
                return true;
            }
            if (!trimUuidBatchToXp()) {
                uuidCooldownSlot(slot);
                finishUuidBatch();
                return false;
            }
        } else if (uuidBatchFunded && !trimUuidBatchToXp()) {
            // XP was spent elsewhere: do not silently refill in the middle of a batch.
            finishUuidBatch();
            return false;
        }
        if (startUuidName(slot)) return true;
        finishUuidBatch();
        return false;
    }

    private boolean uuidFetchStep() {
        if (freeCarrySlot() < 0 || ticks - uuidScanAt < UUID_SCAN_EVERY) return false;
        uuidScanAt = ticks;
        if (stash == null || mc.player == null || mc.level == null) return false;
        if (!mc.player.hasInfiniteMaterials() && mc.player.experienceLevel == 0 && !xpTopUpAvailable()) return false;
        pruneUuidCooldown();
        refreshUuidDuplicates();
        return fetchUuidCandidate();
    }

    /** Select another eligible, unreserved shulker from fresh stock. */
    private boolean fetchUuidCandidate() {
        Box bestBox = null;
        int bestSlot = -1;
        double bestDistance = Double.MAX_VALUE;
        for (Box box : stash.boxes) {
            if (!boxKnownReadable(box) || blocked(box, Job.Fetch)) continue;
            for (int i = 0; i < box.contents.length; i++) {
                ItemStack stack = box.contents[i];
                if (!orderRunner.mayTakeFrom(box, i) || !needsUuidName(stack, uuidDupes) || !uuidBatchCanAdd(stack))
                    continue;
                String key = StashIndex.groupKey(stack);
                if (key == null || uuidCooldown.getOrDefault(key, 0L) > ticks) continue;
                double d = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
                if (d < bestDistance) {
                    bestDistance = d;
                    bestBox = box;
                    bestSlot = i;
                }
            }
        }
        return bestBox != null && fetch(bestBox, bestSlot);
    }

    private boolean boxKnownReadable(Box box) {
        return box.sortable() && !box.stale && !box.unreachable && !isKitBox(box);
    }

    /** For the heartbeat: where the anvil in use came from: "set", "found", "placed" or "none". */
    String anvilSource() {
        Stash s = ensureStash();
        if (s == null || s.anvilPos == null) return "none";
        return s.anvilSource.isEmpty() ? "set" : s.anvilSource;
    }

    private static boolean anvilItem(ItemStack s) {
        return !s.isEmpty() && (s.is(Items.ANVIL) || s.is(Items.CHIPPED_ANVIL) || s.is(Items.DAMAGED_ANVIL));
    }

    /** The inventory slot of an anvil item gathered for placing, or -1. */
    private int anvilItemSlot() {
        var inv = mc.player.getInventory();
        if (anvilSlot >= 0 && anvilItem(inv.getItem(anvilSlot))) return anvilSlot;
        for (int slot : crafter.lastHeld())
            if (anvilItem(inv.getItem(slot)) && (stashHeld.containsKey(slot) || orderHeld.containsKey(slot)))
                return slot;
        return -1;
    }

    /** Placed anvils in the stash area: the perimeter if one is set, else the radius and height around us. Loaded chunks only. */
    private List<AnvilFinder.Pos> placedAnvils() {
        List<AnvilFinder.Pos> out = new ArrayList<>();
        for (BlockPos p : scanArea(st -> st.is(net.minecraft.tags.BlockTags.ANVIL), false))
            out.add(new AnvilFinder.Pos(p.getX(), p.getY(), p.getZ()));
        return out;
    }

    /**
     * Blocks in the stash area that pass the test: the perimeter if one is set, else the radius and height around us. Loaded chunks
     * only. {@code bounded}: at most TableFinder.SEARCH_SPAN blocks across (x and z), around us.
     */
    private List<BlockPos> scanArea(java.util.function.Predicate<BlockState> test, boolean bounded) {
        if (mc.level == null || mc.player == null) return List.of();
        int minX, maxX, minY, maxY, minZ, maxZ;
        if (stash != null && stash.hasArea()) {
            BlockPos lo = stash.areaMin(), hi = stash.areaMax();
            minX = lo.getX();
            maxX = hi.getX();
            minY = lo.getY();
            maxY = hi.getY();
            minZ = lo.getZ();
            maxZ = hi.getZ();
        } else {
            BlockPos at = mc.player.blockPosition();
            int r = radius.get(), h = height.get();
            minX = at.getX() - r;
            maxX = at.getX() + r;
            minY = at.getY() - h;
            maxY = at.getY() + h;
            minZ = at.getZ() - r;
            maxZ = at.getZ() + r;
        }
        if (bounded) {
            int[] xs = TableFinder.span(minX, maxX, mc.player.getBlockX()),
                    zs = TableFinder.span(minZ, maxZ, mc.player.getBlockZ());
            minX = xs[0];
            maxX = xs[1];
            minZ = zs[0];
            maxZ = zs[1];
        }
        return scanRegion(test, minX, maxX, minY, maxY, minZ, maxZ);
    }

    /**
     * 6b6t's login portal belongs to the current lobby, not the Manager's remote
     * warehouse or the sorter's user-configurable container-search radius.
     * Scan only nearby loaded chunks (and only the relevant Y sections).
     */
    private List<BlockPos> scanLocalPortalBlocks() {
        if (mc.level == null || mc.player == null) return List.of();
        var nearby = PortalEntry.nearbyWindow(mc.player.blockPosition());
        return scanRegion(
                state -> state.is(Blocks.NETHER_PORTAL),
                nearby.minX(),
                nearby.maxX(),
                nearby.minY(),
                nearby.maxY(),
                nearby.minZ(),
                nearby.maxZ());
    }

    /** Shared chunk-palette scanner. The caller supplies its own bounds. */
    private List<BlockPos> scanRegion(
            java.util.function.Predicate<BlockState> test, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<BlockPos> out = new ArrayList<>();
        if (mc.level == null) return out;
        int minSection = mc.level.getMinSectionY();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                var sections = mc.level.getChunk(cx, cz).getSections();
                for (int i = 0; i < sections.length; i++) {
                    int baseY = (minSection + i) * 16;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    var section = sections[i];
                    // Whole sections without such a block in their palette are skipped
                    if (section.hasOnlyAir() || !section.maybeHas(test)) continue;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (!test.test(section.getBlockState(x, y, z))) continue;
                                int wx = cx * 16 + x, wy = baseY + y, wz = cz * 16 + z;
                                if (wx < minX || wx > maxX || wy < minY || wy > maxY || wz < minZ || wz > maxZ)
                                    continue;
                                out.add(new BlockPos(wx, wy, wz));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** An anvil is needed: finds one placed, or gathers one from the stash to place. The outcome goes to the order runner. */
    private boolean startFindAnvil() {
        if (stash == null || mc.level == null) {
            orderRunner.anvilFound(false);
            return false;
        }
        // An anvil taken out earlier and not yet placed (the job was interrupted)
        if (anvilSlot >= 0 && !anvilItem(mc.player.getInventory().getItem(anvilSlot))) anvilSlot = -1;
        int held = anvilItemSlot();
        if (held >= 0 && startAnvilPlace(held)) return true;

        AnvilFinder.Memory memory = stash.anvilPos == null
                ? null
                : new AnvilFinder.Memory(
                        new AnvilFinder.Pos(stash.anvilPos.getX(), stash.anvilPos.getY(), stash.anvilPos.getZ()),
                        AnvilFinder.Source.of(stash.anvilSource.isEmpty() ? "set" : stash.anvilSource));
        AnvilFinder.Decision decision = AnvilFinder.decide(memory, new AnvilFinder.World() {
            @Override
            public boolean isAnvil(AnvilFinder.Pos p) {
                BlockPos at = new BlockPos(p.x(), p.y(), p.z());
                return !mc.level.isLoaded(at) || StashSorter.this.isAnvil(at);
            }

            @Override
            public AnvilFinder.Pos search() {
                AnvilFinder.Pos from =
                        new AnvilFinder.Pos(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
                return AnvilFinder.nearest(
                        placedAnvils(), from, p -> !stash.hasArea() || stash.inArea(new BlockPos(p.x(), p.y(), p.z())));
            }

            @Override
            public boolean stockHasAnvil() {
                Map<String, Long> pool = orderRunner.craftPool();
                for (String id : ANVIL_IDS) if (pool.getOrDefault(id, 0L) > 0) return true;
                return false;
            }

            @Override
            public boolean craftableAnvil() {
                StashManager manager = meteordevelopment.meteorclient.systems.modules.Modules.get()
                        .get(StashManager.class);
                return AnvilFinder.craftable(
                        Crafter.recipes(),
                        orderRunner.craftPool(),
                        manager == null ? Set.of() : manager.craftNeverUse());
            }
        });
        if (decision.forget()) {
            info("The anvil I knew is gone, looking for another.");
            stash.anvilPos = null;
            stash.anvilSource = "";
            stash.save();
        }
        switch (decision.step()) {
            case USE -> orderRunner.anvilFound(true);
            case FOUND -> {
                stash.anvilPos = new BlockPos(
                        decision.pos().x(), decision.pos().y(), decision.pos().z());
                stash.anvilSource = "found";
                stash.save();
                info("Found an anvil in the stash area, using it for naming from now on.");
                orderRunner.anvilFound(true);
            }
            case PLACE_FROM_STOCK -> {
                Map<String, Long> pool = orderRunner.craftPool();
                String id = null;
                for (String candidate : ANVIL_IDS)
                    if (id == null && pool.getOrDefault(candidate, 0L) > 0) id = candidate;
                String why = id == null
                        ? "none in the stash"
                        : crafter.begin(new Crafter.Request(
                                id, 1, "naming", false, 0, orderRunner::craftPool, orderRunner::lotKeys, true));
                if (why != null) {
                    warning("No anvil to place: %s.", why);
                    orderRunner.anvilFound(false);
                    return false;
                }
                craftForOrder = true;
                anvilSession = true;
                info("No anvil in the area, taking one out of the stash to place.");
                return runCraft(crafter.next());
            }
            case CRAFT -> {
                // A 3x3 craft needs a table: one is ensured first, then the anvil is crafted and placed.
                if (tableMissing()) {
                    // The craft itself must be possible before a table is put in the world for it
                    var preview = crafter.preview(AnvilFinder.ANVIL_ID, 1, 0, orderRunner.craftPool(), false);
                    if (!preview.ok()) {
                        warning("No anvil to craft: %s.", preview.refusal());
                        orderRunner.anvilFound(false);
                        return false;
                    }
                    switch (prepareTable(TableResume.Anvil)) {
                        case GATHERING -> {
                            return runCraft(crafter.next());
                        }
                        case PLACING -> {
                            return true;
                        }
                        case FAILED -> {
                            warning("No anvil to craft: %s.", tableWhy);
                            orderRunner.anvilFound(false);
                            return false;
                        }
                        default -> {}
                    }
                }
                return startAnvilCraft();
            }
            case FAIL -> {
                warning("%s.", decision.failure());
                orderRunner.anvilFound(false);
            }
        }
        return false;
    }

    /** Crafts an anvil from the stash's iron and places it: the session is driven like a gathered one. */
    private boolean startAnvilCraft() {
        String why = crafter.begin(new Crafter.Request(
                AnvilFinder.ANVIL_ID, 1, "naming", false, 0, orderRunner::craftPool, orderRunner::lotKeys, false));
        if (why != null) {
            warning("No anvil to craft: %s.", why);
            orderRunner.anvilFound(false);
            return false;
        }
        anvilSession = true;
        craftForOrder = true;
        info("No anvil in the area or the stash, crafting one from iron to place.");
        return runCraft(crafter.next());
    }

    private boolean startAnvilPlace(int slot) {
        return startPlaceItem(slot, false);
    }

    /** Places the anvil, or the crafting table (the same job and spot search), from this inventory slot. */
    private boolean startPlaceItem(int slot, boolean table) {
        placingTable = table;
        placeWhy = "";
        anvilSlot = slot;
        anvilSpot = anvilStand = null;
        anvilPlaced = anvilWalking = false;
        anvilPlaceTries = anvilRestarts = 0;
        anvilSkip.clear();
        job = Job.AnvilPlace;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step(
                table
                        ? "placing a crafting table to craft at; next: carry on."
                        : "order: placing an anvil to name the shulkers at; next: carry on with the order.");
        return true;
    }

    private void anvilPlaceTick() {
        stepTicks++;
        lastOwnAct = ticks;
        var inv = mc.player.getInventory();

        if (anvilPlaced) {
            if (placingTable ? isTable(anvilSpot) : isAnvil(anvilSpot)) anvilPlaceDone(true);
            else if (stepTicks - anvilPlacedAt > 40) anvilPlaceDone(false);
            return;
        }
        if (stepTicks > ANVIL_PLACE_TICKS
                || anvilSlot < 0
                || !(placingTable ? tableItem(inv.getItem(anvilSlot)) : anvilItem(inv.getItem(anvilSlot)))) {
            anvilPlaceDone(false);
            return;
        }

        if (anvilWalking) {
            Vec3 at = mc.player.position();
            if (at.distanceToSqr(Vec3.atBottomCenterOf(anvilStand)) <= 1.8 * 1.8) {
                releaseGoal();
                anvilWalking = false;
                wait = delay.get();
                return;
            }
            if (ourGoal == null) {
                if (++anvilRestarts > 3) anvilSpotFailed();
                else {
                    walkNear(anvilStand, 1);
                    anvilWalkStart = stepTicks;
                }
                return;
            }
            boolean stopped = stepTicks - anvilWalkStart > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
            if (stopped || stepTicks - anvilWalkStart > WANDER_TICKS) anvilSpotFailed();
            return;
        }

        if (anvilSpot == null) {
            PlaceSearch.Result found = PlaceSearch.search(pos(mc.player.blockPosition()), placeView(), anvilSkip);
            if (found.found() == null) {
                WeSortAddon.LOG.info(
                        "{}: no spot within {} blocks: {}",
                        placingTable ? "crafting table" : "anvil",
                        PlaceSearch.RADIUS,
                        found.why());
                placeWhy = "no room to place it (" + found.why() + ")";
                anvilPlaceDone(false);
                return;
            }
            anvilSpot = bp(found.found().spot());
            anvilStand = bp(found.found().stand());
            if (found.found().steps() > 0) {
                if (!walkOn()) {
                    anvilPlaceDone(false);
                    return;
                }
                info(
                        "No spot for the %s beside me, walking %d steps to the nearest one.",
                        placingTable ? "crafting table" : "anvil", found.found().steps());
                walkNear(anvilStand, 1);
                anvilWalking = true;
                anvilWalkStart = stepTicks;
                anvilRestarts = 0;
                return;
            }
        }

        // It has to be in the hotbar to place it
        if (anvilSlot >= 9) {
            Set<Integer> keep = parseSlots(keepSlots.get());
            int hotbar = -1;
            for (int i = 0; i < 9 && hotbar == -1; i++)
                if (!keep.contains(i) && inv.getItem(i).isEmpty()) hotbar = i;
            if (hotbar == -1) {
                warning("No free hotbar slot to place the %s from.", placingTable ? "crafting table" : "anvil");
                placeWhy = "no free hotbar slot to place it from";
                anvilPlaceDone(false);
                return;
            }
            // The tracking of the stack goes with it, or what is left of it would count as the player's own
            int old = anvilSlot;
            ItemStack was = inv.getItem(old).copy();
            Box source = heldFrom.get(old);
            InvUtils.move().from(old).to(hotbar);
            anvilSlot = hotbar;
            stashHeld.remove(old);
            orderHeld.remove(old);
            heldFrom.remove(old);
            stashHeld.put(hotbar, was);
            if (orderWork()) orderHeld.put(hotbar, was.copy());
            if (source != null) heldFrom.put(hotbar, source);
            wait = delay.get();
            return;
        }
        if (!BlockUtils.canPlace(anvilSpot, true)) {
            anvilSpotFailed();
            return;
        }
        // W-061: this job places its anvil/table into this empty cell, nothing else.
        com.porter.wesort.compat.WorldInteractionGuard.authorizePlace(anvilSpot);
        com.porter.wesort.compat.InventorySync.expectPlacement(
                anvilSpot,
                BuiltInRegistries.ITEM.getKey(inv.getItem(anvilSlot).getItem()).toString());
        if (placeWithDurableRotation(
                anvilSpot, new FindItemResult(anvilSlot, inv.getItem(anvilSlot).getCount()))) {
            anvilPlaced = true;
            anvilPlacedAt = stepTicks;
            wait = delay.get();
        } else {
            com.porter.wesort.compat.InventorySync.clearPlacementExpectation();
            anvilSpotFailed();
        }
    }

    /** That spot did not work: not tried again, and another is looked for (a few times). */
    private void anvilSpotFailed() {
        releaseGoal();
        if (anvilSpot != null) anvilSkip.add(pos(anvilSpot));
        anvilSpot = anvilStand = null;
        anvilWalking = false;
        if (++anvilPlaceTries >= ANVIL_TRIES) anvilPlaceDone(false);
    }

    private void anvilPlaceDone(boolean ok) {
        releaseGoal();
        boolean table = placingTable;
        if (ok && table) {
            stash.craftingTablePos = anvilSpot.immutable();
            stash.craftingTableSource = tableCrafted ? "crafted" : "placed";
            stash.save();
            tableChanged();
            info(
                    tableCrafted
                            ? "Crafted a crafting table and placed it, remembered for next time."
                            : "Placed a crafting table to craft at, remembered for next time.");
        } else if (ok) {
            stash.anvilPos = anvilSpot.immutable();
            stash.anvilSource = "placed";
            stash.save();
            info("Placed an anvil to name shulkers at, remembered for next time.");
        } else warning(table ? "Couldn't place a crafting table." : "Couldn't place an anvil.");
        if (table && !ok) tableWhy = TableFinder.failure(placeWhy.isEmpty() ? "it couldn't be placed" : placeWhy);
        placingTable = false;
        // An anvil item that was not placed is carried like anything else and put back
        anvilSlot = -1;
        crafter.clearLastHeld();
        anvilSpot = anvilStand = null;
        anvilPlaced = anvilWalking = false;
        job = null;
        target = null;
        wait = delay.get();
        if (table) tableAcquired(ok);
        else orderRunner.anvilFound(ok);
        version++;
        idle = false;
    }

    private boolean startName(OrderRunner.Rename rename) {
        return startName(rename, false);
    }

    private boolean startName(OrderRunner.Rename rename, boolean idleUuid) {
        uuidNaming = idleUuid;
        xpTargetLevels = idleUuid ? uuidBatchLevels() : OrderRunner.XP_TARGET_LEVELS;
        String bad = anvilProblem();
        if (bad == null && !walkOn() && !inReachOf(rename.anvil())) bad = NOT_REACHED;
        if (bad != null) {
            if (idleUuid) {
                uuidCooldownSlot(rename.slot());
                uuidNaming = false;
                return false;
            }
            orderRunner.nameFailed(bad);
            return runOrder(orderRunner.next());
        }

        namePos = rename.anvil();
        nameText = rename.name();
        nameSlot = relocateSlot = rename.slot();
        nameExpected = mc.player.getInventory().getItem(nameSlot).copy();
        nameStashOwned = ItemStack.matches(nameExpected, stashHeld.getOrDefault(nameSlot, ItemStack.EMPTY));
        nameOrderOwned = orderHeld.containsKey(nameSlot);
        nameSent = namePlaced = false;
        nameSentAt = nameJobTicks = nameResultSeen = 0;
        nameTakes = nameCheckTicks = nameOpenTries = nameOpenClickAt = nameDrainTries = 0;
        nameSucceeded = false;
        nameFailure = null;
        if (!java.util.Objects.equals(nameStreakOrder, orderRunner.orderId())) {
            nameStreakOrder = orderRunner.orderId();
            nameClosedStreak = 0;
        }

        job = Job.Name;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        swapStep = 0;
        step(
                idleUuid
                        ? "naming a stash shulker its WeSort UUID."
                        : "order: naming a shulker at the anvil; next: deliver it.");

        // Short on XP: throw stash bottles of enchanting first, then walk to the anvil
        if (needsXpTopUp()) {
            xpPending = true;
            xpThrows = 0;
            xpLastThrow = -100000;
            xpSwapped = false;
            xpMoveTo = -1;
            xpThrowQueued = false;
            xpAttempt++;
            stepTicks = 0;
            step = Step.Walking;
            step(
                    idleUuid
                            ? "UUID batch: topping up to " + xpTargetLevels + " XP levels before naming."
                            : "order: topping up XP with bottles of enchanting; next: name it.");
            return true;
        }

        if (inReachOf(namePos)) step = Step.Opening;
        else {
            walkNear(namePos, 2);
            // The anvil may be outside the stash area: that is not a reason to refuse the way
            walkInside = false;
            step = Step.Walking;
        }
        return true;
    }

    /**
     * Names a carried shulker its WeSort UUID, idle work when there is nothing else to sort. The order
     * flow is untouched: order renames keep their package names. Reports nowhere; failures cool the box
     * down instead of failing anything.
     */
    private boolean startUuidName(int slot) {
        if (stash == null || stash.anvilPos == null) return false;
        if (!startName(new OrderRunner.Rename(stash.anvilPos, newUuidName(), slot), true)) return false;
        step("naming a shulker its WeSort UUID; " + uuidBatch.size() + " remaining in this batch.");
        return true;
    }

    private void nameTick() {
        // Bottles of enchanting first: XP is topped up standing still, then the walk to the anvil starts
        if (xpPending) {
            nameXp();
            return;
        }
        stepTicks++;
        lastOwnAct = ticks;
        // The whole job at the anvil (window open) is capped; the walk and the open have their own limits
        if (step == Step.Working) nameJobTicks++;
        if (nameJobTicks > NAME_JOB_TICKS + 400) {
            if (nameFailure == null) nameFailure = NAME_UNFINISHED;
            nameEnd();
            return;
        }
        if (nameJobTicks > NAME_JOB_TICKS && nameFailure == null) nameFailure = NAME_UNFINISHED;
        switch (step) {
            case Walking -> nameWalk();
            case Opening -> nameOpen();
            case Working -> nameWork();
        }
    }

    /**
     * Tops up XP by throwing stash bottles of enchanting at our feet, before walking to the anvil.
     * Only stash stock is ever thrown: the slot must be tracked as taken from the stash, never the
     * player's own bottles. Leftovers go back with the normal leftover pass.
     */
    private void nameXp() {
        stepTicks++;
        lastOwnAct = ticks;
        if (mc.player == null || mc.level == null) {
            nameFailure = TOO_FEW_LEVELS;
            nameEnd();
            return;
        }
        if (mc.player.experienceLevel >= xpTargetLevels) {
            if (uuidNaming) uuidBatchFunded = true;
            beginNameWalk();
            return;
        }
        if (stepTicks > XP_TIMEOUT_TICKS || xpThrows >= XP_MAX_THROWS) {
            if (stepTicks <= XP_TIMEOUT_TICKS && ticks - xpLastThrow < 100) return;
            finishXpBudget();
            return;
        }
        // The anvil window isn't open yet: anything open is closed first, bottles need the inventory
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            if (!mc.player.containerMenu.getCarried().isEmpty()) recoverCursor();
            else closeOurWindow(mc.player.containerMenu);
            return;
        }
        int bottle = xpBottleSlot();
        if (bottle < 0) {
            // The last bottle's XP orbs may still be in flight.
            if (xpThrowQueued || ticks - xpLastThrow < 100) return;
            finishXpBudget();
            return;
        }
        if (xpThrowQueued || ticks - xpLastThrow < XP_THROW_GAP) return;
        throwXpBottle(bottle);
    }

    private void finishXpBudget() {
        if (uuidNaming) {
            // If the first supply ran out, fetch more before any rename. Keep the
            // planned batch and bound the number of refill trips.
            if (xpBottleSlot() < 0
                    && freeCarryCount() > 0
                    && uuidBatchRefills < UUID_MAX_XP_REFILLS
                    && xpBottleSource() != null) {
                xpPending = false;
                xpThrowQueued = false;
                xpAttempt++;
                if (xpSwapped) InvUtils.swapBack();
                xpSwapped = false;
                xpMoveTo = -1;
                uuidNaming = false;
                job = null;
                target = null;
                wait = delay.get();
                return;
            }
            if (trimUuidBatchToXp()) beginNameWalk();
            else {
                nameFailure = TOO_FEW_LEVELS;
                nameEnd();
            }
            return;
        }
        // Three levels are a buffer; the anvil's actual cost is checked before taking the result.
        if (mc.player.experienceLevel >= OrderRunner.XP_MIN_LEVELS) beginNameWalk();
        else {
            nameFailure = TOO_FEW_LEVELS;
            nameEnd();
        }
    }

    /** The XP is topped up: restore the hotbar and start the walk to the anvil. */
    private void beginNameWalk() {
        xpPending = false;
        xpThrowQueued = false;
        xpAttempt++;
        if (xpSwapped) {
            InvUtils.swapBack();
            xpSwapped = false;
        }
        xpMoveTo = -1;
        stepTicks = 0;
        if (inReachOf(namePos)) step = Step.Opening;
        else {
            walkNear(namePos, 2);
            // The anvil may be outside the stash area: that is not a reason to refuse the way
            walkInside = false;
            step = Step.Walking;
        }
    }

    /** Throws one bottle of enchanting at our feet: into the hand, aimed down, used, hand restored later. */
    private void throwXpBottle(int bottle) {
        // A move sent but not confirmed yet: wait for it instead of stacking moves
        if (xpMoveTo >= 0) {
            if (isXpBottle(mc.player.getInventory().getItem(xpMoveTo))) xpMoveTo = -1;
            else return;
        }
        int hand = bottle;
        if (hand < 0 || hand > 8) {
            Set<Integer> keep = parseSlots(keepSlots.get());
            int free = -1;
            for (int i = 0; i < 9; i++) {
                if (keep.contains(i)) continue;
                if (mc.player.getInventory().getItem(i).isEmpty()) {
                    free = i;
                    break;
                }
            }
            if (free < 0) return; // no room in the hotbar right now; retried until the timeout
            Box from = heldFrom.get(bottle);
            ItemStack moving = mc.player.getInventory().getItem(bottle).copy();
            InvUtils.move().from(bottle).to(free);
            // Tracked for the leftover pass like any other order take
            noteOrderHeld(free, moving);
            if (from != null) heldFrom.put(free, from);
            stashHeld.put(free, moving.copy());
            yielder.inventoryChangedByUs();
            xpMoveTo = free;
            wait = Math.max(wait, delay.get());
            return;
        }
        if (!InvUtils.swap(hand, !xpSwapped)) return;
        xpSwapped = true;
        int selected = hand;
        long attempt = ++xpAttempt;
        xpThrowQueued = true;
        Runnable toss = InventorySync.scopedAutomation(() -> {
            if (attempt != xpAttempt) return;
            xpThrowQueued = false;
            if (gate != Gate.Running
                    || job != Job.Name
                    || !xpPending
                    || mc.player == null
                    || !InventorySync.ready()
                    || !sortingPermitted()
                    || mc.player.containerMenu != mc.player.inventoryMenu
                    || mc.player.getInventory().getSelectedSlot() != selected
                    || !xpBottleUsable(selected)
                    || !isXpBottle(mc.player.getMainHandItem())) return;
            xpThrows++;
            xpLastThrow = ticks;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        });
        // Bottles must hit the ground close enough to collect the XP, even with rotate disabled.
        if (rotate.get()) Rotations.rotate(mc.player.getYRot(), 85.0f, toss);
        else {
            float pitch = mc.player.getXRot();
            mc.player.setXRot(85.0f);
            try {
                toss.run();
            } finally {
                mc.player.setXRot(pitch);
            }
        }
        wait = Math.max(wait, delay.get());
    }

    private void nameWalk() {
        if (mc.level.isLoaded(namePos) && !isAnvil(namePos)) {
            nameFailure = ANVIL_GONE;
            nameEnd();
            return;
        }
        if (inReachOf(namePos)) {
            releaseGoal();
            step = Step.Opening;
            stepTicks = 0;
            nameOpenTries = 0;
            return;
        }
        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (stopped || stepTicks > WALK_TIMEOUT) {
            nameFailure = NOT_REACHED;
            nameEnd();
        }
    }

    private void nameOpen() {
        if (!isAnvil(namePos)) {
            nameFailure = ANVIL_GONE;
            nameEnd();
            return;
        }
        AbstractContainerMenu open = mc.player.containerMenu;
        if (open != mc.player.inventoryMenu) {
            if (openPos != null && open != openMenuBefore && open instanceof net.minecraft.world.inventory.AnvilMenu) {
                ownedMenu = open;
                step = Step.Working;
                stepTicks = 0;
                wait = firstClickWait();
                return;
            }
            // Something else opened (one left over, a plugin window): closed, and the click made again
            if (!open.getCarried().isEmpty()) recoverCursor();
            mc.player.closeContainer();
            lastCloseTick = ticks;
        }

        if (openHeld()) {
            stepTicks--;
            return;
        }

        if (stepTicks > NAME_OPEN_TICKS
                || (nameOpenTries >= NAME_OPEN_TRIES && stepTicks - nameOpenClickAt > NAME_OPEN_RETRY)) {
            nameFailure = NOT_OPENED;
            nameEnd();
            return;
        }

        if (nameOpenTries < NAME_OPEN_TRIES && (nameOpenTries == 0 || stepTicks - nameOpenClickAt >= NAME_OPEN_RETRY)) {
            nameOpenTries++;
            nameOpenClickAt = stepTicks;
            BlockPos pos = namePos;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> {
                if (gate != Gate.Running || job != Job.Name) return;
                openPos = pos;
                openClickTick = ticks;
                openMenuBefore = mc.player.containerMenu;
                // W-061: the exact container this job opens, nothing else. TTL-bound (rotation
                // callbacks run ticks later), exact position + block + job, revoked by expiry.
                com.porter.wesort.compat.WorldInteractionGuard.authorizeUse(pos);
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    /** One click in the open anvil window. */
    private void nameClick(int slotId) {
        InvUtils.click().slotId(slotId);
        yielder.inventoryChangedByUs();
        wait = Math.max(delay.get(), 4);
    }

    /**
     * Works out what to do from what the window shows, so it can be picked up at any point: the shulker goes in, the
     * rename is sent, the result is checked and taken into the same slot the shulker came from, and the slot is checked
     * for the name. When it can't be named the shulker is taken back out first.
     */
    private void nameWork() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof net.minecraft.world.inventory.AnvilMenu anvil) || menu != ownedMenu) {
            nameEnd();
            return;
        }

        ItemStack cursor = menu.getCarried();
        ItemStack input = anvil.getSlot(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT)
                .getItem();
        ItemStack result = anvil.getSlot(net.minecraft.world.inventory.AnvilMenu.RESULT_SLOT)
                .getItem();
        int home = inventoryToMenu(nameSlot, 3);

        // Something was picked up: it goes back into the slot the shulker came from
        if (!cursor.isEmpty()) {
            int free = freeCarrySlot();
            if (menu.getSlot(home).getItem().isEmpty()) nameClick(home);
            else if (free != -1) nameClick(inventoryToMenu(free, 3));
            else if (!recoverBounded()) nameEnd();
            return;
        }

        if (nameFailure != null) {
            if (!input.isEmpty()) nameClick(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT);
            else nameEnd();
            return;
        }

        if (input.isEmpty()) {
            if (namePlaced) {
                // Taken out of the anvil: the slot it came from must now carry the name
                if (nameTakes > 0 && !result.isEmpty()) return;
                ItemStack renamed = mc.player.getInventory().getItem(nameSlot);
                if (exactNamedResult(renamed)) {
                    if (nameStashOwned) stashHeld.put(nameSlot, renamed.copy());
                    if (nameOrderOwned) orderHeld.put(nameSlot, renamed.copy());
                    syncHeld();
                    if (stash != null) stash.save();
                    nameSucceeded = true;
                    nameEnd();
                    return;
                }
                if (++nameCheckTicks > NAME_CHECK_TICKS) nameFailure = NOT_TAKEN;
                return;
            }
            ItemStack source = mc.player.getInventory().getItem(nameSlot);
            if (!ItemStack.matches(source, nameExpected)
                    || (uuidNaming && (!nameStashOwned || !needsUuidName(source, uuidDupes)))
                    || (!uuidNaming && !orderRunner.claims(source))) {
                nameFailure = CLOSED;
                return;
            }
            InvUtils.move().fromId(home).toId(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT);
            yielder.inventoryChangedByUs();
            namePlaced = true;
            wait = delay.get();
            return;
        }

        if (!ItemStack.matches(input, nameExpected)) {
            nameFailure = CLOSED;
            return;
        }

        if (!nameSent) {
            // What the anvil screen does when the name box changes
            if (anvil.setItemName(nameText))
                mc.player.connection.send(
                        new net.minecraft.network.protocol.game.ServerboundRenameItemPacket(nameText));
            nameSent = true;
            nameSentAt = stepTicks;
            wait = delay.get();
            return;
        }

        boolean named = exactNamedResult(result);
        if (!named) {
            nameResultSeen = 0;
            // After a take that did not go through, the result is gone with it: that is the refused take
            if (nameTakes > 0) nameFailure = NOT_TAKEN;
            else if (stepTicks - nameSentAt > NAME_RESULT_TICKS) nameFailure = NO_RESULT;
            return;
        }
        // The server's answer, not only our own guess, is what is taken: the result stays a moment first
        nameResultSeen++;
        int cost = anvil.getCost();
        boolean free = mc.player.hasInfiniteMaterials();
        if (nameResultSeen < NAME_SETTLE_TICKS || (cost <= 0 && !free && nameResultSeen <= NAME_COST_WAIT_TICKS))
            return;

        // A rename costs at least one level
        int need = Math.max(1, cost);
        if (!free && need >= NAME_TOO_DEAR) {
            nameFailure = TOO_DEAR;
            return;
        }
        if (!free && mc.player.experienceLevel < need) {
            nameFailure = TOO_FEW_LEVELS;
            return;
        }
        // A take the server keeps refusing is given up on after a few clicks, not repeated
        if (nameTakes >= NAME_MAX_TAKES) {
            nameFailure = NOT_TAKEN;
            return;
        }
        nameTakes++;
        nameClick(net.minecraft.world.inventory.AnvilMenu.RESULT_SLOT);
        wait = Math.max(wait, NAME_SETTLE_TICKS * 2);
    }

    private boolean exactNamedResult(ItemStack result) {
        var custom = result.get(DataComponents.CUSTOM_NAME);
        if (result.isEmpty() || custom == null || !custom.getString().equals(nameText)) return false;
        ItemStack actual = result.copy();
        ItemStack original = nameExpected.copy();
        actual.remove(DataComponents.CUSTOM_NAME);
        original.remove(DataComponents.CUSTOM_NAME);
        return ItemStack.matches(actual, original);
    }

    private void rememberAnvil(String why) {
        anvilFailure = why;
        anvilFailedOrder = orderRunner.orderId();
        anvilFailedFor = namePos;
    }

    /** An idle UUID naming failed: cool the box down instead of retrying it at once. */
    private void uuidRenameFailed() {
        if (mc.player != null && mc.level != null && nameSlot >= 0) {
            ItemStack s = mc.player.getInventory().getItem(nameSlot);
            String key = s.isEmpty() ? null : StashIndex.groupKey(s);
            if (key != null) uuidCooldown.put(key, ticks + UUID_RETRY_COOLDOWN);
        }
        info("Naming a shulker its WeSort UUID failed (%s), trying again later.", nameFailure);
    }

    /**
     * The window is never closed on the shulker: one still in the anvil or on the cursor goes back into the slot it came
     * from (kept free for it) or another free carry slot first, for a few tries.
     * @return true when a click was made and the window stays open
     */
    private boolean nameDrain(AbstractContainerMenu menu) {
        if (!(menu instanceof net.minecraft.world.inventory.AnvilMenu anvil)
                || menu != ownedMenu
                || ++nameDrainTries > 20) return false;
        int home = inventoryToMenu(nameSlot, 3);
        if (!menu.getCarried().isEmpty()) {
            int free = freeCarrySlot();
            if (menu.getSlot(home).getItem().isEmpty()) nameClick(home);
            else if (free != -1) nameClick(inventoryToMenu(free, 3));
            else return false;
            return true;
        }
        if (!anvil.getSlot(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT)
                .getItem()
                .isEmpty()) {
            nameClick(net.minecraft.world.inventory.AnvilMenu.INPUT_SLOT);
            return true;
        }
        return false;
    }

    private void nameEnd() {
        if (ourGoal != null) releaseGoal();
        if (xpSwapped) {
            InvUtils.swapBack();
            xpSwapped = false;
        }
        xpPending = false;
        xpThrowQueued = false;
        xpAttempt++;
        xpMoveTo = -1;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (nameDrain(menu)) return;
        if (menu != mc.player.inventoryMenu && ourMenu(menu)) {
            if (!menu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(menu);
        }
        ownedMenu = null;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;
        if (nameFailure != null) {
            WeSortAddon.LOG.info("[wesort-stash-sorter] naming given up: {}", nameFailure);
            // An anvil that can't be reached or opened, gives no result or doesn't give the shulker back is not tried
            // again
            // for the rest of this order
            if (nameFailure.equals(NOT_REACHED)
                    || nameFailure.equals(NOT_OPENED)
                    || nameFailure.equals(NO_RESULT)
                    || nameFailure.equals(NOT_TAKEN)) rememberAnvil(nameFailure);
            // A found or placed anvil that can't be reached or opened is forgotten, so the next naming searches again;
            // a hand-set one never
            if ((nameFailure.equals(NOT_REACHED) || nameFailure.equals(NOT_OPENED))
                    && stash.anvilPos != null
                    && namePos != null
                    && namePos.equals(stash.anvilPos)
                    && ("found".equals(stash.anvilSource) || "placed".equals(stash.anvilSource))) {
                stash.anvilPos = null;
                stash.anvilSource = "";
                stash.save();
            }
            if (uuidNaming) uuidRenameFailed();
            else orderRunner.nameFailed(nameFailure);
        } else if (uuidNaming) {
            info("Named a shulker its WeSort UUID (%s).", nameText);
        }
        // Closes that nobody asked for, twice in a row, count as a bad anvil too
        if (uuidNaming) {
            if (nameSucceeded) {
                uuidBatch.remove(nameSlot);
                if (uuidBatch.isEmpty()) finishUuidBatch();
            } else finishUuidBatch();
        }
        if (nameSucceeded) nameClosedStreak = 0;
        else if (nameFailure == null && ++nameClosedStreak >= 2) rememberAnvil(CLOSED);
        nameSucceeded = false;
        uuidNaming = false;
        nameFailure = null;
        job = null;
        target = null;
        wait = delay.get();
    }

    /**
     * Between jobs: the shulker that was at the anvil is back in the inventory but not in a carry slot (the window was
     * closed on it), so the order can find it again: it goes to the slot it came from, or any free carry slot.
     * @return true when a click was made
     */
    private boolean relocateNamed() {
        if (relocateSlot < 0) return false;
        var inv = mc.player.getInventory();
        if (!orderRunner.running() || !mc.player.containerMenu.getCarried().isEmpty() || mc.screen != null) {
            if (!orderRunner.running()) relocateSlot = -1;
            return false;
        }
        for (int s : carry) {
            if (orderRunner.claims(inv.getItem(s))) {
                relocateSlot = -1;
                return false;
            }
        }
        int dest = inv.getItem(relocateSlot).isEmpty() ? relocateSlot : freeCarrySlot();
        for (int s = 0; s < 36; s++) {
            if (carry.contains(s) || !orderRunner.claims(inv.getItem(s))) continue;
            // Held but nowhere to put it: tried again later
            if (dest == -1) return false;
            bundleClick(s, 0);
            bundleClick(dest, 0);
            relocateSlot = -1;
            wait = delay.get();
            return true;
        }
        relocateSlot = -1;
        return false;
    }

    /** Puts the order's finished shulker into the delivery container, stopping when it is full. */
    private boolean stagingPackage;

    private boolean deliverOne() {
        if (!mc.player.containerMenu.getCarried().isEmpty()) return recoverBounded();
        // A look at a full chest that may have been emptied, not a delivery
        if (recheckOnly) {
            recheckRead();
            return false;
        }

        // What the container held before, to see afterwards that it gained the shulker
        if (actions == 0) orderRunner.deliveryOpened(target.contents);

        for (int slot : carriedSlots()) {
            if (!orderRunner.claims(mc.player.getInventory().getItem(slot))) continue;
            if (!canFit(mc.player.getInventory().getItem(slot))) {
                deliveryChestFull(target);
                orderRunner.deliveryFull();
                return false;
            }
            stagingPackage = true;
            try {
                InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
            } finally {
                stagingPackage = false;
            }
            return true;
        }
        // Only a read after the settle wait, or of a freshly opened window, is the server's word and not our own
        // prediction
        if (settled || actions == 0) orderRunner.deliveryClosed(target, target.contents);
        return false;
    }

    // For the WeSort Manager module

    OrderRunner orders() {
        return orderRunner;
    }

    /** Why an order can't be taken now, or null if one can. */
    String orderBlocker() {
        if (!running()) return driveBlocked != null ? driveBlocked : "starting";
        if (!shulkersOk()) return "sorter can't use shulkers";
        if (carry.isEmpty()) return "no free inventory slot";
        if (ensureStash() == null) return "no stash";
        String away = notInStashWorld();
        if (away != null) return away;
        if (orderRunner.deliveryBox() == null) return "no delivery box";
        if (pendingPhysicalRecovery()) return "finishing automatically recovered physical work";
        if (kitBuilder.active() && !orderKit()) return "busy: building a kit";
        // The previous order cannot yield the queue while its carried items
        // still have to be returned to stash storage.
        leftoverSlots();
        return orderRunner.holdsOrder() || !orderHeld.isEmpty() ? "busy" : null;
    }

    /**
     * For wesort-stash-manager (the website's rescan button): every known container is queued, oldest first, and the area
     * is explored again for new ones. What is known stays in place, so the index keeps showing it until each is read.
     * Works with sort-items off, and with the sorter off: the reading starts when it is turned on.
     * @return how many containers will be read, or -1 when no stash is loaded
     */
    int rescan() {
        if (ensureStash() == null) return -1;
        int count = 0;
        surveyBatch.clear();
        for (Box box : stash.boxes) {
            if (box.excluded || box.foreign) continue;
            forcedSurvey.add(box);
            // Only readable containers are counted: unreachable and failed-survey ones
            // would inflate the "re-reading N" count without any subsequent reads.
            if (readableBox(box)) count++;
        }
        forgetExploration();
        // Refresh membership at the next safe job boundary, including radius-only stashes.
        // A running scan used to reread only its old list and miss restored/new containers.
        areaDirty = true;
        explorePending = true;
        stash.save();
        version++;
        idle = false;
        return count;
    }

    /**
     * Marks Manager-requested positions to be read again: the inversion of control where the
     * Manager, which owns the database, decides what to re-check. Unknown positions are ignored
     * silently; excluded, foreign, unreachable and failed-survey chests are left to
     * the normal jobs. Wakes the loop like {@link #rescan}, without forcing a full re-read.
     * @return how many chests were marked, or -1 when no stash is loaded
     */
    int applyRecheck(List<BlockPos> positions) {
        if (ensureStash() == null) return -1;
        if (positions == null || positions.isEmpty()) return 0;
        int marked = 0;
        for (BlockPos pos : positions) {
            if (pos == null) continue;
            Box box = stash.boxAt(pos);
            if (box == null || box.excluded || box.foreign || box.unreachable || failedSurvey.contains(box)) continue;
            if (requestedSurvey.add(box)) marked++;
        }
        if (marked > 0) {
            version++;
            idle = false;
        }
        return marked;
    }

    /** The server cancelled an order: the sorter wakes up to stop it at the next safe point. */
    boolean orderCancel(String id) {
        boolean held = orderRunner.cancel(id);
        if (held) {
            version++;
            idle = false;
        }
        return held;
    }

    /** A new order was taken: the sorter wakes up for it. */
    void orderTaken() {
        version++;
        idle = false;
    }

    private boolean isKitJob() {
        return job == Job.KitTake || job == Job.KitUnpack || job == Job.KitFill;
    }

    private boolean isKitBox(Box box) {
        return Kits.isKitBox(box);
    }

    // Repacking shulkers

    /**
     * Shulkers may be put down: not paused after a problem, and none left standing that couldn't be picked up.
     */
    private boolean shulkersOk() {
        return ticks >= repackPausedUntil && !dropLost && pendingResume == null;
    }

    private boolean compactOn() {
        return compact.get() && shulkersOk();
    }

    /**
     * No more shulkers for a few minutes, sorting goes on.
     */
    private void pauseShulkers(String why) {
        warning("%s, no more shulker work for a few minutes.", why);
        repackPausedUntil = ticks + PAUSE_TICKS;
    }

    private boolean repackOn() {
        return repack.get() && shulkersOk();
    }

    /**
     * Cleans a carried mixed shulker, or packs carried items into a bulk shulker, fetching one if needed.
     * @return true when a job was started
     */
    private boolean nextRepackJob() {
        // Compacting has the shulkers and the stacks set aside for it
        if (compactor.active()
                || repackWork != null
                        && !repackWork.terminal()
                        && repackWork.phase() == com.porter.wesort.inventory.PhysicalWork.Phase.STORE) return false;

        // A carried shulker with items in it that belong elsewhere: clean it out right here
        if (freeCarrySlot() != -1) {
            for (int slot : carriedSlots()) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (strays(stack).isEmpty()) continue;
                mainItem = Categories.dominantContent(stack).getItem();
                return startHandling(Job.Clean, slot, null);
            }
        }

        // Loose items with a bulk shulker to go in
        for (int slot : carriedSlots()) {
            ItemStack loose = mc.player.getInventory().getItem(slot);
            BulkKey key = keyOf(loose);
            if (key == null || packTried.contains(key)) continue;
            int stacks = carriedStacksOf(key);
            // With compacting on, new bulk shulkers are made by compacting: a full shulker's worth, never a part-filled
            // one
            boolean startNew = newBulkAt.get() > 0 && stacks >= newBulkAt.get();

            for (int s : carriedSlots()) {
                ItemStack shulker = mc.player.getInventory().getItem(s);
                if (bulkWithRoom(shulker, key) || (startNew && emptyShulker(shulker))) {
                    return startHandling(Job.Fill, s, key);
                }
            }

            if (freeCarrySlot() != -1) {
                Box from = null;
                int fromSlot = -1;
                for (Box box : stash.boxes) {
                    if (!box.sortable() || box.stale || box.dropOff || blocked(box, Job.Fetch) || isKitBox(box))
                        continue;
                    for (int i = 0; i < box.contents.length; i++) {
                        ItemStack s = box.contents[i];
                        boolean usable = bulkWithRoom(s, key) || (startNew && emptyShulker(s));
                        if (usable && (from == null || closer(box, from))) {
                            from = box;
                            fromSlot = i;
                            break;
                        }
                    }
                }
                if (from != null) return fetch(from, fromSlot);
            }

            // No empty box is an explicit, non-mutating precondition failure, not a successful repack.
            if (startNew && (repackWork == null || repackWork.terminal())) {
                repackWork = new com.porter.wesort.inventory.PhysicalWork("Fill", System.nanoTime());
                repackWork.failBeforeMutation("No verified empty shulker available");
                step("repacking blocked: no verified empty shulker; keeping items loose.");
            }
            // Nowhere to pack it: it's put away loose
            packTried.add(key);
        }

        return false;
    }

    private static BulkKey keyOf(ItemStack stack) {
        StackInfo info = StackInfo.of(stack);
        return info == null ? null : info.bulkKey();
    }

    private boolean fetch(Box box, int slot) {
        fetchWants = it -> Utils.isShulker(it.getItem());
        wantSlot = slot;
        return start(Job.Fetch, box);
    }

    /** Fetch bottles of enchanting for an XP top-up. Tracked for the leftover pass like any order take. */
    private boolean fetchXp(Box box, int slot) {
        fetchWants = StashSorter::isXpBottle;
        wantSlot = slot;
        return start(Job.Fetch, box);
    }

    /**
     * Slots inside a shulker holding different item types from the shulker's dominant contents
     * full of. Empty when the shulker isn't one to clean: not a shulker, named, all one item, or wanted whole by a
     * while preserving physically assigned kits and protected cargo.
     */
    private List<Integer> strays(ItemStack shulker) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem())) return List.of();
        if (!repackableName(shulker)) return List.of();

        ItemStack main = Categories.dominantContent(shulker);
        if (main == null) return List.of();
        BulkKey dominant = keyOf(main);
        if (dominant == null) return List.of();

        // A saved kit, finished or part built, stays whole
        if (isKitShulker(shulker)) return List.of();

        // Kit and reserved boxes are protected by Manager-assigned policy.

        List<Integer> out = new ArrayList<>();
        ItemStack[] inside = inside(shulker);
        for (int i = 0; i < inside.length; i++) {
            ItemStack s = inside[i];
            if (s == null || s.isEmpty()) continue;
            if (fullBundle(s)) {
                out.add(i);
                continue;
            }
            if (dominant.equals(keyOf(s))) continue;
            // Move different variants to a safe
            // ordinary destination only when it is currently available.
            if (canPlace(s)) out.add(i);
        }
        return out;
    }

    /**
     * A finished or mixed part-built kit, or what is left of a kit just given up on. A one-kind shulker that merely
     * fits inside a kit is an ordinary bulk shulker.
     */
    private boolean isKitShulker(ItemStack shulker) {
        return Kits.claims(shulker) || kitBuilder.heldKit(shulker) != null || orderRunner.claims(shulker);
    }

    /**
     * A shulker holding only stacks of this bulk key, with room for more.
     */
    /** Protect human labels and duplicate UUID identities from physical optimization. */
    private boolean repackableName(ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) return true;
        if (!com.porter.wesort.data.ShulkerUuid.repackableName(name.getString())) return false;
        String uuid = uuidOf(stack);
        if (uuid == null || stash == null) return false;
        int copies = 0;
        for (Box box : stash.boxes) {
            if (box.contents == null || box.stale) continue;
            for (ItemStack item : box.contents) {
                if (uuid.equals(uuidOf(item)) && ++copies > 1) return false;
            }
        }
        return true;
    }

    private boolean bulkWithRoom(ItemStack shulker, BulkKey key) {
        if (shulker == null
                || shulker.isEmpty()
                || !Utils.isShulker(shulker.getItem())
                || !repackableName(shulker)
                || isKitShulker(shulker)) return false;

        List<StackInfo> contents = StackInfo.of(shulker).contents();
        if (contents.isEmpty()) return false;
        boolean room = contents.size() < 27;
        for (StackInfo s : contents) {
            if (!key.equals(s.bulkKey())) return false;
            if (s.count < s.maxStack) room = true;
        }
        return room;
    }

    private boolean emptyShulker(ItemStack shulker) {
        if (shulker == null
                || shulker.isEmpty()
                || !Utils.isShulker(shulker.getItem())
                || !repackableName(shulker)
                || Kits.claims(shulker)
                || orderRunner.claims(shulker)) return false;
        for (ItemStack s : inside(shulker)) if (s != null && !s.isEmpty()) return false;
        return true;
    }

    private static ItemStack[] inside(ItemStack shulker) {
        return Kits.inside(shulker);
    }

    private int carriedStacksOf(BulkKey key) {
        int count = 0;
        for (int slot : carriedSlots())
            if (key.equals(keyOf(mc.player.getInventory().getItem(slot)))) count++;
        return count;
    }

    private int freeCarryCount() {
        int count = 0;
        for (int slot : carry) if (mc.player.getInventory().getItem(slot).isEmpty()) count++;
        return count;
    }

    private boolean closer(Box a, Box b) {
        Vec3 eyes = mc.player.getEyePosition();
        return eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(a)))
                < eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(b)));
    }

    private boolean startHandling(Job next, int slot, BulkKey fill) {
        if (slot >= 0) {
            returningChangedShulker = false;
            verifiedReturnStack = ItemStack.EMPTY;
        }
        handlingProof = next == Job.Clean || next == Job.Fill || next == Job.KitFill || next == Job.KitUnpack;
        if (next != Job.Clean && next != Job.Fill) repackExpected = "";
        if (next == Job.KitFill || next == Job.KitUnpack) {
            if (slot < 0) {
                blockRepack("Kit shulker needs fresh physical recovery verification");
                return false;
            }
            try {
                repackOuter = mc.player.getInventory().getItem(slot).copy();
                repackExpected = com.porter.wesort.compat.RepackRuntime.signature(repackOuter);
                repackExistingCopies = (int) repackInventory().stream()
                                .filter(this::repackMatches)
                                .count()
                        - 1;
                if (mc.gameMode.getPlayerMode().isBlockPlacingRestricted()) {
                    kitBuilder.suspend("Kit packing requires permitted placement");
                    return false;
                }
                // No pickaxe needed: a shulker breaks by hand too, a pickaxe just speeds it up.
                repackConservation = com.porter.wesort.compat.RepackRuntime.conservation(repackInventory());
            } catch (RuntimeException invalid) {
                kitBuilder.suspend("Cannot verify the exact kit box components/counts");
                return false;
            }
        }
        if (next == Job.Clean || next == Job.Fill) {
            if (slot >= 0) {
                if (repackWork != null && !repackWork.terminal()) return false;
                repackWork = new com.porter.wesort.inventory.PhysicalWork(next.name(), System.nanoTime());
                repackOuter = mc.player.getInventory().getItem(slot).copy();
                repackExpected = "";
                try {
                    if (!repackableName(repackOuter) || isKitShulker(repackOuter))
                        return repackPrecondition("Non-WeSort named or kit shulker is protected");
                    repackExpected = com.porter.wesort.compat.RepackRuntime.signature(repackOuter);
                    repackExistingCopies = (int) repackInventory().stream()
                                    .filter(this::repackMatches)
                                    .count()
                            - 1;
                    if (mc.gameMode.getPlayerMode().isBlockPlacingRestricted())
                        return repackPrecondition("Game mode forbids physical shulker placement");
                    // No pickaxe needed: a shulker breaks by hand too, a pickaxe just speeds it up.
                    if (stash.boxes.stream()
                            .noneMatch(box -> box.sortable()
                                    && !box.dropOff
                                    && !box.stale
                                    && !isKitBox(box)
                                    && roomFor(box, repackOuter)))
                        return repackPrecondition("No verified storage capacity for the repacked shulker");
                    repackConservation = com.porter.wesort.compat.RepackRuntime.conservation(repackInventory());
                } catch (RuntimeException failure) {
                    return repackPrecondition(
                            "Cannot verify exact shulker metadata and inventory counts before placement");
                }
            } else if (repackWork == null || repackWork.terminal() || repackOuter.isEmpty()) {
                repackWork = new com.porter.wesort.inventory.PhysicalWork(next.name(), System.nanoTime());
                blockRepack("Standing shulker needs fresh recovery verification before work can resume");
                return false;
            } else repackConservation = null; // Next verified round includes newly gathered stock.
        }
        restoreHotbar();
        if (next == Job.Clean || next == Job.Fill)
            journalShulkerPhase(com.porter.wesort.inventory.PhysicalWork.Phase.SELECT);
        resetWander();
        job = next;
        target = null;
        handledSlot = handledOrigin = slot;
        fillKey = fill;
        handle = Handle.Place;
        handleTicks = 0;
        breakRetries = 0;
        brokenSince = -1;
        placedWrong = false;
        placed = null;
        placedConfirmed = false;
        shulkersAtBreak = -1;
        actions = 0;
        collectedSlot = -1;
        fillSample = ItemStack.EMPTY;
        if (fill != null) {
            Compactor.Plan active = compactor.plan;
            if (active != null && fill.equals(active.key)) fillSample = active.sample.copy();
            else {
                for (int s : carriedSlots()) {
                    ItemStack stack = mc.player.getInventory().getItem(s);
                    if (fill.equals(keyOf(stack))) {
                        fillSample = stack.copyWithCount(1);
                        break;
                    }
                }
            }
        }
        BlockPos here = mc.player.blockPosition();
        if (resuming) return true;
        switch (next) {
            case Clean -> {
                ItemStack shulker = mc.player.getInventory().getItem(slot);
                int n = shulker.isEmpty() ? 0 : strays(shulker).size();
                step(String.format(
                        "repacking: cleaning a mixed shulker beside %s; next: break and pick it up, then put away %d stacks.",
                        xyz(here), n));
            }
            case Fill ->
                step(String.format(
                        "repacking: filling a shulker beside %s; next: break and pick it up, then put it away.",
                        xyz(here)));
            case KitFill ->
                step(String.format(
                        orderKit()
                                ? "order: filling the shulker for the order beside %s; next: break and pick it up."
                                : "kits: filling a kit shulker beside %s; next: break and pick it up.",
                        xyz(here)));
            case CraftUnpack ->
                step(String.format(
                        "crafting: taking what the craft needs out of a shulker beside %s; next: break and pick it up.",
                        xyz(here)));
            case KitUnpack ->
                step(String.format(
                        orderKit()
                                ? "order: taking what the order needs out of a shulker beside %s; next: break and pick it up."
                                : "kits: unpacking a kit shulker beside %s; next: break and pick it up.",
                        xyz(here)));
            default -> {}
        }
        return true;
    }

    /**
     * Keep the WeSort automation scope across Meteor's deferred rotation. Meteor's
     * BlockUtils.place schedules its own callback, which otherwise loses the
     * originating WeSort stack before the UseItemOn packet is journaled.
     * Select the same support face as BlockUtils and let its normal immediate
     * placement path retain all item swapping, swing and entity checks.
     */
    private boolean placeWithDurableRotation(BlockPos spot, FindItemResult item) {
        if (!rotate.get()) return BlockUtils.place(spot, item, false, 50, true, true, true);
        if (mc.player == null || !item.isHotbar() || !BlockUtils.canPlace(spot, true)) return false;
        var side = BlockUtils.getPlaceSide(spot);
        // Air-place has no support face and cannot identify the exact permitted
        // placement cell from the outgoing packet. Refuse it, never journal a guess.
        if (side == null) return false;
        var hit = net.minecraft.world.phys.Vec3.atCenterOf(spot)
                .add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        BlockPos target = spot.immutable();
        ItemStack expected = mc.player.getInventory().getItem(item.slot()).copy();
        Rotations.rotate(
                Rotations.getYaw(hit),
                Rotations.getPitch(hit),
                50,
                com.porter.wesort.compat.InventorySync.scopedAutomation(() -> {
                    if (mc.player == null
                            || !InventorySync.ready()
                            || !sortingPermitted()
                            || !ItemStack.matches(
                                    expected, mc.player.getInventory().getItem(item.slot()))
                            || !BlockUtils.place(target, item, false, 50, true, true, true))
                        com.porter.wesort.compat.InventorySync.clearPlacementExpectation();
                }));
        return true;
    }

    /**
     * One step of repacking a shulker beside us: place, open, move items, break, pick back up.
     */
    private void tickHandling() {
        // Fail-closed for every handle: orphan recovery, resume and collect run outside the
        // repacking gate below but must never act while the durable journal is fenced.
        if (PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror()) return;
        boolean repacking = repackWork != null && !repackWork.terminal() && (job == Job.Clean || job == Job.Fill);
        if (repacking) {
            var phase = switch (handle) {
                case Place, Wander -> com.porter.wesort.inventory.PhysicalWork.Phase.PLACE;
                case Open, Return, Resume -> com.porter.wesort.inventory.PhysicalWork.Phase.OPEN;
                case Work -> com.porter.wesort.inventory.PhysicalWork.Phase.TRANSFER;
                case Verify -> com.porter.wesort.inventory.PhysicalWork.Phase.VERIFY;
                case Break -> com.porter.wesort.inventory.PhysicalWork.Phase.BREAK;
                case Collect -> com.porter.wesort.inventory.PhysicalWork.Phase.PICKUP;
                case Leave -> com.porter.wesort.inventory.PhysicalWork.Phase.SELECT;
            };
            if (repacking) repackWork.enter(phase, System.nanoTime());
            // The durable journal phase must reflect a durable fact: OPEN is only persisted once
            // the placed shulker is confirmed in the world. BlockUtils.place defers the real
            // interact into a rotation callback, so the schedule (handle = Open) precedes the
            // packet — and the crash marker arms on the journal phase. Persisting OPEN early
            // would arm the crash on a placement whose packet (and durable intent) never left.
            boolean openConfirmed =
                    phase != com.porter.wesort.inventory.PhysicalWork.Phase.OPEN || placed == null || placedConfirmed;
            if (openConfirmed) journalShulkerPhase(phase);
            if (PhysicalJournalRuntime.blocked()) return;
        }
        handleTicks++;

        // Written down whenever the shulker's state changes, so a restart can finish the step
        if (handle != syncedHandle
                || !java.util.Objects.equals(placed, syncedPlaced)
                || placedConfirmed != syncedConfirmed) {
            syncedHandle = handle;
            syncedPlaced = placed;
            syncedConfirmed = placedConfirmed;
            syncInFlight();
        }
        if (resuming && ticks - resumeStarted > RESUME_MAX_TICKS && handle != null) {
            abandonShulker("Couldn't finish the shulker step at "
                    + (placed == null ? "the saved place" : placed.toShortString()) + " within 10 minutes.");
            return;
        }

        switch (handle) {
            case Place -> handlePlace();
            case Wander -> handleWander();
            case Open -> handleOpen();
            case Work -> handleWork();
            case Verify -> handleVerify();
            case Break -> handleBreak();
            case Collect -> handleCollect();
            case Return -> handleReturn();
            case Leave -> handleLeave();
            case Resume -> handleResume();
        }
    }

    private void handlePlace() {
        ItemStack shulker = mc.player.getInventory().getItem(handledSlot);
        if (!Utils.isShulker(shulker.getItem())) {
            endHandling();
            return;
        }
        if (handlingProof && !repackExpected.isEmpty() && !repackMatches(shulker)) {
            blockRepack("The selected shulker changed before placement");
            return;
        }

        // Shulkers are not put down in quick succession: that is what a build bot does
        if (ticks - lastPlaceTick < PLACE_GAP_TICKS) {
            wait = 10;
            return;
        }

        // Where first: nothing is moved for a spot that isn't there
        // The spot a walk was made for is placed at directly; else one beside me
        BlockPos spot = wanderSpot != null && BlockUtils.canPlace(wanderSpot, true) && spotProblem(wanderSpot) == null
                ? wanderSpot
                : null;
        if (spot == null) {
            wanderSpot = null;
            spot = safeSpot();
        }
        if (spot == null) {
            // Nothing beside me: the nearest spot within 5 blocks that can be walked to, and only when there is none
            // the work pauses
            String why = startWander();
            if (why == null) return;
            pauseShulkers("no spot within " + PlaceSearch.RADIUS + " blocks: " + why);
            endHandling();
            return;
        }
        wanderSpot = null;

        // It has to be in the hotbar to place it
        int hotbar = handledSlot;
        if (hotbar >= 9) {
            hotbar = -1;
            Set<Integer> keep = parseSlots(keepSlots.get());
            // An empty slot first, so nothing of the player's moves
            for (int i = 0; i < 9 && hotbar == -1; i++)
                if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) hotbar = i;
            if (hotbar == -1) {
                for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i)) hotbar = i;
                if (hotbar != -1) {
                    // The player's item ends up in the carry slot: it's theirs until it's put back
                    swapBackFrom = handledSlot;
                    swapBackTo = hotbar;
                    hotbarDisplaced = mc.player.getInventory().getItem(hotbar).copy();
                }
            }
            if (hotbar == -1) {
                pauseShulkers("No hotbar slot I'm allowed to use to place shulkers");
                endHandling();
                return;
            }
            InvUtils.move().from(handledSlot).to(hotbar);
            handledSlot = hotbar;
            wait = delay.get();
            return;
        }

        if (repackWork != null && !repackWork.terminal() && !repackWork.attempt(System.nanoTime())) {
            blockRepack("Placement attempt limit");
            return;
        }
        shulkersBefore = countShulkers();
        placedStacks = StackInfo.of(shulker).contents().size();
        // W-061: this job places its own temporary shulker into this empty cell, nothing else.
        com.porter.wesort.compat.WorldInteractionGuard.authorizePlace(spot);
        com.porter.wesort.compat.InventorySync.expectPlacement(
                spot, BuiltInRegistries.ITEM.getKey(shulker.getItem()).toString());
        if (!placeWithDurableRotation(spot, new FindItemResult(hotbar, shulker.getCount()))) {
            com.porter.wesort.compat.InventorySync.clearPlacementExpectation();
            placeFailed(spot);
            return;
        }

        lastPlaceTick = ticks;
        placed = spot;
        placedConfirmed = false;
        placedItemId = BuiltInRegistries.ITEM.getKey(shulker.getItem()).getPath();
        // Written down now, in case the block never shows: it is a shulker standing somewhere until it's picked up
        recordOrphan(spot, placedName(), placedStacks);
        shulkerSlotsBefore.clear();
        for (int i = 0; i < 36; i++) {
            if (i != handledSlot
                    && Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
        }
        handle = Handle.Open;
        handleTicks = 0;
        wait = delay.get();
    }

    /**
     * A rejected spot is skipped during the cooldown, then assessed against the current world. After three in a row shulkers are left alone for a while.
     */
    private void placeFailed(BlockPos spot) {
        placementRetryAt.put(spot.immutable(), ticks + PAUSE_TICKS);
        debug("Couldn't put a shulker at %s.", spot.toShortString());
        if (++placeFailures >= 3) {
            placeFailures = 0;
            pauseShulkers("Couldn't put the shulker down (3 spots failed)");
            endHandling();
            return;
        }
        wait = 5;
    }

    private boolean shulkerAt(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock;
    }

    private void handleOpen() {
        // Put back whatever we swapped out of the hotbar to place it, as soon as it's safe to
        if (swapBackFrom != -1) {
            restoreHotbar();
            if (swapBackFrom == -1) {
                wait = delay.get();
                return;
            }
        }

        // The place may still be on its way: longer while the shulker is out of the inventory
        if (!shulkerAt(placed)) {
            if (placedConfirmed) {
                if (handlingProof) {
                    blockRepack("Placed shulker disappeared before full work verification");
                    return;
                }
                // Was there, now it isn't: broken, or picked up by someone
                handle = Handle.Collect;
                handleTicks = 0;
                repackRebaselined = false;
                return;
            }
            boolean gone = countShulkers() < shulkersBefore;
            if (handleTicks <= (gone ? 200 : 30)) return;

            if (gone) {
                if (handlingProof) {
                    blockRepack("Placement consumed the shulker without a verified world source");
                    return;
                }
                // Out of the inventory but no block: a late update, or it's on the ground. Looked for, and on record
                warning(
                        "The shulker I put down at %s hasn't shown up as a block, looking for it.",
                        placed.toShortString());
                placedConfirmed = true;
                handle = Handle.Collect;
                handleTicks = 0;
                repackRebaselined = false;
                return;
            }

            // Never got placed after all, it's still in the inventory
            BlockPos failed = placed;
            clearOrphan(failed);
            placed = null;
            handle = Handle.Place;
            handleTicks = 0;
            placeFailed(failed);
            return;
        }
        if (!placedConfirmed) {
            placedConfirmed = true;
            placeFailures = 0;
            recordOrphan(placed, placedName(), placedStacks);
        }
        lastGoodStand = mc.player.blockPosition();

        if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
            if (!InventorySync.confirmedSource(List.of(placed))) {
                if (handleTicks > HANDLE_TIMEOUT) {
                    // Without a source-bound observation even an intact return is unsafe.
                    blockRepack("Shulker menu has no matching server-confirmed physical source");
                }
                return;
            }
            if (handlingProof) {
                try {
                    ItemStack[] contents = repackMenuContents(mc.player.containerMenu);
                    String observed = com.porter.wesort.compat.RepackRuntime.signature(repackOuter, contents);
                    if (!repackExpected.isEmpty() && !repackExpected.equals(observed)) {
                        String why =
                                "Standing shulker differs from its last verified contents at " + placed.toShortString()
                                        + " (expected " + repackExpected + ", observed " + observed + ")";
                        if (!returningChangedShulker && returnObservedShulker(contents, why)) return;
                        blockRepackConflict(why);
                        return;
                    }
                    var actual = repackCombined(contents);
                    if (repackConservation != null && !repackConservation.equals(actual)) {
                        // Do not replace the conservation baseline and hide a lost mutation.
                        blockRepackConflict("Shulker placement did not conserve exact items/components at "
                                + placed.toShortString());
                        return;
                    }
                    repackConservation = actual;
                } catch (RuntimeException failure) {
                    blockRepack("Cannot verify exact physical shulker components/counts");
                    return;
                }
            }
            // A shulker picked up again from a saved step is only worked with if it holds what the record says
            if (resumeVerify) {
                resumeVerify = false;
                String problem = resumeContentsProblem(mc.player.containerMenu);
                if (problem != null) {
                    if (!returningChangedShulker
                            && !repackOuter.isEmpty()
                            && returnObservedShulker(repackMenuContents(mc.player.containerMenu), problem)) return;
                    if (handlingProof || returningChangedShulker) {
                        blockRepackConflict("Cannot verify changed shulker for intact return: " + problem);
                        return;
                    }
                    int actual = 0;
                    for (int i = 0; i < 27; i++)
                        if (!mc.player.containerMenu.getSlot(i).getItem().isEmpty()) actual++;
                    closeOurWindow(mc.player.containerMenu);
                    alert(
                            "The shulker at %s doesn't hold what I expected (%s), leaving it alone.",
                            placed.toShortString(), problem);
                    // Standing there, with what it really holds
                    recordOrphan(placed, placedName(), actual);
                    compactor.drop();
                    dropLost = true;
                    dropLostAtMs = System.currentTimeMillis();
                    endHandling();
                    return;
                }
            }
            ownedMenu = mc.player.containerMenu;
            if (job == Job.Recover) recoveredContents = repackMenuContents(ownedMenu);
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++)
                if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
            handle = Handle.Work;
            handleTicks = 0;
            wait = firstClickWait();
            return;
        }

        // container-delay: the open click waits, and the wait does not count toward the open timeout
        if (openHeld()) {
            handleTicks--;
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT) {
            if (handlingProof || job == Job.Recover) {
                blockRepack("Shulker could not be opened for full physical verification");
                return;
            }
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        if (handleTicks == 1 || handleTicks % 20 == 0) {
            if (repackWork != null && !repackWork.terminal() && !repackWork.attempt(System.nanoTime())) {
                blockRepack("Open attempt limit");
                return;
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placed), Direction.UP, placed, false);
            // W-061: this job opens the shulker it placed, nothing else.
            com.porter.wesort.compat.WorldInteractionGuard.authorizeUse(placed);
            BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
        }
    }

    /**
     * What the shulker in the world is holding, for the records.
     */
    private String placedName() {
        Compactor.Plan plan = compactor.plan;
        if (plan != null) return plan.name;
        return fillKey != null ? new ItemStack(fillKey.item()).getItemName().getString() : "items";
    }

    private void handleWork() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu menu)) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        // A saved step with nothing to rebuild the work from: the shulker is just picked up
        if (resumeFinishOnly || returningChangedShulker) {
            if (!menu.getCarried().isEmpty()) {
                recoverCursor();
                return;
            }
            closeOurWindow(mc.player.containerMenu);
            handle = Handle.Break;
            handleTicks = 0;
            wait = delay.get();
            return;
        }

        boolean packing = job == Job.Fill && compactor.active();
        boolean busy = switch (job) {
            case Clean -> takeStray(menu);
            case KitUnpack -> kitBuilder.unpackOne(menu);
            case CraftUnpack -> crafter.unpackOne(menu);
            case KitFill -> kitBuilder.fillOne(menu);
            default -> packing ? compactor.packOne(menu) : packOne(menu);
        };
        if (busy) {
            actions++;
            wait = delay.get();
            return;
        }

        // Enter a separate phase; the next tick runs only after InventorySync's full readback/barrier.
        handle = Handle.Verify;
        handleTicks = 0;
    }

    /**
     * A source changed before this round's first transfer. Preserve the new slot
     * layout and return the box intact, rather than executing the obsolete plan.
     */
    private boolean returnObservedShulker(ItemStack[] contents, String why) {
        if (!com.porter.wesort.inventory.StockDeviation.mayReturnIntact(
                        InventorySync.ready() && InventorySync.confirmedSource(List.of(placed)),
                        !PhysicalJournalRuntime.blocked() && !PhysicalJournalRuntime.awaitingMirror(),
                        placedConfirmed
                                && !repackOuter.isEmpty()
                                && blockId(mc.level, placed).equals(placedItemId),
                        mc.player.containerMenu.getCarried().isEmpty(),
                        actions,
                        returningChangedShulker)
                || !freeInventorySlot()) return false;
        try {
            // Other player inventory must still match: only the source's observed
            // contents may differ, not the result of an uncertain transfer.
            if (repackConservation != null) {
                var previousContents =
                        com.porter.wesort.compat.RepackRuntime.conservation(Arrays.asList(Kits.inside(repackOuter)));
                var observedContents = com.porter.wesort.compat.RepackRuntime.conservation(Arrays.asList(contents));
                if (!com.porter.wesort.inventory.StockDeviation.matchesWithObservedSource(
                        repackConservation, previousContents, observedContents, repackCombined(contents))) return false;
            }
            ItemStack observed = repackOuter.copy();
            observed.set(
                    DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(Arrays.asList(contents)));
            String signature = com.porter.wesort.compat.RepackRuntime.signature(repackOuter, contents);
            if (!signature.equals(com.porter.wesort.compat.RepackRuntime.signature(observed))) return false;
            verifiedReturnStack = observed;
            returningChangedShulker = true;
            repackOuter = observed.copy();
            repackExpected = signature;
            repackConservation = repackCombined(contents);
            repackExistingCopies =
                    (int) repackInventory().stream().filter(this::repackMatches).count();
            placedStacks = (int)
                    Arrays.stream(contents).filter(stack -> !stack.isEmpty()).count();
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++)
                if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
            if (compactor.active()) compactor.drop();
            placedWrong = job == Job.KitFill || job == Job.KitUnpack;
            resumeFinishOnly = true;
            resumeVerify = false;
            ownedMenu = mc.player.containerMenu;
            handle = Handle.Break;
            handleTicks = 0;
            breakTicks = 0;
            brokenSince = -1;
            recordOrphan(placed, "changed shulker", placedStacks);
            syncInFlight();
            if (stash.isDirty()) {
                blockRepackConflict("Could not persist changed shulker before returning it");
                return true;
            }
            warning("%s; recorded the new contents, returning the shulker intact and replanning.", why);
            closeOurWindow(mc.player.containerMenu);
            wait = delay.get();
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void handleVerify() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu menu)) {
            blockRepack("Verification menu disappeared");
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            recoverCursor();
            return;
        }
        if (handlingProof) {
            try {
                var contents = repackMenuContents(menu);
                if (!repackConservation.equals(repackCombined(contents))) {
                    blockRepackConflict(
                            "Transfer did not conserve exact item components/counts at " + placed.toShortString());
                    return;
                }
                repackExpected = com.porter.wesort.compat.RepackRuntime.signature(repackOuter, contents);
                repackExistingCopies = (int)
                        repackInventory().stream().filter(this::repackMatches).count();
            } catch (RuntimeException failure) {
                blockRepack("Cannot verify exact physical shulker components/counts");
                return;
            }
        }
        boolean packing = job == Job.Fill && compactor.active();

        if (packing) {
            boolean stay = compactor.afterPacking(menu, actions, placed);
            closeOurWindow(mc.player.containerMenu);
            handle = stay ? Handle.Leave : Handle.Break;
            handleTicks = 0;
            wait = delay.get();
            return;
        }

        if (job == Job.Fill && actions == 0 && fillKey != null) packTried.add(fillKey);
        // Nothing to take out after all: stop trying, or we'd keep reopening the same shulker
        if (job == Job.Clean && actions == 0) pauseShulkers("There was nothing to take out of the shulker");
        if (isKitJob() && actions == 0) kitBuilder.suspend("there was nothing I could move in the shulker.");
        if (job == Job.CraftUnpack && actions == 0) crafter.unpackFailed();
        closeOurWindow(mc.player.containerMenu);
        handle = Handle.Break;
        handleTicks = 0;
        wait = delay.get();
    }

    /**
     * Takes one stack that belongs elsewhere out of the open shulker, into a free carry slot. One slot stays free, for
     * picking the shulker up.
     */
    private boolean takeStray(AbstractContainerMenu menu) {
        int into = freeCarrySlot();
        if (into == -1 || freeCarryCount() <= 1) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty() || (!fullBundle(s) && s.is(mainItem))) continue;
            stashHeld.put(into, s.copy());
            InvUtils.move().fromId(i).toId(inventoryToMenu(into, 27));
            moved++;
            return true;
        }
        return false;
    }

    /**
     * Puts one carried stack of the item being packed into the open shulker.
     */
    private boolean packOne(AbstractContainerMenu menu) {
        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (fillKey == null || !fillKey.equals(keyOf(stack))) continue;

            for (int i = 0; i < 27; i++) {
                ItemStack here = menu.getSlot(i).getItem();
                if (here.isEmpty()
                        || (ItemStack.isSameItemSameComponents(here, stack)
                                && here.getCount() < here.getMaxStackSize())) {
                    InvUtils.shiftClick().slotId(inventoryToMenu(slot, 27));
                    moved++;
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * The round is packed and more is wanted: the window is closed and the shulker stays where it is while the next round
     * is gathered.
     */
    private void handleLeave() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            closeOurWindow(mc.player.containerMenu);
            return;
        }
        // Left standing: the plan knows where, and the next round comes back to it
        Compactor.Plan plan = compactor.plan;
        if (plan != null) {
            plan.placedPos = placed;
            plan.dimension = mc.level.dimension().toString();
        }
        endHandling();
    }

    private void handleBreak() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            closeOurWindow(mc.player.containerMenu);
            return;
        }

        // The break is reported as soon as the block looks gone here, but the server can still refuse it and put the
        // shulker back: it only counts as broken once the spot has stayed clear for a moment
        if (!shulkerAt(placed)) {
            if (brokenSince < 0) brokenSince = ticks;
            if (ticks - brokenSince < BREAK_SETTLE_TICKS) return;
            brokenSince = -1;
            if (pickaxeSwapped) {
                InvUtils.swapBack();
                pickaxeSwapped = false;
            }
            // Hand the controller back cleanly: without an explicit stop, vanilla keeps the
            // destroy state set and every later stop/continue re-checks the stale target
            // against an expired permit (break-no-permit storm in Collect with no live permit).
            // Finish the previously authorized START; never reissue a BREAK
            // permit against client-predicted air (that overwrites the original
            // exact shulker permit before STOP_DESTROY_BLOCK is sent).
            mc.gameMode.stopDestroyBlock();
            handle = Handle.Collect;
            handleTicks = 0;
            return;
        }
        if (returningChangedShulker && !blockId(mc.level, placed).equals(placedItemId)) {
            blockRepackConflict("Changed shulker was replaced before break at " + placed.toShortString());
            return;
        }
        brokenSince = -1;
        // It can't be picked up while it stands: what is held now is what picking it up adds to
        shulkersAtBreak = countShulkers();

        // Breaking it needs a free slot to pick it up into: otherwise it would end up on the ground
        if (!freeInventorySlot()) {
            if (handleTicks % 200 == 1)
                alert(
                        "No free inventory slot to pick the shulker at %s up into, leaving it where it is until there is one.",
                        placed.toShortString());
            if (handleTicks > 20 * 60 * 5)
                abandonShulker("No free inventory slot to pick the shulker at " + placed.toShortString() + " up into.");
            return;
        }

        // Bad TPS looks like a shulker that won't break: the server hasn't confirmed it yet.
        // The clock pauses until the server answers, so lag doesn't abandon the shulker.
        if (com.porter.wesort.compat.InventorySync.ready() && ++breakTicks > HANDLE_TIMEOUT * 2) {
            abandonShulker("Couldn't break the shulker at " + placed.toShortString() + ", it's still there.");
            return;
        }

        // No pickaxe needed: a shulker breaks by hand too. Use a pickaxe when there is one,
        // for speed, otherwise keep breaking with the hand.
        if (!pickaxeSwapped) {
            FindItemResult pickaxe = InvUtils.findInHotbar(s -> s.is(ItemTags.PICKAXES));
            if (pickaxe.found()) {
                InvUtils.swap(pickaxe.slot(), true);
                pickaxeSwapped = true;
            }
        }
        // W-061: this job breaks the temporary shulker it placed, nothing else.
        // Fail-closed even if a caller bypassed the tick fence: never break while fenced.
        if (PhysicalJournalRuntime.blocked()) return;
        com.porter.wesort.compat.WorldInteractionGuard.authorizeBreak(placed);
        BlockUtils.breakBlock(placed, true);
    }

    private static final int BREAK_SETTLE_TICKS = 10;
    private static final int MAX_BREAK_RETRIES = 5;
    private int breakRetries;
    private int brokenSince = -1;
    // The placed shulker turned out to be the wrong one: break it back down, put it back, try again
    private boolean placedWrong;

    private static String fmtPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private boolean shulkerItemNearby() {
        return shulkerItemNearby(4);
    }

    private boolean shulkerItemNearby(int radius) {
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(radius))) {
            if (Utils.isShulker(e.getItem().getItem())) return true;
        }
        return false;
    }

    private void handleCollect() {
        breakTicks = 0;
        // The server put the shulker back: it is broken again rather than looked for on the ground, where it never was
        if (shulkerAt(placed) && !shulkerItemNearby()) {
            if (++breakRetries > MAX_BREAK_RETRIES) {
                abandonShulker("Couldn't break the shulker at " + fmtPos(placed)
                        + ", the server keeps putting it back. It's still standing there.");
                return;
            }
            alert(
                    "The shulker at %s is still standing, the server didn't let it break. Breaking it again (%d/%d).",
                    fmtPos(placed), breakRetries, MAX_BREAK_RETRIES);
            brokenSince = -1;
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }
        // A block that turns up after all (a late update) is worked with again
        if (shulkerAt(placed)) {
            handle = Handle.Open;
            handleTicks = 0;
            return;
        }

        if (pickedUp()) {
            // Picked up: the new shulker, or failing that the one that holds what the plan was packing, in any slot
            int found = !repackExpected.isEmpty() ? verifiedRepackPickup() : -1;
            if (returningChangedShulker
                    && (found < 0 || found >= 36 || parseSlots(keepSlots.get()).contains(found))) {
                blockRepackConflict("Changed shulker pickup could not be assigned to a safe inventory slot");
                return;
            }
            for (int i = 0; i <= 40 && found == -1; i++) {
                if ((i >= 36 && i < 40) || shulkerSlotsBefore.contains(i)) continue;
                if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) found = i;
            }
            Compactor.Plan plan = compactor.plan;
            if (found == -1 && plan != null) {
                for (int i = 0; i <= 40 && found == -1; i++) {
                    if (i >= 36 && i < 40) continue;
                    ItemStack there = mc.player.getInventory().getItem(i);
                    if (Utils.isShulker(there.getItem())
                            && StackInfo.of(there).contents().stream().anyMatch(c -> plan.key.equals(c.bulkKey())))
                        found = i;
                }
            }
            if (found == -1) found = heldLikePlaced(false);

            if (job == Job.Recover) found = recoveredPickupSlot();
            if (job == Job.Recover
                    && (found < 0 || found >= 36 || parseSlots(keepSlots.get()).contains(found))) {
                blockRepackConflict("Recovered shulker has no verified storage carry slot");
                return;
            }

            if (found == -1) {
                alert("I picked up the shulker from %s but couldn't tell which slot it is in.", placed.toShortString());
                compactor.collected(-1, placed, ItemStack.EMPTY);
            } else {
                ItemStack got = mc.player.getInventory().getItem(found).copy();
                collectedSlot = found;
                if (returningChangedShulker && found < 36) {
                    carry.add(found);
                    stashHeld.put(found, got.copy());
                    Box source = heldFrom.get(handledOrigin);
                    if (orderWork() || !sorting()) {
                        orderHeld.put(found, got.copy());
                        if (source != null) heldFrom.put(found, source);
                    }
                    // STORE verification keeps ordinary repack work from starting
                    // until the updated box has been deposited.
                } else if (job == Job.Recover && found < 36) {
                    // A collected world drop-off belongs to the warehouse, never to the player's
                    // pre-existing inventory. Persist the priority return in both sorter modes.
                    carry.add(found);
                    stashHeld.put(found, got.copy());
                    stash.recoveredBox = new Stash.RecoveredBox(found, got.copy(), null, -1);
                    stash.markDirty();
                } else if (found < 36 && !carry.contains(found)) {
                    int free = freeCarrySlot();
                    if (free != -1) {
                        InvUtils.move().from(found).to(free);
                        collectedSlot = free;
                    } else {
                        // No carry slot to move it to: that slot is carried from now on, so it gets put away
                        warning(
                                "The repacked shulker ended up outside the carry slots, in slot %d, treating it as carried.",
                                found + 1);
                        carry.add(found);
                        stashHeld.put(found, got.copy());
                    }
                } else if (found >= 36) {
                    warning("The repacked shulker ended up in your offhand, please put it in your inventory.");
                }
                compactor.collected(collectedSlot, placed, got);
            }
            if (repackWork != null && !repackWork.terminal() && (job == Job.Clean || job == Job.Fill)) {
                repackStoreAt = System.currentTimeMillis();
                repackStoreBefore.clear();
                for (var box : stash.boxes)
                    if (box.known()) repackStoreBefore.put(box.parts.toString(), repackCount(box.contents));
                repackWork.enter(com.porter.wesort.inventory.PhysicalWork.Phase.STORE, System.nanoTime());
                journalShulkerPhase(com.porter.wesort.inventory.PhysicalWork.Phase.STORE);
            }
            clearOrphan(placed);
            if (job == Job.Recover) {
                idle = false;
                version++;
                clearStalled();
                stuckCarried.clear();
            }
            endHandling();
            return;
        }

        if (handleTicks == 20 * 30 || handleTicks == 20 * 60) {
            alert(
                    "Still looking for the shulker I broke at %s, it isn't in my inventory yet (%d s).",
                    placed.toShortString(), handleTicks / 20);
        }
        if (handleTicks > COLLECT_TIMEOUT) {
            // #198: reconcile before fencing. A late pickup (magnet delay, cursor, drifted
            // count) must not hard-lock all shulker work: adopt exactly one unambiguous
            // candidate, wait boundedly for a cursor-held box to land, fence only the rest.
            if (!repackRebaselined && tryAdoptLateCollect()) {
                repackRebaselined = true;
                return;
            }
            if (cursorMatchesRepack() && handleTicks <= COLLECT_TIMEOUT + COLLECT_SETTLE_TICKS) {
                if (handleTicks % (20 * 30) == 0)
                    alert(
                            "The shulker from %s is on my cursor, waiting for it to land in the inventory.",
                            placed.toShortString());
                return;
            }
            if (cursorLikePlaced() && handleTicks <= COLLECT_TIMEOUT + COLLECT_SETTLE_TICKS) {
                if (handleTicks % (20 * 30) == 0)
                    alert(
                            "A shulker like the one from %s is on my cursor, waiting for it to land.",
                            placed.toShortString());
                return;
            }
            // Keep the exact workspace and pickup baseline for a signed read-only
            // recovery; never discard it or claim the box is still on the ground.
            syncInFlight();
            blockRepack("Pickup not yet verified at " + placed.toShortString() + collectDiagnostics());
            return;
        }

        if (leftArea()) {
            abandonShulker("Skipping the shulker box at " + at(placed) + ": the way there leaves the stash area.");
            return;
        }

        // Dropped out of reach: walk over it
        if (handleTicks >= 40 && handleTicks % 200 == 40 && walkOn()) {
            ItemEntity drop = null;
            for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(4))) {
                if (Utils.isShulker(e.getItem().getItem())) {
                    drop = e;
                    break;
                }
            }
            if (drop != null) {
                walkNear(drop.blockPosition(), 0);
            }
        }
    }

    private static final int PICKUP_GRACE_TICKS = 20 * 3;

    private int recoveredPickupSlot() {
        if (recoveredContents == null) return -1;
        int found = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (shulkerSlotsBefore.contains(slot)) continue;
            ItemStack item = mc.player.getInventory().getItem(slot);
            if (!Utils.isShulker(item.getItem())
                    || !BuiltInRegistries.ITEM.getKey(item.getItem()).getPath().equals(placedItemId)) continue;
            ItemStack[] contents = inside(item);
            boolean matches = contents.length == recoveredContents.length;
            for (int i = 0; matches && i < contents.length; i++)
                matches = ItemStack.matches(contents[i] == null ? ItemStack.EMPTY : contents[i], recoveredContents[i]);
            if (!matches) continue;
            if (found >= 0) return -1;
            found = slot;
        }
        return found;
    }

    /**
     * Whether the shulker we broke is in the inventory now.
     * <p>
     * The shulker count alone was not enough: a shulker resumed after a reconnect may already be held, and a count taken
     * at the wrong moment never comes back up, so the bot kept looking on the ground for a shulker it was holding. So
     * once nothing is lying near the spot for a few seconds, a held shulker that matches it counts as picked up.
     */
    private boolean pickedUp() {
        if (job == Job.Recover && repackExpected.isEmpty()) {
            if (placed == null || !mc.level.isLoaded(placed) || shulkerAt(placed) || recoveredPickupSlot() < 0)
                return false;
            if (!InventorySync.confirmedMenu()) {
                releaseGoal();
                InventorySync.verifyInventory();
                return false;
            }
            return true;
        }
        if (!repackExpected.isEmpty()) {
            if (placed == null || !mc.level.isLoaded(placed) || shulkerAt(placed)) return false;
            if (verifiedRepackPickup() < 0
                    || repackInventory().stream().filter(this::repackMatches).count() != repackExistingCopies + 1L)
                return false;
            if (!InventorySync.confirmedMenu()) {
                releaseGoal();
                InventorySync.verifyInventory();
                return false;
            }
            return true;
        }
        int now = countShulkers();
        if (now >= shulkersBefore || (shulkersAtBreak >= 0 && now > shulkersAtBreak)) return true;
        // Only standing close by with nothing lying in sight, and only when exactly one held shulker could be it
        // (handleCollect has already gone back to breaking or opening if the block is there again)
        if (handleTicks <= PICKUP_GRACE_TICKS
                || mc.player.blockPosition().distSqr(placed) > 6 * 6
                || shulkerItemNearby(8)) return false;
        int held = heldLikePlaced(true);
        if (held != -1)
            info(
                    "Nothing dropped near %s and I hold a shulker like it in slot %d, taking it as picked up.",
                    placed.toShortString(), held < 36 ? held + 1 : 41);
        return held != -1;
    }

    /**
     * The inventory slot (40 for the offhand) of a held shulker that could be the one we put down: the same colour,
     * holding what the job put in or left in it. -1 if there is none, or with {@code unique} if more than one could be.
     */
    private int heldLikePlaced(boolean unique) {
        int match = -1;
        for (int i = 0; i <= 40; i++) {
            if (i >= 36 && i < 40) continue;
            ItemStack there = mc.player.getInventory().getItem(i);
            if (!fitsPlacedPlan(there)) continue;
            if (!unique) return i;
            if (match != -1) return -1;
            match = i;
        }
        return match;
    }

    /** True when the cursor holds a shulker that could be the one we put down (#198). */
    private boolean cursorLikePlaced() {
        return fitsPlacedPlan(cursorStack());
    }

    /** Same colour as the placed box, holding what the job put in or left in it. */
    private boolean fitsPlacedPlan(ItemStack there) {
        if (there.isEmpty() || !Utils.isShulker(there.getItem())) return false;
        if (!placedItemId.isEmpty()
                && !BuiltInRegistries.ITEM.getKey(there.getItem()).getPath().equals(placedItemId)) return false;
        List<StackInfo> inside = StackInfo.of(there).contents();
        if (inside.isEmpty()) return false;
        Compactor.Plan plan = compactor.plan;
        if (plan != null)
            return inside.size() == plan.placedSize && inside.stream().allMatch(c -> plan.key.equals(c.bulkKey()));
        else if (job == Job.Fill && fillKey != null) return inside.stream().allMatch(c -> fillKey.equals(c.bulkKey()));
        else if (job == Job.Clean && mainItem != null) {
            String main = BuiltInRegistries.ITEM.getKey(mainItem).getPath();
            return inside.stream().allMatch(c -> main.equals(c.id));
        }
        return false;
    }

    private List<ItemStack> repackInventory() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 36; i++)
            items.add(mc.player.getInventory().getItem(i).copy());
        items.add(mc.player.getOffhandItem().copy());
        // #198: a magnet-late pickup can sit on the cursor: it is carried inventory,
        // so conservation counts it consistently on both sides of a move.
        ItemStack cursor = cursorStack();
        if (!cursor.isEmpty()) items.add(cursor.copy());
        return items;
    }

    /** Whatever the cursor holds right now, or EMPTY when nothing is carried. Never throws. */
    private ItemStack cursorStack() {
        try {
            if (mc.player == null || mc.player.containerMenu == null) return ItemStack.EMPTY;
            ItemStack carried = mc.player.containerMenu.getCarried();
            return carried == null ? ItemStack.EMPTY : carried;
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** True when the cursor holds the expected repacked shulker (magnet/click delay, #198). */
    private boolean cursorMatchesRepack() {
        if (repackExpected.isEmpty()) return false;
        ItemStack cursor = cursorStack();
        return !cursor.isEmpty() && repackMatches(cursor);
    }

    private ItemStack[] repackMenuContents(AbstractContainerMenu menu) {
        ItemStack[] contents = new ItemStack[27];
        for (int i = 0; i < 27; i++) contents[i] = menu.getSlot(i).getItem().copy();
        return contents;
    }

    private Map<String, Long> repackCombined(ItemStack[] contents) {
        var items = repackInventory();
        var header = repackOuter.copy();
        header.remove(DataComponents.CONTAINER);
        items.add(header);
        items.addAll(java.util.Arrays.asList(contents));
        return com.porter.wesort.compat.RepackRuntime.conservation(items);
    }

    private boolean repackMatches(ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem()) || repackExpected.isEmpty()) return false;
        try {
            return repackExpected.equals(com.porter.wesort.compat.RepackRuntime.signature(stack));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * One-line Collect diagnostics for the fence message, heartbeat and logs: which
     * pickup evidence exists (fingerprint slot, colour/content slot, cursor, counts).
     */
    private String collectDiagnostics() {
        int verified = -1;
        int held = -1;
        try {
            verified = verifiedRepackPickup();
            held = heldLikePlaced(false);
        } catch (RuntimeException ignored) {
        }
        long count = -1;
        try {
            count = repackInventory().stream().filter(this::repackMatches).count();
        } catch (RuntimeException ignored) {
        }
        ItemStack cursor = cursorStack();
        String cursorDesc = cursor.isEmpty()
                ? "empty"
                : (Utils.isShulker(cursor.getItem())
                        ? "shulker"
                        : cursor.getItem().toString());
        return " (verifiedSlot=" + verified + " heldSlot=" + held + " cursor=" + cursorDesc + " matchingCount=" + count
                + " expectedCopies=" + repackExistingCopies + ")";
    }

    /**
     * Late-arrival reconciliation for a Collect that already timed out (#198). Adopts
     * exactly one unambiguous candidate and re-baselines the pickup expectation so the
     * strict gate verifies it next tick:
     * <ul>
     *   <li>exact fingerprint found but count drifted: recount the baseline;</li>
     *   <li>otherwise a unique same-colour/same-content box: adopt its signature.</li>
     * </ul>
     * Anything ambiguous returns false and the caller fences. Logged loudly: adoption
     * is an observation correction, never silent.
     */
    private boolean tryAdoptLateCollect() {
        if (repackExpected.isEmpty() || mc.player == null || mc.level == null) return false;
        int verified = verifiedRepackPickup();
        if (verified >= 0) {
            long count = repackInventory().stream().filter(this::repackMatches).count();
            if (count != repackExistingCopies + 1L) {
                repackExistingCopies = (int) count - 1;
                warning(
                        "Late pickup of the shulker from %s: exact box in slot %d but count drifted, re-baselined to %d expected copies.",
                        placed != null ? placed.toShortString() : "?",
                        verified < 36 ? verified + 1 : 41,
                        repackExistingCopies);
            }
            return true;
        }
        int unique = heldLikePlaced(true);
        if (unique >= 0) {
            ItemStack held = mc.player.getInventory().getItem(unique).copy();
            try {
                repackExpected = com.porter.wesort.compat.RepackRuntime.signature(held);
            } catch (RuntimeException e) {
                return false;
            }
            long count = repackInventory().stream().filter(this::repackMatches).count();
            repackExistingCopies = (int) count - 1;
            warning(
                    "Late pickup of the shulker from %s: adopted the unique same-colour box in slot %d with drifted contents.",
                    placed != null ? placed.toShortString() : "?", unique + 1);
            return true;
        }
        return false;
    }

    private int verifiedRepackPickup() {
        // A pickup can land in a previously occupied slot after the inventory
        // is rearranged. When the exact fingerprint was absent at break time
        // and there is precisely one matching copy now, its slot is unambiguous.
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            if (i >= 36 && i < 40) continue;
            if (repackMatches(mc.player.getInventory().getItem(i))) matches.add(i);
        }
        return PlacedShulkerRecovery.pickupSlot(matches, shulkerSlotsBefore, repackExistingCopies);
    }

    private int repackCount(ItemStack[] contents) {
        int count = 0;
        for (var stack : contents) if (repackMatches(stack)) count += stack.getCount();
        return count;
    }

    /** Why the tick is parked before any work: journal, inventory, navigation or permit. */
    private String preGateReason() {
        if (PhysicalJournalRuntime.blocked())
            return "waiting for automatic recovery: " + PhysicalJournalRuntime.status();
        if (PhysicalJournalRuntime.awaitingMirror())
            return "waiting for the Manager receipt: " + PhysicalJournalRuntime.status();
        if (!InventorySync.ready())
            return InventorySync.failed() ? "awaiting automatic inventory recovery" : "awaiting server inventory";
        if (com.porter.wesort.compat.NavigationRuntime.paused())
            return "navigation paused: " + com.porter.wesort.compat.NavigationRuntime.status();
        return "waiting";
    }

    /**
     * Records the pre-gate reason for the status line and heartbeat, and says it at most
     * every few seconds: a parked tick is visible without flooding the log.
     */
    private void gateLogThrottled(String reason) {
        gateReason = reason;
        if (clock - lastGateLogClock < GATE_LOG_CLOCKS) return;
        lastGateLogClock = clock;
        // The chat line needs a world; the log line does not.
        if (mc.player != null && mc.level != null) step(reason);
        else WeSortAddon.LOG.info("[wesort-stash-sorter] Stash: {}", reason);
    }

    /**
     * The durable journal healed (a verified inspection completed) but the in-memory repack
     * state still says NEEDS_RECONCILIATION: without a reset the bot sits there forever.
     * With a saved shulker step the resume path rebuilds the state, so the memory is
     * dropped; otherwise fresh tracking starts.
     */
    private void healRepackState() {
        if (repackWork == null
                || repackWork.phase() != com.porter.wesort.inventory.PhysicalWork.Phase.NEEDS_RECONCILIATION
                || mc.player == null
                || mc.level == null) return;
        // A debt-only fence must not block the heal that lifts it: rebuilding the sorter
        // tracking is exactly what makes pendingPhysicalRecovery true again.
        if ((PhysicalJournalRuntime.blocked() && !PhysicalJournalRuntime.debtGated())
                || PhysicalJournalRuntime.awaitingMirror()
                || !InventorySync.ready()) return;
        if (!repackConflict.isEmpty() || PhysicalJournalRuntime.recoveryProof() <= repackBlockedProof) return;
        repackVerifyReads = 0;
        repackVerifySeen.clear();
        storeWatchMs = 0;
        dropLost = false; // a new accepted reconciliation proof clears this latch (or expireDropLost below)
        dropLostAtMs = 0;
        if ((stash != null && stash.inFlight != null) || pendingResume != null || resuming) {
            repackWork = new com.porter.wesort.inventory.PhysicalWork("Shulker", System.nanoTime());
            step("verified recovery finished; next: resume the saved shulker step.");
        } else {
            repackWork = new com.porter.wesort.inventory.PhysicalWork("Shulker", System.nanoTime());
            step("verified recovery finished; next: carry on.");
        }
    }

    /**
     * Time-bounded lost-shulker latch (#198). A {@code dropLost} latch set by a local
     * Collect/pickup failure without an unfinished journal behind it expires after
     * {@link #DROP_LOST_TIMEOUT_MS}: the step is quarantined as an orphan (manual return
     * still required for that box) and other shulker work resumes instead of parking
     * the headless bot until a manual sorter restart. While the durable journal still
     * has an unfinished operation the fence stays: that mutation needs the
     * manager-inspection loop, not a local reset.
     */
    private void expireDropLost() {
        if (!dropLost || dropLostAtMs <= 0 || mc.player == null || mc.level == null) return;
        if (System.currentTimeMillis() - dropLostAtMs < DROP_LOST_TIMEOUT_MS) return;
        boolean journalBacked = PhysicalJournalRuntime.unfinished();
        if (placed != null) recordOrphan(placed, placedName(), placedStacks);
        alertError(
                "Shulker latch expired after 5m without verified recovery%s: quarantined %s for manual return; other shulker work resumes.",
                journalBacked ? " (journal still holds an unfinished operation, its fence stays)" : "",
                placed != null ? placed.toShortString() : "the step");
        dropLost = false;
        dropLostAtMs = 0;
        if (!journalBacked) {
            repackWork = new com.porter.wesort.inventory.PhysicalWork("Shulker", System.nanoTime());
            if (handle != null || job == Job.Clean || job == Job.Fill) endHandling();
        }
    }

    /**
     * Wall-clock watchdog for a resume or STORE wait: the tick counters freeze behind the
     * recovery gates, so RESUME_MAX_TICKS never fires there. After ten minutes with nothing
     * verified the shulker step is abandoned loudly, saying what was awaited. While waiting,
     * what is awaited is repeated every minute, not just once.
     */
    private void watchResumeStore() {
        // Without a world nothing can be awaited or abandoned: re-arm on return.
        if (mc.player == null || mc.level == null) {
            storeWatchMs = 0;
            return;
        }
        boolean watched = (resuming && handle != null)
                || (repackWork != null
                        && !repackWork.terminal()
                        && repackWork.phase() == com.porter.wesort.inventory.PhysicalWork.Phase.STORE);
        if (!watched) {
            storeWatchMs = 0;
            return;
        }
        if (storeWatchMs == 0) {
            storeWatchMs = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - storeWatchMs < RESUME_MAX_TICKS_MS) {
            if (clock - lastResumeLogClock >= 1200) {
                lastResumeLogClock = clock;
                step("resuming: " + resumeWhat() + "; next: automatic recovery or resume.");
            }
            return;
        }
        storeWatchMs = 0;
        if (resuming)
            abandonShulker("Couldn't finish the shulker step at "
                    + (placed == null ? "the saved place" : placed.toShortString()) + " within 10 minutes (awaited "
                    + resumeWhat() + ").");
        else
            abandonShulker("The stored shulker was not verified within 10 minutes (awaited STORE verification"
                    + (placed == null ? "" : " at " + placed.toShortString()) + ").");
    }

    /** What a stuck resume or STORE wait is waiting on, for alerts and the status line. */
    private String resumeWhat() {
        Stash.InFlight r = pendingResume;
        if (r != null
                && !r.dimension.isEmpty()
                && mc.level != null
                && !r.dimension.equals(mc.level.dimension().toString()))
            return "the shulker at " + r.placed.toShortString() + " is in another dimension";
        if (handle != null && placed != null) return handle.name() + " at " + placed.toShortString();
        if (handle != null) return handle.name();
        if (repackWork != null)
            return "STORE verification (" + repackWork.phase().name() + ")";
        return "the saved shulker step";
    }

    /**
     * Read-only surveys while the physical journal (but nothing else) blocks the loop: a
     * repack wait must not freeze re-reads. Drives a Survey job already in hand, or starts
     * one; anything physical (including resume) stays parked until recovery. Never touches a
     * foreign screen or menu.
     * @return true when the tick was consumed by a survey wait
     */
    private boolean surveyWhileBlocked() {
        if (PhysicalJournalRuntime.inspecting() || portalAwaiting()) return false;
        if (!InventorySync.ready() || com.porter.wesort.compat.NavigationRuntime.paused()) return false;
        if (stash == null || stash.recoveredBox != null || handle != null) return false;
        ticks++;
        if (wait > 0) {
            wait--;
            return false;
        }
        if (mc.player == null || mc.level == null) return false;
        if (job == null && (mc.player.containerMenu != mc.player.inventoryMenu || mc.screen != null)) return false;
        StashYield.Reason foreign = yielder.check(StashManager.ownsClient());
        if (foreign != null) return false;
        if (job != null) {
            if (job != Job.Survey || target == null || step == null) return false;
            stepTicks++;
            if (++jobTicks > SURVEY_WATCHDOG_TICKS) {
                Box stuck = target;
                releaseGoal();
                if (BARITONE) BaritoneHelper.unconfine();
                surveyFailed(
                        stuck,
                        "reading it took " + jobTicks / 20 + "s behind the recovery wait (stuck in step " + step + ")");
                finishJob();
                return true;
            }
            switch (step) {
                case Walking -> tickWalking();
                case Opening -> tickOpening();
                case Working -> tickWorking();
            }
            return true;
        }
        releaseDueSurveys();
        applyStale();
        queueSurveyBatch(true, false);
        return startSurveyBatch(false);
    }

    /** A container that a survey would actually read: known filters live with the jobs. */
    private boolean readableBox(Box box) {
        return !box.excluded
                && !box.foreign
                && !failedSurvey.contains(box)
                && (!box.unreachable || surveyRetry.failures(box) > 0);
    }

    /** Containers skipped after failed reads, waiting for their retry time. For the heartbeat. */
    public int failedSurveyCount() {
        return failedSurvey.size();
    }

    /** Containers a survey would actually read now. For the rescan blocker. */
    public int readableCount() {
        if (stash == null) return 0;
        return (int) stash.boxes.stream().filter(this::readableBox).count();
    }

    /** Why the bot is away from the stash, or null. For the Manager's alerts and blockers. */
    public String awayReason() {
        return notInStashWorld();
    }

    /**
     * What the yield gate is waiting on, with the quiet remaining: for the status line and
     * the Manager heartbeat. Null while running.
     */
    public String gateStatus() {
        if (gate == Gate.Waiting) {
            StashYield.Reason reason = yielder.lastReason();
            long quiet = yielder.quietRemainingTicks() / 20;
            return "WAITING " + (reason == null ? "cursor" : reason.name())
                    + (quiet > 0 ? " quiet " + quiet + "s" : "");
        }
        if (gate == Gate.Yielding) {
            StashYield.Reason reason = yielder.lastReason();
            return "YIELDING " + (reason == null ? "cursor" : reason.name());
        }
        return null;
    }

    private void verifyRepackStored() {
        if (stash == null
                || repackWork == null
                || repackWork.phase() != com.porter.wesort.inventory.PhysicalWork.Phase.STORE
                || mc.player == null) {
            repackVerifyReads = 0;
            repackVerifySeen.clear();
            return;
        }
        if (storeWatchMs == 0) storeWatchMs = System.currentTimeMillis();
        long carriedCopies =
                repackInventory().stream().filter(this::repackMatches).count();
        if (carriedCopies > repackExistingCopies) {
            // More identical carried stock than when storage began (a re-pickup, a drop
            // collected): re-arm the stored counts against the current world, but keep the
            // original baseline — a transient pickup that is later put away normally must
            // not trip the guard below. Just log it for diagnostics.
            debug(
                    "repack verify: carried identical copies grew from %d to %d, recounting the stored baseline.",
                    repackExistingCopies, carriedCopies);
            for (var box : stash.boxes)
                if (box.known() && com.porter.wesort.compat.ObservationRuntime.current(box))
                    repackStoreBefore.put(box.parts.toString(), repackCount(box.contents));
            return;
        }
        if (carriedCopies < repackExistingCopies) {
            blockRepack("Other identical carried stock changed during storage verification");
            return;
        }
        int readable = 0, verified = 0;
        for (var box : stash.boxes) {
            if (!readableBox(box)) continue;
            readable++;
            if (!(box.known()
                    && box.lastRead >= repackStoreAt
                    && com.porter.wesort.compat.ObservationRuntime.current(box))) continue;
            verified++;
            repackVerifySeen.add(box.parts.toString());
            if (repackCount(box.contents) > repackStoreBefore.getOrDefault(box.parts.toString(), Integer.MAX_VALUE)) {
                repackVerifyReads = 0;
                repackVerifySeen.clear();
                storeWatchMs = 0;
                PhysicalJournalRuntime.complete();
                if (!PhysicalJournalRuntime.blocked())
                    repackWork.enter(com.porter.wesort.inventory.PhysicalWork.Phase.COMPLETED, System.nanoTime());
                return;
            }
        }
        final int verifiedBoxes = verified;
        final int readableBoxes = readable;
        stepProgress("repack-verify", verifiedBoxes, Math.max(1, readableBoxes), () -> {
            StringBuilder pending = new StringBuilder();
            if (readableBoxes - verifiedBoxes <= 5 && readableBoxes - verifiedBoxes > 0) {
                for (var box : stash.boxes) {
                    if (!readableBox(box)
                            || (box.known()
                                    && box.lastRead >= repackStoreAt
                                    && com.porter.wesort.compat.ObservationRuntime.current(box))) continue;
                    if (pending.length() > 0) pending.append(", ");
                    pending.append(xyz(box.pos));
                }
            }
            return String.format(
                    "verifying storage: re-read %d of %d containers%s, carrying %d of %d expected stacks; next: finish the pass.",
                    verifiedBoxes,
                    readableBoxes,
                    pending.length() == 0 ? "" : " (still waiting on " + pending + ")",
                    carriedCopies,
                    repackExistingCopies);
        });
        // Every readable box re-read since storage began without a match is one full pass.
        // After a few the stored shulker is declared missing, fail closed with the cause.
        if (readable > 0 && repackVerifySeen.size() >= readable) {
            repackVerifySeen.clear();
            if (++repackVerifyReads >= MAX_REPACK_VERIFY_PASSES) {
                repackVerifyReads = 0;
                blockRepack("re-read every container " + MAX_REPACK_VERIFY_PASSES
                        + " times without finding the stored shulker");
            }
        }
    }

    private boolean repackPrecondition(String why) {
        repackWork.failBeforeMutation(why);
        pauseShulkers(why);
        return false;
    }

    private void blockRepackConflict(String why) {
        repackConflict = why;
        syncInFlight();
        blockRepack(why);
    }

    private void blockRepack(String why) {
        repackBlockedProof = PhysicalJournalRuntime.recoveryProof();
        if (repackWork == null
                || repackWork.terminal()
                        && repackWork.phase() != com.porter.wesort.inventory.PhysicalWork.Phase.NEEDS_RECONCILIATION)
            repackWork = new com.porter.wesort.inventory.PhysicalWork("Shulker", System.nanoTime());
        repackWork.reconcile(why);
        PhysicalJournalRuntime.block(why);
        dropLost = true;
        dropLostAtMs = System.currentTimeMillis();
        storeWatchMs = 0;
        long now = System.currentTimeMillis();
        if (!why.equals(lastRepackAlert) || now - lastRepackAlertAt >= 60_000) {
            lastRepackAlert = why;
            lastRepackAlertAt = now;
            alertError("Shulker operation waiting for recovery: %s. No unverified physical retry is sent.", why);
        }
    }

    private void endHandling() {
        if (repackWork != null && !repackWork.terminal() && (job == Job.Clean || job == Job.Fill)) {
            if (handle == Handle.Leave)
                repackWork.enter(com.porter.wesort.inventory.PhysicalWork.Phase.SELECT, System.nanoTime());
            else if (repackWork.phase() != com.porter.wesort.inventory.PhysicalWork.Phase.STORE && placed == null) {
                repackWork.failBeforeMutation("No safe shulker operation started");
                PhysicalJournalRuntime.endUnstarted();
            }
        }
        resetWander();
        // A shulker cleaned or filled is real headway: the counters start again
        if (job == Job.Clean || job == Job.Fill) {
            passJobs = 0;
            boxVisits.clear();
            stalled.removeAll(circular);
            circular.clear();
        }
        if (job == Job.CraftUnpack) {
            int now = collectedSlot >= 0 ? collectedSlot : handledSlot;
            crafter.relocated(handledOrigin, now);
            noteCollected(handledOrigin, now);
        }
        if (job == Job.KitFill || job == Job.KitUnpack) {
            if (placedWrong) {
                // The wrong shulker, picked back up: don't adopt it as the kit base. Reject where it
                // came from so it isn't fetched again, and leave it to be put back where it came from
                // by the leftover pass before the kit tries again.
                placedWrong = false;
                kitBuilder.rejectLastFetch();
                kitBuilder.suspend("took the wrong shulker, putting it back");
                noteCollected(handledOrigin, collectedSlot >= 0 ? collectedSlot : handledSlot);
            } else {
                kitBuilder.handled(job == Job.KitFill, collectedSlot >= 0 ? collectedSlot : handledSlot);
                // The shulker picked up again: an unpacked one goes back to the stash in scan-only mode
                noteCollected(handledOrigin, collectedSlot >= 0 ? collectedSlot : handledSlot);
            }
        }
        handle = null;
        placed = null;
        placedConfirmed = false;
        placedWrong = false;
        com.porter.wesort.compat.InventorySync.clearPlacementExpectation();
        shulkersAtBreak = -1;
        breakRetries = 0;
        brokenSince = -1;
        lastOwnAct = ticks;
        restoreHotbar();
        if (pickaxeSwapped) {
            InvUtils.swapBack();
            pickaxeSwapped = false;
        }
        syncedHandle = null;
        syncedPlaced = null;
        syncedConfirmed = false;
        verifiedReturnStack = ItemStack.EMPTY;
        returningChangedShulker = false;
        resumeFinishOnly = false;
        if (resuming) finishResume();
        else syncInFlight();
        recoveringOrphan = false;
        syncHeld();
        if (stash.isDirty()) stash.save();
        job = null;
        wait = delay.get();
    }

    /**
     * A shulker that could not be broken or picked up, or walked back to: said loudly, kept in the stash file and the
     * status with what it holds and where, and no more shulkers are put down until sorting is restarted. Sorting carries on.
     */
    private void abandonShulker(String why) {
        if (repackWork != null && !repackWork.terminal()) {
            repackBlockedProof = PhysicalJournalRuntime.recoveryProof();
            repackWork.reconcile(why);
        }
        Compactor.Plan plan = compactor.plan;
        BlockPos at = placed != null ? placed : plan != null ? plan.placedPos : null;
        String what = placedName();
        int stacks = plan != null ? plan.placedSize : placedStacks;
        alertError(
                "%s It holds %s of %s. New shulker work waits for verified recovery.",
                why, stacks > 0 ? stacks + " stacks" : "some stacks", what);
        if (at != null) {
            recordOrphan(at, what, stacks);
            if (job == Job.Recover) recoveryRetryAt.put(at, ticks + 20 * 60 * 5);
        }
        dropLost = true;
        dropLostAtMs = System.currentTimeMillis();
        compactor.drop();
        if (isKitJob()) kitBuilder.suspend(why);
        if (job == Job.CraftUnpack) crafter.abort(why);
        endHandling();
    }

    /**
     * Puts the player's hotbar item back where it was, once no screen is open and nothing is on the cursor, if it's still
     * where the move left it. Nothing is moved otherwise: a stale record is dropped.
     */
    private void restoreHotbar() {
        if (swapBackFrom == -1) return;
        if (mc.player == null
                || mc.screen != null
                || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()) return;

        int from = swapBackFrom, to = swapBackTo;
        ItemStack displaced = hotbarDisplaced;
        swapBackFrom = swapBackTo = -1;
        hotbarDisplaced = ItemStack.EMPTY;

        if (displaced.isEmpty() || !ItemStack.matches(mc.player.getInventory().getItem(from), displaced)) return;
        ItemStack hot = mc.player.getInventory().getItem(to);
        // Into the free slot, or in place of the shulker still there (which goes back to the carry slot)
        if (hot.isEmpty() || Utils.isShulker(hot.getItem()))
            InvUtils.move().from(from).to(to);
    }

    // The offline safety net

    /**
     * The stacks carried from the stash go into the stash file with it, so they are still known as the stash's after a
     * restart.
     */
    private void syncHeld() {
        if (stash == null) return;
        boolean same = stash.held.size() == stashHeld.size();
        if (same) {
            for (Map.Entry<Integer, ItemStack> entry : stashHeld.entrySet()) {
                ItemStack saved = stash.held.get(entry.getKey());
                if (saved == null || !ItemStack.matches(saved, entry.getValue())) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        stash.held.clear();
        for (Map.Entry<Integer, ItemStack> entry : stashHeld.entrySet())
            stash.held.put(entry.getKey(), entry.getValue().copy());
        stash.markDirty();
    }

    /**
     * What the stash file said we were carrying is carried again, where the inventory still holds exactly that.
     */
    private void restoreHeld() {
        if (stash == null || mc.player == null) return;
        for (Map.Entry<Integer, ItemStack> entry : stash.held.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= 36) continue;
            ItemStack there = mc.player.getInventory().getItem(slot);
            if (!there.isEmpty() && ItemStack.isSameItemSameComponents(there, entry.getValue()))
                stashHeld.putIfAbsent(slot, there.copy());
        }
    }

    /**
     * Where a shulker of ours stands in the world right now, or null.
     */
    private BlockPos standingShulker() {
        Compactor.Plan plan = compactor.plan;
        if (handle != null && placed != null && job != null) return placed;
        if (plan != null && plan.placedPos != null) return plan.placedPos;
        return null;
    }

    private static String blockId(net.minecraft.world.level.Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock())
                .getPath();
    }

    private Stash.InFlight buildInFlight() {
        BlockPos at = standingShulker();
        if (at == null) return null;

        Compactor.Plan plan = compactor.plan;
        boolean live = handle != null && placed != null && job != null;
        Stash.InFlight f = new Stash.InFlight();
        f.savedAt = System.currentTimeMillis();
        f.dimension = mc.level != null ? mc.level.dimension().toString() : cachedDimension;
        f.job = live ? job.name() : Job.Fill.name();
        f.handle = live ? handle.name() : Handle.Leave.name();
        f.returnTo = live && returnTo != null ? returnTo.name() : "";
        f.placed = at;
        f.confirmed = live ? placedConfirmed : true;
        f.placedItemId = mc.level != null && shulkerAt(at) ? blockId(mc.level, at) : placedItemId;
        f.placedStacks = plan != null ? plan.placedSize : placedStacks;
        f.fillSample =
                plan != null ? plan.sample.copy() : live && fillKey != null ? fillSample.copy() : ItemStack.EMPTY;
        f.repackConflict = repackConflict;
        f.verifiedReturnStack = verifiedReturnStack.copy();
        f.verifiedReturnCopies = repackExistingCopies;
        f.cleanMainId = live && job == Job.Clean && mainItem != null
                ? BuiltInRegistries.ITEM.getKey(mainItem).getPath()
                : "";
        f.lastGoodStand = lastGoodStand;
        f.target = target == null ? null : target.pos;
        f.compact = compactor.snapshot();
        return f;
    }

    /**
     * Writes the state of the shulker step in hand into the stash file (or removes it when none is standing), together
     * with what is carried. Called at every change that matters, so the file is never behind by more than a tick.
     */
    private void syncInFlight() {
        syncInFlight(false);
    }

    /**
     * {@code leaving}: the world may already be gone, so it is written from the sorter's own fields and the dimension
     * and registries remembered earlier.
     */
    private void syncInFlight(boolean leaving) {
        if (stash == null || (!leaving && (mc.level == null || mc.player == null))) return;
        // A record waiting to be finished is not ours to rewrite until we are at it
        if (pendingResume != null && !resuming) return;

        syncHeld();
        Stash.InFlight record = buildInFlight();
        if (record == null && stash.inFlight == null) return;
        stash.inFlight = record;
        stash.markDirty();
        stash.save();
    }

    private static Item itemByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(item).getPath().equals(path)) return item;
        }
        return null;
    }

    private List<Box> staleBoxes() {
        if (stash == null) return List.of();
        return stash.boxes.stream()
                .filter(b -> b.stale && !b.excluded && !b.foreign && !b.unreachable && !failedSurvey.contains(b))
                .toList();
    }

    /**
     * A saved shulker step is picked up again: the shulker is walked to, checked to be the one we put down, and worked
     * with where it stands. Before anything else is done.
     * @return true when the step has begun
     */
    private boolean startResume() {
        Stash.InFlight r = pendingResume;
        if (r == null || stash == null || resuming) return false;
        if (!r.verifiedReturnStack.isEmpty()) return resumeObservedReturn(r);
        if (Job.Recover.name().equals(r.job)) return beginOrphanRecovery(r);

        // A crash may have left an old kit/unpack job in flight after its order
        // was cancelled. Collect the box intact instead of resuming mutations.
        if (r.placed != null && stash.hasArea() && stash.inArea(r.placed)) {
            String active = orderRunner.running() && !orderRunner.calledOff() ? orderRunner.orderId() : null;
            boolean cancelled = stash.orphans.stream()
                    .anyMatch(o -> o.pos.equals(r.placed)
                            && !o.orderId.isEmpty()
                            && (o.dimension.isEmpty() || Stash.sameDimension(o.dimension, Stash.currentDimension()))
                            && PlacedShulkerRecovery.shouldRecover(o.orderId, active));
            if (cancelled) {
                r.job = Job.Recover.name();
                r.handle = Handle.Resume.name();
                r.compact = null;
                stash.inFlight = r;
                stash.markDirty();
                stash.save();
                return beginOrphanRecovery(r);
            }
        }

        String here = mc.level.dimension().toString();
        if (!r.dimension.isEmpty() && !r.dimension.equals(here)) {
            if (!resumeDimensionSaid) {
                resumeDimensionSaid = true;
                resumeDimLogTick = ticks;
                alert(
                        "A shulker I left at %s is in another dimension, it will be finished when I'm back there. No shulkers are put down until then.",
                        r.placed.toShortString());
            } else if (ticks - resumeDimLogTick >= 1200) {
                resumeDimLogTick = ticks;
                step(String.format(
                        "resuming: waiting to be back in %s for the shulker at %s; next: automatic recovery or resume.",
                        r.dimension, r.placed.toShortString()));
            }
            return false;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) return false;

        // What the shulker is for, rebuilt: a compaction, a packing of one item, a cleaning, or only to be picked up
        Compactor.Plan plan = r.compact != null ? compactor.restore(r.compact, r.dimension) : null;
        Job rebuilt = Job.Fill;
        BulkKey key = null;
        boolean finishOnly = false;
        if (plan != null) key = plan.key;
        else if (r.job.equals(Job.Clean.name()) && itemByPath(r.cleanMainId) != null) {
            rebuilt = Job.Clean;
            mainItem = itemByPath(r.cleanMainId);
        } else if (r.job.equals(Job.Fill.name()) && !r.fillSample.isEmpty() && keyOf(r.fillSample) != null)
            key = keyOf(r.fillSample);
        else finishOnly = true;
        if (plan == null && rebuilt == Job.Fill && key == null) finishOnly = true;

        resuming = true;
        resumeStarted = ticks;
        resumeVerify = true;
        resumeFinishOnly = finishOnly;
        startHandling(rebuilt, -1, key);
        fillSample = r.fillSample.copy();
        placed = r.placed;
        placedConfirmed = true;
        placedItemId = r.placedItemId;
        placedStacks = r.placedStacks;
        lastGoodStand = r.lastGoodStand;
        returnTo = r.handle.equals(Handle.Collect.name()) ? Handle.Collect : Handle.Open;
        handle = Handle.Resume;
        handleTicks = 0;

        String what = plan != null
                ? plan.name
                : rebuilt == Job.Clean && mainItem != null
                        ? new ItemStack(mainItem).getItemName().getString()
                        : fillSample.isEmpty() ? "items" : StackInfo.label(fillSample);
        int unread = (int) stash.boxes.stream()
                .filter(b -> !b.excluded && !b.foreign && (b.stale || !b.known()))
                .count();
        step(String.format(
                "resuming: shulker of %s (%d stacks) at %s; next: %s, then re-read %d containers.",
                what,
                r.placed == null ? 0 : (plan != null ? plan.placedSize : r.placedStacks),
                xyz(r.placed),
                returnTo == Handle.Collect
                        ? "pick it up, store it"
                        : finishOnly
                                ? "break and pick it up, store it"
                                : "open it, finish packing, break and pick it up, store it",
                unread));
        return true;
    }

    /**
     * The first step of a resumed shulker: get to where it stands, make sure it is the one we put down, then on to
     * opening it (or picking it up, if it was already broken).
     */
    private boolean resumeObservedReturn(Stash.InFlight r) {
        if (r.placed == null
                || !Stash.sameDimension(r.dimension, Stash.currentDimension())
                || mc.player.containerMenu != mc.player.inventoryMenu) return false;
        if (!beginOrphanRecovery(r)) return false;
        returningChangedShulker = true;
        verifiedReturnStack = r.verifiedReturnStack.copy();
        repackOuter = verifiedReturnStack.copy();
        repackExpected = com.porter.wesort.compat.RepackRuntime.signature(repackOuter);
        repackExistingCopies = r.verifiedReturnCopies;
        repackConservation = null;
        handlingProof = true;
        if (Job.Clean.name().equals(r.job) || Job.Fill.name().equals(r.job)) {
            job = Job.Fill;
            repackWork = new com.porter.wesort.inventory.PhysicalWork("ReturnChangedShulker", System.nanoTime());
        }
        return true;
    }

    private void handleResume() {
        Stash.InFlight r = pendingResume;
        if (r == null || placed == null) {
            endHandling();
            return;
        }

        // The chunk is not there yet: walk to where we last stood
        if (!mc.level.isLoaded(placed)) {
            if (handleTicks == 1 || handleTicks % 100 == 0) {
                if (walkOn()) walkNear(lastGoodStand != null ? lastGoodStand : placed, 1);
            }
            return;
        }

        if (!shulkerAt(placed)) {
            if (returningChangedShulker) {
                // Without the standing source, identical pre-existing copies cannot
                // be attributed to this pickup after a restart.
                if (repackExistingCopies > 0) {
                    blockRepackConflict(
                            "Cannot identify the changed shulker among pre-existing identical copies after restart");
                    return;
                }
                shulkerSlotsBefore.clear();
                handle = Handle.Collect;
                handleTicks = 0;
                return;
            }
            if (job == Job.Recover) {
                blockRepackConflict(
                        "Recovered shulker is no longer standing and its pickup has no verified checkpoint");
                return;
            }
            // Broken since: on the ground nearby, or already in the inventory, or gone
            if (shulkerItemNearby(8)) {
                shulkersBefore = countShulkers() + 1;
                shulkerSlotsBefore.clear();
                for (int i = 0; i < 36; i++)
                    if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
                handle = Handle.Collect;
                handleTicks = 0;
                return;
            }
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (Utils.isShulker(stack.getItem())
                        && StackInfo.of(stack).contents().size() == r.placedStacks
                        && r.placedStacks > 0) {
                    info("The shulker I left at %s is back in my inventory.", placed.toShortString());
                    compactor.drop();
                    clearOrphan(placed);
                    endHandling();
                    return;
                }
            }
            alert("The shulker I left at %s is gone and I can't find it. Forgetting it.", placed.toShortString());
            compactor.drop();
            clearOrphan(placed);
            endHandling();
            return;
        }

        // Never break a shulker that is not the one we put down
        String id = blockId(mc.level, placed);
        if (!r.placedItemId.isEmpty() && !id.equals(r.placedItemId)) {
            if (returningChangedShulker) {
                blockRepackConflict("Changed shulker was replaced before verified return at " + placed.toShortString());
                return;
            }
            alert(
                    "The shulker box at %s is a %s, not the %s I put down, leaving it alone.",
                    placed.toShortString(), id, r.placedItemId);
            compactor.drop();
            clearOrphan(placed);
            dropLost = true;
            dropLostAtMs = System.currentTimeMillis();
            endHandling();
            return;
        }

        shulkersBefore = countShulkers() + 1;
        shulkerSlotsBefore.clear();
        handleTicks = 0;
        if (returnTo == Handle.Collect) {
            // It was being broken: the block is still here, so it is opened and checked, then broken again
            resumeFinishOnly = true;
        }
        handle = Handle.Return;
        returnTo = Handle.Open;
    }

    /**
     * The shulker that was opened does not hold what the record says it should: nothing is done to it.
     */
    private String resumeContentsProblem(AbstractContainerMenu menu) {
        Compactor.Plan plan = compactor.plan;
        BulkKey key = plan != null ? plan.key : fillKey;
        boolean any = false, main = false;
        for (int i = 0; i < 27; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) continue;
            any = true;
            if (job == Job.Clean && mainItem != null && s.is(mainItem)) main = true;
            if (job != Job.Clean && key != null && !key.equals(keyOf(s))) return StackInfo.label(s) + " is in it";
        }
        if (job == Job.Clean && mainItem != null && any && !main)
            return "none of it is " + new ItemStack(mainItem).getItemName().getString();
        return null;
    }

    private void finishResume() {
        resuming = false;
        resumeVerify = false;
        resumeFinishOnly = false;
        pendingResume = null;
        if (stash != null) {
            stash.inFlight = null;
            stash.markDirty();
            if (mc.level != null) stash.save();
        }
        int unread = stash == null
                ? 0
                : (int) stash.boxes.stream()
                        .filter(b -> !b.excluded && !b.foreign && (b.stale || !b.known()))
                        .count();
        step(String.format("resume finished; next: re-read %d containers.", unread));
    }

    // Shulkers standing in the world are kept in the stash file until they are picked up

    private void recordOrphan(BlockPos at, String item, int stacks) {
        if (stash == null) return;
        Stash.Orphan previous = stash.orphans.stream()
                .filter(o -> o.pos.equals(at)
                        && (o.dimension.isEmpty() || Stash.sameDimension(o.dimension, Stash.currentDimension())))
                .findFirst()
                .orElse(null);
        // Only a new placement establishes ownership; later verification/recovery keeps it.
        // A previous occupant at the same position must not lend an old order ID to this one.
        String owner = handle == Handle.Place
                ? orderRunner.running() && !orderRunner.calledOff() ? orderRunner.orderId() : ""
                : previous == null ? "" : previous.orderId;
        String id =
                placedItemId.isEmpty() && mc.level.isLoaded(at) && shulkerAt(at) ? blockId(mc.level, at) : placedItemId;
        stash.orphans.removeIf(o -> o.pos.equals(at)
                && (o.dimension.isEmpty() || Stash.sameDimension(o.dimension, Stash.currentDimension())));
        stash.orphans.add(new Stash.Orphan(
                at,
                item,
                stacks,
                previous != null
                        ? previous.when
                        : java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                Stash.currentDimension(),
                owner,
                previous != null && !previous.blockId.isEmpty() ? previous.blockId : id));
        stash.markDirty();
        stash.save();
    }

    private void clearOrphan(BlockPos at) {
        if (stash == null || at == null) return;
        if (stash.orphans.removeIf(o -> o.pos.equals(at)
                && (o.dimension.isEmpty() || Stash.sameDimension(o.dimension, Stash.currentDimension())))) {
            stash.markDirty();
            stash.save();
        }
    }

    /**
     * Reconcile all shulker block entities in loaded perimeter chunks. Unknown boxes are drop-offs;
     * order ownership is only attached when our own placement is recorded, not merely because
     * another player placed a box while an order happened to be running.
     *
     * Unloaded chunks and still-pending placements are never treated as removed.
     */
    private void observePlacedShulkers() {
        if (stash == null || !stash.hasArea() || mc.level == null) return;
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        String dimension = Stash.currentDimension();
        boolean changed = false;
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                for (BlockEntity entity :
                        mc.level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(entity instanceof ShulkerBoxBlockEntity)) continue;
                    BlockPos pos = entity.getBlockPos();
                    if (!stash.inArea(pos) || !shulkerAt(pos)) continue;
                    if (stash.orphans.stream()
                            .anyMatch(o -> o.pos.equals(pos)
                                    && (o.dimension.isEmpty() || Stash.sameDimension(o.dimension, dimension))))
                        continue;
                    stash.orphans.add(new Stash.Orphan(
                            pos.immutable(),
                            "shulker box",
                            0,
                            java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                            dimension,
                            "",
                            blockId(mc.level, pos)));
                    changed = true;
                    info("Discovered placed shulker at %s; queued for stash recovery.", pos.toShortString());
                }
            }
        }
        for (Stash.Orphan orphan : new ArrayList<>(stash.orphans)) {
            if (!orphan.dimension.isEmpty() && !Stash.sameDimension(orphan.dimension, dimension)) continue;
            // A temporarily missing block entity is not proof that the block is gone.
            if (!stash.inArea(orphan.pos) || !mc.level.isLoaded(orphan.pos) || shulkerAt(orphan.pos)) continue;
            if (placed != null && placed.equals(orphan.pos)
                    || pendingResume != null && orphan.pos.equals(pendingResume.placed)) continue;
            stash.orphans.remove(orphan);
            recoveryRetryAt.remove(orphan.pos);
            changed = true;
        }
        if (changed) {
            stash.markDirty();
            stash.save();
        }
        if (stash.orphans.stream()
                .anyMatch(o -> stash.inArea(o.pos)
                        && (o.dimension.isEmpty() || Stash.sameDimension(o.dimension, dimension))
                        && PlacedShulkerRecovery.shouldRecover(
                                o.orderId,
                                orderRunner.running() && !orderRunner.calledOff() ? orderRunner.orderId() : null)))
            idle = false;
    }

    /**
     * Schedule a physical recovery before other idle work. One box at a time is opened for
     * server-confirmed readback, broken, collected and then stored using the normal stash
     * return/deposit path. A live order keeps ownership of its own standing shulkers.
     */
    private boolean startOrphanRecovery() {
        if (stash == null
                || !stash.hasArea()
                || pendingResume != null
                || handle != null
                || compactor.active()
                || kitBuilder.active()
                || !freeInventorySlot()) return false;
        String active = orderRunner.running() && !orderRunner.calledOff() ? orderRunner.orderId() : null;
        for (Stash.Orphan orphan : new ArrayList<>(stash.orphans)) {
            if (!stash.inArea(orphan.pos)
                    || !orphan.dimension.isEmpty() && !Stash.sameDimension(orphan.dimension, Stash.currentDimension())
                    || !PlacedShulkerRecovery.shouldRecover(orphan.orderId, active)
                    || ticks < recoveryRetryAt.getOrDefault(orphan.pos, 0)
                    || !mc.level.isLoaded(orphan.pos)
                    || !shulkerAt(orphan.pos)) continue;

            String actualId = blockId(mc.level, orphan.pos);
            if (!orphan.blockId.isEmpty() && !actualId.equals(orphan.blockId)) {
                // The original box was replaced. Treat the replacement as a new drop-off,
                // never reuse ownership or colour evidence from the previous box.
                stash.orphans.remove(orphan);
                stash.orphans.add(new Stash.Orphan(
                        orphan.pos,
                        "shulker box",
                        0,
                        java.time.LocalDateTime.now().toString(),
                        Stash.currentDimension(),
                        "",
                        actualId));
                stash.markDirty();
                stash.save();
                return false;
            }
            Stash.InFlight recovery = new Stash.InFlight();
            recovery.job = Job.Recover.name();
            recovery.handle = Handle.Resume.name();
            recovery.dimension = Stash.currentDimension();
            recovery.placed = orphan.pos;
            recovery.confirmed = true;
            recovery.placedItemId = actualId;
            recovery.placedStacks = orphan.stacks;
            recovery.savedAt = System.currentTimeMillis();
            stash.inFlight = recovery;
            stash.markDirty();
            stash.save();
            pendingResume = recovery;
            return beginOrphanRecovery(recovery);
        }
        return false;
    }

    /** Resume an interrupted recovery without treating a foreign shulker as a kit or compaction. */
    private boolean beginOrphanRecovery(Stash.InFlight recovery) {
        if (recovery.placed == null
                || !stash.hasArea()
                || !stash.inArea(recovery.placed)
                || !Stash.sameDimension(recovery.dimension, Stash.currentDimension())
                || mc.player.containerMenu != mc.player.inventoryMenu
                || !freeInventorySlot()) return false;
        recoveringOrphan = true;
        recoveredContents = null;
        resuming = true;
        resumeStarted = ticks;
        resumeVerify = false;
        resumeFinishOnly = true;
        job = Job.Recover;
        target = null;
        placed = recovery.placed;
        placedConfirmed = true;
        placedItemId = recovery.placedItemId;
        placedStacks = recovery.placedStacks;
        lastGoodStand = recovery.lastGoodStand;
        fillKey = null;
        repackExpected = "";
        handlingProof = false;
        returnTo = Handle.Open;
        handle = Handle.Resume;
        handleTicks = 0;
        breakTicks = 0;
        brokenSince = -1;
        collectedSlot = -1;
        shulkersBefore = countShulkers() + 1;
        shulkerSlotsBefore.clear();
        step("recovering abandoned shulker at " + placed.toShortString()
                + "; next: verify, break, collect, store in stash.");
        return true;
    }

    /**
     * At startup, discard records for blocks confirmed gone; others are queued for automatic
     * collection rather than requiring an operator to pick them up.
     */
    private void checkOrphans() {
        String here = mc.level.dimension().toString();
        for (Stash.Orphan orphan : new ArrayList<>(stash.orphans)) {
            // One in another dimension is neither forgotten nor looked for from here
            if (!orphan.dimension.isEmpty() && !Stash.sameDimension(orphan.dimension, here)) continue;
            // The one a saved step is about to finish
            if (pendingResume != null && orphan.pos.equals(pendingResume.placed)) continue;
            if (mc.level.isLoaded(orphan.pos) && !shulkerAt(orphan.pos)) {
                stash.orphans.remove(orphan);
                stash.markDirty();
                info("The shulker I left at %s is gone.", orphan.pos.toShortString());
            } else {
                info(
                        "Placed shulker at %s (since %s) queued for recovery when its order is inactive.",
                        orphan.pos.toShortString(), orphan.when);
            }
        }
    }

    /**
     * A shulker of ours standing in the world when sorting stops: said loudly with what is in it and where.
     */
    private void reportPlacedShulker() {
        Compactor.Plan plan = compactor.plan;
        BlockPos at = null;
        if (handle != null && placed != null && mc.level != null && shulkerAt(placed)) at = placed;
        else if (plan != null && plan.placedPos != null) at = plan.placedPos;
        if (at == null) return;

        String what = placedName();
        int stacks = plan != null ? plan.placedSize : placedStacks;
        // Manager direction: report standing shulker as facts (pos + contents + stacks).
        WeSortAddon.LOG.warn(
                "WeSort standing shulker: pos={} what={} stacks={}.",
                at.toShortString(),
                what,
                stacks);
        // Saved, and finished the next time the sorter starts
        syncInFlight();
        if (stash != null && stash.inFlight != null) {
            alert("Stopped mid-job: shulker at %s kept, it will be finished on the next start.", at.toShortString());
            return;
        }
        alertError(
                "Stopped with a shulker holding %s of %s standing at %s. Pick it up by hand.",
                stacks > 0 ? stacks + " stacks" : "some stacks", what, at.toShortString());
        recordOrphan(at, what, stacks);
    }

    private boolean freeInventorySlot() {
        Set<Integer> keep = parseSlots(keepSlots.get());
        for (int i = 0; i < 36; i++)
            if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) return true;
        return false;
    }

    private int countShulkers() {
        int count = 0;
        for (int i = 0; i < 36; i++)
            if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) count++;
        if (Utils.isShulker(mc.player.getInventory().getItem(40).getItem())) count++;
        return count;
    }

    /**
     * A spot beside us that is safe for a shulker: open air on solid ground with room for the lid, not where a place
     * failed before, not a stash container, not on top of one (it would not open), not next to a hopper (it would be
     * emptied), not where a player or a mob stands.
     */
    private BlockPos safeSpot() {
        BlockPos feet = mc.player.blockPosition();
        Map<String, Integer> rejected = new HashMap<>();
        for (int dy = 0; dy >= -1; dy--) {
            for (Direction dir : HORIZONTALS) {
                BlockPos p = feet.relative(dir).above(dy);
                if (!BlockUtils.canPlace(p, true)) continue;
                if (!mc.level.getBlockState(p.above()).isAir()) continue;
                if (!mc.level.getBlockState(p.below()).isFaceSturdy(mc.level, p.below(), Direction.UP)) continue;

                String why = spotProblem(p);
                if (why == null) return p;
                debug("Not putting a shulker at %s: %s.", p.toShortString(), why);
                rejected.merge(why, 1, Integer::sum);
            }
        }
        if (!rejected.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (var entry : rejected.entrySet())
                text.append(text.isEmpty() ? "" : ", ")
                        .append(entry.getValue())
                        .append("x ")
                        .append(entry.getKey());
            info("No safe spot for a shulker beside me: %s.", text);
        }
        return null;
    }

    /**
     * Why a shulker mustn't be put at this spot, or null if it may.
     */
    private String unsafeBecause(BlockPos spot) {
        if (placementRetryAt.containsKey(spot)) return "waiting before retrying placement here";
        if (stash.boxAt(spot) != null) return "a stash container is there";
        // What is dropped there when the shulker is broken has to be walkable to: floor under it, and open on two sides
        // at least (our own side and one more), not a gap between containers
        BlockPos floor = spot.below();
        if (mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty())
            return "there is no floor under it";
        int open = 0;
        for (Direction d : HORIZONTALS) {
            BlockPos side = spot.relative(d);
            if (mc.level.getBlockState(side).getCollisionShape(mc.level, side).isEmpty() && stash.boxAt(side) == null)
                open++;
        }
        if (open < 2) return "it is boxed in, an item dropped there could not be reached";
        // Another player or a mob whose box, a little wider than it is, overlaps the spot's cell blocks the place. Not
        // the
        // player themselves, and not items, frames or armor stands
        if (!mc.level
                .getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(spot).inflate(0.3),
                        e -> e != mc.player && !(e instanceof ArmorStand))
                .isEmpty()) {
            return "a player or mob is standing in the way";
        }
        BlockPos below = spot.below();
        for (Box box : stash.boxes)
            if (box.has(below)) return "it is on top of the container at " + box.pos.toShortString();
        for (Direction d : Direction.values()) {
            if (mc.level.getBlockState(spot.relative(d)).is(Blocks.HOPPER)) return "there is a hopper next to it";
        }
        return null;
    }

    /** All the rules for a shulker (or an anvil) at this spot: the safe ones, away from the special blocks, inside the perimeter. */
    private String spotProblem(BlockPos spot) {
        String why = unsafeBecause(spot);
        if (why != null) return why;
        String special = nearSpecial(spot);
        if (special != null) return special;
        if (stash.hasArea() && !stash.inArea(spot)) return "it is outside the stash area";
        return null;
    }

    /** On or next to the delivery container, the anvil or the crafting table: those are walked up to and used. */
    private String nearSpecial(BlockPos spot) {
        for (Box box : stash.boxes) {
            if (!box.delivery) continue;
            for (BlockPos part : box.parts) if (touches(spot, part)) return "it is next to the delivery container";
        }
        if (stash.anvilPos != null && touches(spot, stash.anvilPos)) return "it is next to the anvil";
        if (stash.craftingTablePos != null && touches(spot, stash.craftingTablePos))
            return "it is next to the crafting table";
        return null;
    }

    private static boolean touches(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 1
                && Math.abs(a.getY() - b.getY()) <= 1
                && Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    // Walking to a spot for a block (a shulker, an anvil) when there is none beside us: PlaceSearch, as the plugin's
    private static final int WANDER_TRIES = 3, WANDER_TICKS = 20 * 20;
    private BlockPos wanderStand, wanderSpot;
    private int wanderTries, wanderStart, wanderRestarts;
    private final Set<PlaceSearch.Pos> wanderFailed = new HashSet<>();

    private static PlaceSearch.Pos pos(BlockPos p) {
        return new PlaceSearch.Pos(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos bp(PlaceSearch.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private void resetWander() {
        wanderTries = wanderRestarts = 0;
        wanderFailed.clear();
        wanderStand = wanderSpot = null;
    }

    /** The world as the placement search sees it. */
    private PlaceSearch.View placeView() {
        return new PlaceSearch.View() {
            private boolean empty(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState st = mc.level.getBlockState(p);
                return st.getCollisionShape(mc.level, p).isEmpty()
                        && st.getFluidState().isEmpty();
            }

            @Override
            public boolean open(int x, int y, int z) {
                return empty(x, y, z);
            }

            @Override
            public boolean floor(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                return mc.level.getBlockState(p).isFaceSturdy(mc.level, p, Direction.UP);
            }

            @Override
            public boolean standable(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                return empty(x, y, z)
                        && empty(x, y + 1, z)
                        && !mc.level
                                .getBlockState(p.below())
                                .getCollisionShape(mc.level, p.below())
                                .isEmpty()
                        && stash.boxAt(p) == null;
            }

            @Override
            public String unsafe(PlaceSearch.Pos spot) {
                return unsafeBecause(bp(spot));
            }

            @Override
            public boolean inArea(PlaceSearch.Pos p) {
                return !stash.hasArea() || stash.inArea(bp(p));
            }

            @Override
            public boolean guarded(PlaceSearch.Pos spot) {
                return nearSpecial(bp(spot)) != null;
            }
        };
    }

    /**
     * No spot beside us: looks for the nearest spot within 5 blocks that can be walked to, and starts walking to the cell
     * beside it. When the cell to stand in is where we stand, the spot is just used.
     * @return null when the walk started (or the spot is at hand), else why there is nothing to walk to
     */
    private String startWander() {
        if (!walkOn()) return "walking is off";
        if (wanderTries >= WANDER_TRIES) return "couldn't walk to one";
        PlaceSearch.Result found = PlaceSearch.search(pos(mc.player.blockPosition()), placeView(), wanderFailed);
        if (found.found() == null) return found.why();

        wanderTries++;
        wanderSpot = bp(found.found().spot());
        if (found.found().steps() == 0) return null;
        wanderStand = bp(found.found().stand());
        wanderRestarts = 0;
        info(
                "No spot for a shulker beside me, walking %d steps to the nearest one.",
                found.found().steps());
        walkNear(wanderStand, 1);
        wanderStart = handleTicks;
        handle = Handle.Wander;
        return null;
    }

    /** Walks to the cell beside the chosen spot; there, the block is put down at that spot. A shulker carried is never dropped. */
    private void handleWander() {
        if (wanderStand == null) {
            handle = Handle.Place;
            handleTicks = 0;
            return;
        }
        Vec3 at = mc.player.position();
        boolean arrived = at.distanceToSqr(Vec3.atBottomCenterOf(wanderStand)) <= 1.8 * 1.8;
        if (arrived) {
            releaseGoal();
            wanderStand = null;
            handle = Handle.Place;
            handleTicks = 0;
            wait = delay.get();
            return;
        }
        // A goal let go of while giving way is set again, a few times
        if (ourGoal == null) {
            if (++wanderRestarts > 3) {
                wanderGiveUp();
                return;
            }
            walkNear(wanderStand, 1);
            wanderStart = handleTicks;
            return;
        }
        boolean stopped = handleTicks - wanderStart > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (stopped || handleTicks - wanderStart > WANDER_TICKS) wanderGiveUp();
    }

    /** The walk did not get there: that spot is not tried again, and the next best is looked for. */
    private void wanderGiveUp() {
        releaseGoal();
        if (wanderSpot != null) wanderFailed.add(pos(wanderSpot));
        wanderStand = wanderSpot = null;
        handle = Handle.Place;
        handleTicks = 0;
    }

    /** Stay-on, or wesort-stash-manager is driving it: the sorter rests when it has nothing to do instead of turning itself off. */
    private boolean keepOn() {
        return stayOn.get() || driven;
    }

    private void finishSorting() {
        if (repackWork != null
                && !repackWork.terminal()
                && repackWork.phase() == com.porter.wesort.inventory.PhysicalWork.Phase.STORE) {
            blockRepack(
                    "Repacked shulker has not reached verified storage; inspect the held box and destination capacity");
            return;
        }
        // An order in hand keeps the sorter awake, even when it has to wait (paused, or for a saved kit)
        if (orderRunner.running()) {
            // The pass is over all the same: its counters start again, or the loop watchdog would fire on every tick
            boxVisits.clear();
            circular.clear();
            loopReported.clear();
            closedEarlyReported.clear();
            passJobs = 0;
            passMin = Integer.MAX_VALUE;
            wait = Math.max(wait, 20);
            return;
        }

        // A plan in hand: the shulker standing in the world is fetched back first, then what was gathered for it is put
        // away
        if (compactor.active()) {
            compactor.abort("sorting is finishing");
            if (compactor.next()) return;
            if (!activeCarried().isEmpty()) return;
        }

        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        closedEarlyReported.clear();
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        if (stash.isDirty()) stash.save();

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0
                ? String.format(" Couldn't open %d containers, they were left alone.", unreachable)
                : "";
        int packedRun = compactor.runPacked();
        extra += compactor.takeRunSummary();
        if (!stuckCarried.isEmpty())
            extra += String.format(" %d stacks I am carrying have nowhere to go.", stuckCarried.size());
        long leftInDropOffs = stash.boxes.stream()
                .filter(b -> b.dropOff && b.known() && !b.unreachable)
                .mapToLong(b -> b.contents.length - b.free())
                .sum();
        if (sorting() && leftInDropOffs > 0) reportBlockedDropOff();
        if (sorting() && leftInDropOffs > 0)
            extra += String.format(
                    " %d stacks are still in drop-offs, there's no room left in the stash.", leftInDropOffs);

        // Scan-only: one scan, then off, unless stay-on keeps it watching
        if (!sorting()) {
            if (!keepOn()) {
                info("Scan only: read %d containers, the saved stash is up to date.%s", scanned, extra);
                step(String.format("done: read %d containers; stopping.", scanned));
                scanned = 0;
                turnOff();
                return;
            }
            if (!driven && (scanned > 0 || !extra.equals(lastSummary))) {
                info(
                        "Scan only: read %d containers, the saved stash is up to date.%s Watching for changes.",
                        scanned, extra);
                step(String.format("idle: read %d containers; next: re-read a container when it changes.", scanned));
            }
            lastSummary = extra;
            scanned = 0;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = version;
            return;
        }

        if (keepOn()) {
            // Waking up for a drop-off check that finds nothing says nothing; a changed summary does
            if (moved > 0 || !extra.equals(lastSummary)) {
                info("Sorted, moved %d stacks.%s Waiting for more to sort.", moved, extra);
                step(String.format(
                        "idle: sorted, moved %d stacks; next: sort again when a container or the ground changes.",
                        moved));
            }
            lastSummary = extra;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = version;
            return;
        }

        info("Done, moved %d stacks.%s", moved, extra);
        step(String.format("done: moved %d stacks, packed %d shulkers; stopping.", moved, packedRun));
        turnOff();
    }

    // When the job in hand began, for the debug log
    private long jobStartedAt;

    private boolean start(Job next, Box box) {
        if (next != Job.Dump) startupDump = false;
        if (next == Job.Fetch)
            fetchExpected = box.contents != null && wantSlot >= 0 && wantSlot < box.contents.length
                    ? box.contents[wantSlot].copy()
                    : ItemStack.EMPTY;
        // A kit being built has its own limits: its trips are not what the circle watchdog counts
        // Repacking trips (fetching a shulker), compacting, surveys and kit builds don't shrink the backlog, so they
        // aren't
        // what the circle watchdog counts
        jobCounted = !kitBuilder.active()
                && next != Job.Fetch
                && next != Job.Compact
                && next != Job.Survey
                && next != Job.Gather;
        if (jobCounted) {
            passJobs++;
            boxVisits.merge(box, 1, Integer::sum);
        }
        startSigSet = endSigSet = false;
        settled = false;
        chained = 0;
        jobTicks = 0;
        jobStartedAt = System.nanoTime();
        debug(() -> String.format(
                "pickJob: %s on %s at %s, %.1f blocks away; backlog min %d, %d jobs this pass, %d containers stalled, %d carried.",
                next,
                home(box),
                xyz(nearestPart(box)),
                Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)))),
                passMin == Integer.MAX_VALUE ? -1 : passMin,
                passJobs,
                stalled.size(),
                carriedSlots().size()));
        job = next;
        target = box;
        swapStep = 0;
        actions = 0;
        recoveries = 0;
        closedEarlyTicks = 0;
        openPos = null;
        openMenuBefore = null;

        // Plainly not a container (air, no block entity in a loaded chunk): dropped at once, nothing is walked to. It
        // comes
        // back if the scan finds a container there. Any half counts for a double chest.
        if (next == Job.Survey && !box.delivery && anyPartLoaded(box) && inStashDimension()) {
            if (!anyContainerBlockHere(box)) {
                WeSortAddon.LOG.info(
                        "[wesort-stash-sorter] dropped the container at {}: there is no chest or barrel there any more.",
                        xyz(box.pos));
                step(String.format("skipped %s: there is no chest or barrel there; forgotten.", xyz(box.pos)));
                stash.boxes.remove(box);
                stash.markDirty();
                failedSurvey.remove(box);
                surveyRetry.succeed(box);
                job = null;
                target = null;
                return false;
            }
            if (!anyIsContainer(box)) {
                // A barrel with the barrels setting off: kept in the stash, only not read
                job = null;
                target = null;
                surveyFailed(box, "barrels are switched off in the settings");
                return false;
            }
        }

        if (inReach(box)) {
            step = Step.Opening;
        } else if (walkOn()) {
            walkReach(box);
            step = Step.Walking;
        } else {
            if (stash.hasArea() && !BARITONE) warnNoBaritone();
            box.unreachable = true;
            surveyFailed(box, "outside interaction range while walking is unavailable");
            job = null;
            target = null;
            return false;
        }

        stepTicks = 0;
        walkProgress.reset(0, walkDistance(box));
        announce(next, box);
        return true;
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** The actual container block, never nearby sign text. */
    private String home(Box box) {
        return blockName(box);
    }

    /**
     * Says what the job just started does and what comes after, from queries that change nothing.
     */
    private void announce(Job next, Box box) {
        try {
            switch (next) {
                case Survey -> {
                    int total =
                            (int) stash.boxes.stream().filter(b -> !b.excluded).count();
                    int done = (int) stash.boxes.stream()
                            .filter(b -> !b.excluded && b.known() && !b.stale)
                            .count();
                    Supplier<String> line = () -> String.format(
                            "reading containers: %d/%d; next: %s at %s.", done, total, blockName(box), xyz(box.pos));
                    // The last ones are always said, the bucketed progress could skip them
                    if (total - done <= 1) step(line.get());
                    else stepProgress("read", done, total, line);
                }
                case Take -> {
                    List<Box> homes = List.of();
                    int n = 0;
                    for (ItemStack stack : box.contents) {
                        if (stack.isEmpty() || !misplaced(stack, box)) continue;
                        n++;
                        if (homes.isEmpty())
                            homes = destinationsFor(stack).stream()
                                    .filter(b -> b != box)
                                    .toList();
                    }
                    n = Math.min(n, Math.max(1, freeCarryCount()));
                    Box nearest = homes.isEmpty() ? null : nearest(homes);
                    step(String.format(
                            "sorting: taking %d stacks from %s at %s; next: %s.",
                            n,
                            home(box),
                            xyz(box.pos),
                            nearest == null
                                    ? "carry on"
                                    : String.format(
                                            "put them away (%d containers, nearest %s)",
                                            homes.size(), xyz(nearest.pos))));
                }
                case Deposit, Dump -> {
                    List<Integer> carried = activeCarried();
                    int n = 0;
                    Set<Box> others = new LinkedHashSet<>();
                    for (int slot : carried) {
                        List<Box> homes =
                                destinationsFor(mc.player.getInventory().getItem(slot));
                        if (homes.contains(box) || next == Job.Dump) n++;
                        for (Box other : homes) if (other != box && !blocked(other, Job.Deposit)) others.add(other);
                    }
                    Box source = next == Job.Dump
                            ? null
                            : nearest(stash.boxes.stream()
                                    .filter(b ->
                                            b.sortable() && !blocked(b, Job.Take) && b != box && takeableSlot(b) != -1)
                                    .toList());
                    String after = others.isEmpty() ? "" : others.size() + " more stops, then ";
                    step(String.format(
                            "sorting: putting %d stacks into %s at %s; next: %s.",
                            Math.max(n, 1),
                            home(box),
                            xyz(box.pos),
                            source != null
                                    ? after + "take from " + xyz(source.pos)
                                    : others.isEmpty() ? "carry on" : others.size() + " more stops"));
                }
                case Compact ->
                    step(String.format(
                            "sorting: merging partial stacks in %s at %s; next: carry on.", home(box), xyz(box.pos)));
                case Fetch -> {
                    if (orderWork()) {
                        step(String.format(
                                "order: fetching %s from %s at %s.",
                                kitBuilder.active() ? kitBuilder.fetchPurpose() : "the shulker the order asks for",
                                home(box),
                                xyz(box.pos)));
                    } else
                        step(String.format(
                                "repacking: fetching a mixed shulker from %s at %s; next: clean it beside the chest.",
                                home(box), xyz(box.pos)));
                }
                case Gather -> {
                    /* said by the compactor */
                }
                case CraftTake ->
                    step(String.format(
                            "crafting: taking ingredients from %s at %s; next: the crafting table.",
                            home(box), xyz(box.pos)));
                case KitTake ->
                    step(String.format(
                            orderKit()
                                    ? "order: taking items for the order from %s at %s; next: fill the shulker."
                                    : "kits: taking items from %s at %s; next: fill the kit shulker.",
                            home(box),
                            xyz(box.pos)));
                default -> {}
            }
        } catch (RuntimeException e) {
            WeSortAddon.LOG.debug("[wesort-stash-sorter] couldn't describe the job", e);
        }
    }

    private void finishJob() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (ourMenu(menu)) {
            if (!menu.getCarried().isEmpty()) {
                // A read-only survey behind a physical fence cannot move the cursor.
                if (sortingPermitted() && gate == Gate.Running) beginYield(null);
                return;
            }
            closeOurWindow(menu);
        }
        ownedMenu = null;
        lastOwnAct = ticks;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;

        // Clicks that changed neither the carry slots nor the container count for nothing: they never went through.
        // Clicks were made but the window went before the result could be read: an unconfirmed attempt, which is
        // neither
        // progress nor proof of none. A box is stalled after two of them in a row
        boolean confirmed = endSigSet && startSigSet;
        boolean unconfirmed = actions > 0 && !confirmed;
        boolean progressed = actions > 0 && confirmed && startSig != endSig;
        if (debugOn() && job != null && target != null) {
            debug(
                    "%s at %s took %d ms: %d actions, %s.",
                    job,
                    xyz(target.pos),
                    (System.nanoTime() - jobStartedAt) / 1_000_000,
                    actions,
                    progressed ? "progressed" : unconfirmed ? "unconfirmed" : "no progress");
        }

        // Opened it and got nothing done: not repeated until the carry slots or the container change
        if (actions == 0 && job != null && job != Job.Survey && target != null && !target.unreachable) {
            futile.put(futileKey(target, job), futileSig(target));
        }
        if (job == Job.Survey && target != null && target.known()) {
            scanned++;
            if (!target.stale) surveyRetry.succeed(target);
        }

        int visits = target == null ? 0 : boxVisits.getOrDefault(target, 0);
        if (jobCounted && visits >= 3 && job != Job.Survey && target != null && loopReported.add(target)) {
            debug(
                    "Container at %s has been the target of %d jobs without the backlog shrinking: last job %s, %d actions, %s, %s, carrying %d stacks.",
                    target.pos.toShortString(),
                    visits,
                    job,
                    actions,
                    confirmed ? "confirmed" : "unconfirmed",
                    progressed ? "changed something" : "changed nothing",
                    carriedSlots().size());
        }

        // Going back and forth between containers while the backlog doesn't shrink: given up on, however each job looks
        boolean circle = jobCounted && visits >= 4 && job != Job.Survey && target != null && !target.unreachable;
        if (circle) {
            circular.add(target);
            stalled.add(target);
        } else if (job == Job.Survey
                && target != null
                && (!target.unreachable || surveyRetry.failures(target) > 0)
                && (!target.known() || target.stale)) {
            surveyFailed(target, "the window closed before it could be read");
        } else if (job != null && job != Job.Survey && target != null && !target.unreachable) {
            if (unconfirmed) {
                if (unconfirmedRuns.merge(target, 1, Integer::sum) >= 2) stalled.add(target);
            } else if (!progressed) stalled.add(target);
            else if (progressed) {
                unconfirmedRuns.remove(target);
                clearStalled();
                stalled.addAll(circular);
                stuckCarried.clear();
                stuckWarned = false;
            }
        }

        startSigSet = endSigSet = false;

        if (job == Job.Return && stash.recoveredBox != null && target != null) returnRetry.failOnce(target, ticks);
        if (stash.isDirty()) stash.save();
        job = null;
        target = null;
        wait = delay.get();
    }

    // Steps

    /**
     * Distance from the eyes to the nearest part of the container, for telling whether a walk is getting closer.
     */
    private double walkDistance(Box box) {
        return Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box))));
    }

    private void tickWalking() {
        if (inReach(target)) {
            releaseGoal();
            step = Step.Opening;
            stepTicks = 0;
            return;
        }

        // A read does not spend long on a walk that is going nowhere (looping, blocked, no way in): the box is skipped
        // and tried again later, instead of the whole WALK_TIMEOUT or the watchdog
        if (job == Job.Survey) {
            walkProgress.update(stepTicks, walkDistance(target));
            // A chunk that isn't loaded can't be walked into by a path, so it gets half the time.
            // Any half of a double chest counts.
            if (walkProgress.stalled(
                    stepTicks, anyPartLoaded(target) ? SURVEY_WALK_STALL_TICKS : SURVEY_WALK_STALL_TICKS / 2)) {
                releaseGoal();
                surveyFailed(
                        target,
                        String.format(
                                "could not reach it (walk made no progress for %ds)",
                                walkProgress.idleTicks(stepTicks) / 20));
                finishJob();
                return;
            }
        }

        // Started inside the perimeter and now outside it: a way that leads out of the stash area isn't taken.
        // The delivery container and the input chest may be outside it, so walks to them are allowed out.
        if (walkInside
                && stash.hasArea()
                && target != null
                && !target.delivery
                && !target.input
                && !stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN)) {
            releaseGoal();
            reportUnreachable(target, Cause.Area);
            target.unreachable = true;
            surveyRetry.succeed(target);
            finishJob();
            return;
        }

        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        boolean ourGoalWasTaken = stopped
                && BARITONE
                && ourGoal != null
                && !BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) ourGoal);
        if (stopped || stepTicks > WALK_TIMEOUT) {
            releaseGoal();
            // Our path went away because somebody else cancelled or replaced it (nothing signalled it, but our goal is
            // no
            // longer the one Baritone has): not the container's fault, plan it again. A few times only, since a path
            // that couldn't be found clears the goal too
            boolean cancelled =
                    stopped && BARITONE && ourGoalWasTaken && cancelRetries.merge(target, 1, Integer::sum) <= 2;
            if (stopped && (cancelled || yielder.foreignRecently(stepTicks + 5))) {
                if (jobCounted) {
                    passJobs = Math.max(0, passJobs - 1);
                    boxVisits.merge(target, -1, Integer::sum);
                }
                job = null;
                target = null;
                wait = delay.get();
                return;
            }
            if (job == Job.Survey) {
                // Skipped for now, not for good: the way may open up (a door, a chunk, a player in the way)
                surveyFailed(
                        target,
                        stopped
                                ? "could not reach it (no path)"
                                : "could not reach it (walk timed out after " + WALK_TIMEOUT / 20 + "s)");
                finishJob();
                return;
            }
            reportUnreachable(target, Cause.Path);
            target.unreachable = true;
            surveyFailed(target, "route to storage is temporarily unavailable");
            finishJob();
        }
    }

    private void tickOpening() {
        AbstractContainerMenu open = mc.player.containerMenu;
        if (open != mc.player.inventoryMenu) {
            // Only the menu our own click opened, of the size this box has. Anything else (an ender chest, a plugin
            // GUI, one left over from an earlier click) is closed and the click retried
            if (openPos != null && open != openMenuBefore && open instanceof ChestMenu && sizedForTarget(open)) {
                if (!com.porter.wesort.compat.ObservationRuntime.canRead(target)) {
                    if (stepTicks > OPEN_TIMEOUT) {
                        surveyFailed(target, "no attributable full server observation");
                        mc.player.closeContainer();
                        finishJob();
                    }
                    return;
                }
                ownedMenu = open;
                debug(() -> String.format("Opened %s at %s after %d ticks.", home(target), xyz(target.pos), stepTicks));
                step = Step.Working;
                stepTicks = 0;
                read(target);
                wait = firstClickWait();
                return;
            }

            debug(() -> String.format(
                    "The window that opened at %s doesn't fit (%s, %d slots, expected %d chest slots), closing it and clicking again.",
                    xyz(target.pos),
                    open.getClass().getSimpleName(),
                    open.slots.size() - 36,
                    target.parts.size() * 27));
            if (!open.getCarried().isEmpty()) recoverCursor();
            mc.player.closeContainer();
            lastCloseTick = ticks;
        }

        // container-delay: the open click waits, and the wait does not count toward the open timeout
        if (openHeld()) {
            stepTicks--;
            return;
        }

        if (stepTicks > OPEN_TIMEOUT) {
            // A read that never gets a window (someone has it open, or it closes at once) is tried again later, not
            // given
            // up on for good. The same for any other job under lag: ending the job retries it later instead of
            // marking the container unreachable for something the server may just be slow to answer.
            if (job == Job.Survey)
                surveyFailed(target, "could not open it (no window after " + OPEN_TIMEOUT / 20 + "s)");
            else debug("Could not open %s at %s, trying it again later.", home(target), xyz(target.pos));
            debug(
                    "Open timeout at %s: job %s, nav %s (%s, %s), baritone active=%s calculating=%s, goal %s.",
                    xyz(target.pos),
                    job,
                    com.porter.wesort.compat.NavigationRuntime.paused() ? "paused" : "running",
                    com.porter.wesort.compat.NavigationRuntime.status(),
                    com.porter.wesort.compat.NavigationRuntime.denialSummary(),
                    BARITONE && BaritoneHelper.isActive(),
                    BARITONE && BaritoneHelper.isCalculating(),
                    ourGoal == null ? "none" : "ours");
            finishJob();
            return;
        }

        if (stepTicks == 1 || stepTicks % 20 == 0) {
            BlockPos pos = nearestPart(target);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> {
                // The rotation can finish after we have started to give way
                if (gate != Gate.Running || job == null) return;
                openPos = pos;
                openClickTick = ticks;
                openMenuBefore = mc.player.containerMenu;
                // W-061: the exact container this job opens, nothing else. TTL-bound (rotation
                // callbacks run ticks later), exact position + block + job, revoked by expiry.
                com.porter.wesort.compat.WorldInteractionGuard.authorizeUse(pos);
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    private void tickWorking() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof ChestMenu) || menu.slots.size() - 36 != target.parts.size() * 27) {
            // A double chest that grew or shrank since it was saved shows the wrong size: bring the
            // parts up to date and carry on instead of blaming the server.
            if (menu instanceof ChestMenu && refreshTargetParts()) {
                if (menu.slots.size() - 36 == target.parts.size() * 27) {
                    closedEarlyTicks = 0;
                    return;
                }
            }
            // Transient hiccups (lag, a click still on its way) close the window for a tick or two:
            // only give up after it stays gone, so an order isn't aborted and its shulker put back
            // because the server was merely slow.
            if (++closedEarlyTicks < 40) return;
            // Closed on us, or swapped for something else, by the server or the player
            if (closedEarlyReported.add(target)) {
                debug(
                        "Container at %s closed before I finished with it (%s), the server may have closed it.",
                        target.pos.toShortString(),
                        menu == mc.player.inventoryMenu ? "window gone" : "another window opened");
            }
            finishJob();
            return;
        }
        closedEarlyTicks = 0;

        if (!read(target)) {
            // Bad TPS looks like an unreadable container: the server observation hasn't arrived yet.
            // Only end the job when the server has answered and it still doesn't read.
            if (stepTicks > OPEN_TIMEOUT && com.porter.wesort.compat.InventorySync.ready()) finishJob();
            return;
        }

        // Both ends of the comparison are read through the open window, before anything is clicked and once the last
        // click is through, so they come from the same source
        if (actions == 0 && !startSigSet) {
            startSig = stateSig();
            startSigSet = true;
        }

        boolean busy = switch (job) {
            case Survey,
                    Clean,
                    Fill,
                    Recover,
                    KitUnpack,
                    KitFill,
                    Ground,
                    Explore,
                    Unbundle,
                    Name,
                    CraftUnpack,
                    Craft,
                    AnvilPlace,
                    ChestPlace -> false;
            case Gather -> compactor.gatherOne(target);
            case KitTake -> kitBuilder.takeOne(target);
            case CraftTake -> crafter.takeOne(target);
            case Take -> takeOne();
            case Fetch -> fetchOne();
            case Compact -> compactOne();
            case Deliver -> deliverOne();
            case Return -> returnOne();
            case Deposit -> depositOne();
            case Dump -> dumpOne();
        };

        if (busy) {
            actions++;
            settled = false;
            wait = delay.get();
        } else if (actions > 0 && !settled) {
            // Give the server a moment to answer the last click, then look again before judging what the clicks did
            settled = true;
            wait = SETTLE_TICKS;
        } else {
            endSig = stateSig();
            endSigSet = true;
            if (actions == 0 && noOpReported.add(futileKey(target, job))) {
                debug(
                        "%s at %s did nothing: %d free carry slots, %d carried.",
                        job,
                        target.pos.toShortString(),
                        freeCarryCount(),
                        carriedSlots().size());
            }
            if (chainJob()) return;
            finishJob();
        }
    }

    /**
     * The job in hand is done and its window is still open: if the same container has something else to do, the job is
     * switched in place instead of closing it and walking back to open it again. A take (something here that belongs
     * elsewhere and has room to go), or a merge of part stacks that has not been done this run.
     * @return true when the job was switched and carries on
     */
    private static final int CHAIN_MAX = 4;

    private int chained;

    private boolean chainJob() {
        if (!sorting() || target == null || job == null || actions == 0) return false;
        if (job != Job.Deposit && job != Job.Take && job != Job.Dump && job != Job.Compact) return false;
        // Only after a part that got somewhere, and never on a container given up on, in the
        // middle of a swap, and not more than 4 times in a row
        if (!(endSigSet && startSigSet && startSig != endSig) || chained >= CHAIN_MAX || swapStep != 0) return false;
        if (stalled.contains(target) || circular.contains(target)) return false;
        if (kitBuilder.active() || compactor.active() || target.unreachable || target.stale || foreignNow) return false;
        if (!(mc.player.containerMenu instanceof ChestMenu)
                || !mc.player.containerMenu.getCarried().isEmpty()) return false;

        Job next = null;
        if (freeCarrySlot() != -1 && !blocked(target, Job.Take) && takeableSlot(target) != -1) next = Job.Take;
        else if (!target.dropOff
                && !blocked(target, Job.Compact)
                && !compacted.contains(target)
                && mergeable(target) != null) {
            compacted.add(target);
            next = Job.Compact;
        }
        if (next == null) return false;

        // What the finished part achieved counts, as when a job ends
        unconfirmedRuns.remove(target);
        clearStalled();
        stalled.addAll(circular);
        stuckCarried.clear();
        stuckWarned = false;
        chained++;
        debug("Staying at %s at %s: %s done, %s next.", home(target), xyz(target.pos), job, next);

        jobCounted = !kitBuilder.active() && next != Job.Compact;
        if (jobCounted) {
            passJobs++;
            boxVisits.merge(target, 1, Integer::sum);
        }
        job = next;
        actions = 0;
        recoveries = 0;
        startSigSet = endSigSet = false;
        settled = false;
        swapStep = 0;
        jobStartedAt = System.nanoTime();
        announce(next, target);
        wait = delay.get();
        return true;
    }

    // Loop protection

    private void resetLoopState() {
        stalled.clear();
        cancelRetries.clear();
        stashWatch.clear();
        deliveryWatch.clear();
        staleBoxes.clear();
        surveyBatch.clear();
        // A Manager rescan may arrive before drive(true) sets up the loop.
        // Preserve its pending one-shot requests across that setup.
        nextIdleSurveyAt = nextBackgroundBatchAt = 0;
        futile.clear();
        noOpReported.clear();
        failedSurvey.clear();
        surveyRetry.clear();
        returnRetry.clear();
        unconfirmedRuns.clear();
        closedEarlyReported.clear();
        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        stuckCarried.clear();
        stuckWarned = false;
        lastSummary = null;
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        startSigSet = endSigSet = false;
    }

    private String futileKey(Box box, Job kind) {
        return box.pos.asLong() + ":" + kind;
    }

    private static long stackSig(ItemStack stack) {
        return stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack) * 131L + stack.getCount();
    }

    private long futileSig(Box box) {
        long h = 17;
        for (int slot : carry) h = h * 31 + stackSig(mc.player.getInventory().getItem(slot));
        if (box.known()) for (ItemStack stack : box.contents) h = h * 31 + stackSig(stack);
        return h;
    }

    /**
     * What the carry slots and the target container hold now.
     */
    private long stateSig() {
        long h = 17;
        for (int slot : carry) h = h * 31 + stackSig(mc.player.getInventory().getItem(slot));
        if (target != null && target.known()) for (ItemStack stack : target.contents) h = h * 31 + stackSig(stack);
        return h;
    }

    /**
     * Not worth a trip for this kind of job: the box is stalled, or the same trip came back empty-handed and neither
     * the carry slots nor the container have changed since.
     */
    private void clearStalled() {
        stalled.clear();
    }

    private boolean blocked(Box box, Job kind) {
        return stalled.contains(box) || blockedByFutility(box, kind);
    }

    private boolean blockedByFutility(Box box, Job kind) {
        Long was = futile.get(futileKey(box, kind));
        return was != null && was == futileSig(box);
    }

    // Logging. Classes of line: ERROR and ALERT (a person is needed) and STEP (now / next) always show; INFO and WARN
    // show unless Quiet; DEBUG only on Debug. Whatever is hidden is still written to the log at debug level

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) return message;
        try {
            return String.format(message, args);
        } catch (RuntimeException e) {
            return message;
        }
    }

    private boolean quiet() {
        return logLevel.get() == LogLevel.Quiet;
    }

    @Override
    public void info(String message, Object... args) {
        if (quiet()) WeSortAddon.LOG.debug("[wesort-stash-sorter] {}", format(message, args));
        else super.info(message, args);
    }

    @Override
    public void warning(String message, Object... args) {
        if (quiet()) WeSortAddon.LOG.warn("[wesort-stash-sorter] {}", format(message, args));
        else super.warning(message, args);
    }

    /**
     * Feedback to something the player just did (marking a container, setting the area): shown at every level.
     */
    private void notice(String message, Object... args) {
        super.info(message, args);
    }

    /**
     * A line that needs a person: shown at every level, and whispered to the whitelist.
     */
    private void alert(String message, Object... args) {
        String text = format(message, args);
        super.warning("%s", text);
        WeSortAddon.LOG.warn("[wesort-stash-sorter] ALERT {}", text);
        sendAlert(text);
    }

    /**
     * An error that needs a person: like alert, but shown as an error.
     */
    private void alertError(String message, Object... args) {
        String text = format(message, args);
        super.error("%s", text);
        WeSortAddon.LOG.error("[wesort-stash-sorter] ALERT {}", text);
        sendAlert(text);
    }

    private static final int ALERT_REPEAT_MS = 10 * 60 * 1000, ALERT_GAP_MS = 5000, ALERT_QUEUE = 5;
    // Alert text with its digits taken out, and when it was last queued
    private final Map<String, Long> alertsSent = new HashMap<>();
    private final java.util.ArrayDeque<String> alertQueue = new java.util.ArrayDeque<>();
    private long lastAlertDm;

    /**
     * Queues an alert as a whisper to every whitelisted player (the whitelist, not the search setting, decides): the same
     * text, digits aside, at most once in 10 minutes, and 5 whispers waiting at the most, the oldest dropped. Sent by
     * {@link #pumpAlerts()}, one every 5 seconds, 256 characters at the most, plain ASCII.
     */
    private void sendAlert(String text) {
        if (offline) return;
        List<String> to = whitelist.get().stream()
                .map(String::trim)
                .filter(n -> !n.isEmpty())
                .toList();
        if (to.isEmpty()) return;

        long now = System.currentTimeMillis();
        alertsSent.values().removeIf(at -> now - at > ALERT_REPEAT_MS);
        String key = text.replaceAll("[0-9]", "");
        if (alertsSent.containsKey(key)) return;
        alertsSent.put(key, now);

        StringBuilder plain = new StringBuilder("Stash Sorter: ");
        for (char c : text.toCharArray()) plain.append(c >= 32 && c < 127 ? c : c == '\u00d7' ? 'x' : '?');
        for (String name : to) {
            String prefix = "/msg " + name + " ";
            String body = plain.length() + prefix.length() > 256
                    ? plain.substring(0, Math.max(0, 256 - prefix.length()))
                    : plain.toString();
            alertQueue.addLast(prefix + body);
            while (alertQueue.size() > ALERT_QUEUE) alertQueue.pollFirst();
        }
    }

    /**
     * Sends one waiting alert whisper, at most one in 5 seconds, and only while we are in a world.
     */
    private void pumpAlerts() {
        if (alertQueue.isEmpty()
                || offline
                || worldTicks < ALERT_JOIN_TICKS
                || mc.player == null
                || mc.level == null
                || mc.getConnection() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAlertDm < ALERT_GAP_MS) return;
        lastAlertDm = now;
        ChatUtils.sendPlayerMsg(alertQueue.pollFirst());
    }

    // What it is doing now and next, shown at every level. The same line is never said twice in a row
    private String lastStep = "";
    private final Map<String, long[]> stepProgress = new HashMap<>();

    private void step(String text) {
        text = "Stash: " + text;
        if (text.equals(lastStep)) return;
        lastStep = text;
        super.info("%s", text);
        WeSortAddon.LOG.info("[wesort-stash-sorter] {}", text);
    }

    /**
     * A STEP for something that goes on for a long time: at most every 10 percent or 60 seconds of {@code done} out of
     * {@code total}.
     */
    private void stepProgress(String phase, int done, int total, Supplier<String> text) {
        long now = System.currentTimeMillis();
        long[] last = stepProgress.get(phase);
        long bucket = total <= 0 ? 0 : done * 10L / total;
        if (last != null && last[0] == bucket && now - last[1] < 60_000) return;
        stepProgress.put(phase, new long[] {bucket, now});
        step(text.get());
    }

    private boolean debugOn() {
        return logLevel.get() == LogLevel.Debug;
    }

    /**
     * Shown as info with [debug] on the Debug level, otherwise only written to the debug log.
     */
    private void debug(String format, Object... args) {
        if (debugOn()) debug(() -> format(format, args));
        else if (WeSortAddon.LOG.isDebugEnabled())
            WeSortAddon.LOG.debug("[wesort-stash-sorter] {}", format(format, args));
    }

    /**
     * Like debug(format, args), for text that costs something to build: only built when it will be shown or logged.
     */
    private void debug(Supplier<String> text) {
        if (debugOn()) {
            String line = text.get();
            WeSortAddon.LOG.debug("[wesort-stash-sorter] {}", line);
            super.info("[debug] %s", line);
        } else if (WeSortAddon.LOG.isDebugEnabled()) WeSortAddon.LOG.debug("[wesort-stash-sorter] {}", text.get());
    }

    /**
     * Whether the open menu is a chest-sized one for the target. A box that has become a double chest (or been halved)
     * since it was saved is brought up to date when the menu's size shows it.
     */
    private boolean sizedForTarget(AbstractContainerMenu menu) {
        int slots = menu.slots.size() - 36;
        if (slots == target.parts.size() * 27) return true;
        return refreshTargetParts() && slots == target.parts.size() * 27;
    }

    /**
     * Re-reads which blocks make up the target container, when a chest next to it was placed or broken since it was
     * saved. Its contents are read again, and a saved box that is now just its other half is dropped, keeping its marks.
     * @return true if the parts changed
     */
    private boolean refreshTargetParts() {
        if (!anyPartLoaded(target)) return false;

        List<BlockPos> now = currentParts(target);
        if (new HashSet<>(now).equals(new HashSet<>(target.parts)) && now.size() == target.parts.size()) return false;

        if (now.size() != target.parts.size()) target.contents = null;
        target.parts.clear();
        target.parts.addAll(now);
        for (int i = stash.boxes.size() - 1; i >= 0; i--) {
            Box other = stash.boxes.get(i);
            if (other == target || other.parts.stream().noneMatch(target::has)) continue;
            carryMarks(other, target);
            stash.boxes.remove(i);
        }
        stash.markDirty();
        return true;
    }

    /**
     * What a box that is being dropped as a duplicate was marked as carries over to the box that stays.
     */
    private static void carryMarks(Box from, Box to) {
        if (com.porter.wesort.compat.ObservationRuntime.current(from)
                && new java.util.HashSet<>(from.parts).equals(new java.util.HashSet<>(to.parts))
                && (from.observationSequence > to.observationSequence
                        || !com.porter.wesort.compat.ObservationRuntime.current(to))) {
            to.contents = from.contents;
            to.lastRead = from.lastRead;
            to.observationSession = from.observationSession;
            to.observationDimension = from.observationDimension;
            to.observationFingerprint = from.observationFingerprint;
            to.observationEpoch = from.observationEpoch;
            to.observationSequence = from.observationSequence;
            to.observationBaseRevision = from.observationBaseRevision;
            to.observationAt = from.observationAt;
            to.observationBlock = from.observationBlock;
        }
        to.protectedStorage |= from.protectedStorage;
        to.excluded |= from.excluded;
        to.dropOff |= from.dropOff;
        to.delivery |= from.delivery;
        to.deliveryExtra |= from.deliveryExtra;
        to.wasExcluded |= from.wasExcluded;
        to.wasDropOff |= from.wasDropOff;
    }

    // Moving items

    /**
     * Moves one stack that belongs somewhere else into a free carry slot.
     * @return false when there's nothing more to take, or nowhere to carry it
     */
    private boolean takeOne() {
        int into = freeCarrySlot();
        if (into == -1) return false;

        int slot = takeableSlot(target);
        if (slot == -1) return false;

        // Straight into a carry slot, so it can never land in or top up one of the player's own stacks
        stashHeld.put(into, target.contents[slot].copy());
        debug(() -> String.format(
                "Click: move slot %d (%s) to carry slot %d.", slot, StackInfo.label(target.contents[slot]), into));
        InvUtils.move().fromId(slot).toId(inventoryToMenu(into, target.contents.length));
        return true;
    }

    /**
     * Takes the one shulker this trip came for into a free carry slot, to repack it.
     * @return false once it's taken, or if it's gone
     */
    private boolean fetchOne() {
        if (wantSlot < 0 || actions > 0) return false;

        int into = freeCarrySlot();
        if (wantSlot >= target.contents.length
                || !ItemStack.matches(fetchExpected, target.contents[wantSlot])
                || !fetchWants.test(target.contents[wantSlot])) {
            // read(target) has installed the authoritative observation. Choose again.
            info("Stock changed at %s, slot %d; saved the new state and replanning.", xyz(target.pos), wantSlot);
            stash.save();
            version++;
            wantSlot = -1;
            fetchExpected = ItemStack.EMPTY;
            job = Job.Survey; // No physical failure: do not stall the freshly read container.
            return false;
        }
        if (into == -1) return false;

        stashHeld.put(into, target.contents[wantSlot].copy());
        noteOrderHeld(into, target.contents[wantSlot]);
        InvUtils.move().fromId(wantSlot).toId(inventoryToMenu(into, target.contents.length));
        wantSlot = -1;
        return true;
    }

    /**
     * A slot in this box holding something that belongs elsewhere, where that somewhere still has room once what
     * we're already carrying is put away. -1 if there's none.
     */
    private int takeableSlot(Box source) {
        if (!source.sortable() || isKitBox(source)) return -1;
        // What each destination can still take, so we don't pick up more than we can put away. The room of every
        // container is worked out once per planning pass; what the carried stacks use up is taken off per source
        Map<Box, Integer> base = roomBase;
        if (base == null) {
            base = new HashMap<>();
            for (Box box : stash.boxes) {
                // Only where a deposit could go: not a container being skipped
                if (box.sortable() && !box.dropOff && !blocked(box, Job.Deposit))
                    base.put(box, box.free() + misplacedCount(box));
            }
            if (destCache != null) roomBase = base;
        }
        Map<Box, Integer> used = new HashMap<>();
        for (int carried : carriedSlots()) {
            for (Box box : destinationsFor(mc.player.getInventory().getItem(carried))) {
                if (box != source && base.containsKey(box)) {
                    used.merge(box, 1, Integer::sum);
                    break;
                }
            }
        }
        final Map<Box, Integer> baseRoom = base;
        java.util.function.ToIntFunction<Box> room =
                b -> b == source || !baseRoom.containsKey(b) ? 0 : baseRoom.get(b) - used.getOrDefault(b, 0);

        // Drop-offs are emptied even when nowhere particular has room: whatever doesn't fit goes to a misc chest,
        // or any container with space
        int spare = source.dropOff ? spareRoom(source) : 0;

        for (int i = 0; i < source.contents.length; i++) {
            ItemStack stack = source.contents[i];
            if (stack.isEmpty() || !misplaced(stack, source)) continue;
            // A bundle with things in it needs a slot for itself and one for what comes out
            if (fullBundle(stack)) {
                if (freeCarryCount() >= 2) return i;
                continue;
            }
            if (destinationsFor(stack).stream()
                    .anyMatch(b -> room.applyAsInt(b) > 0 || used.getOrDefault(b, 0) == 0 && roomForWhole(b, stack)))
                return i;
            if (spare > 0) return i;
        }

        return -1;
    }

    /**
     * Puts one carried stack that belongs here into this container. When it's full, swaps it with something in here
     * that belongs elsewhere instead, so full containers can still be sorted.
     * @return false when nothing more can be put here
     */
    private boolean depositOne() {
        int size = target.contents.length;

        // Mid-swap: carried stack on the cursor, then into the slot, then what was there back into the carry slot
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (swapStep > 0) {
            // The cursor must hold what the last click should have left on it, otherwise a click was rejected
            ItemStack expected = swapStep == 1 ? swapStack : swapDisplaced;
            if (!ItemStack.matches(carried, expected)) {
                swapStep = 0;
                return recoverBounded();
            }

            if (swapStep == 1) InvUtils.click().slotId(swapSlot);
            else InvUtils.click().slotId(inventoryToMenu(swapCarry, size));

            swapStep = swapStep == 2 ? 0 : swapStep + 1;
            if (swapStep == 0) moved++;
            return true;
        }

        // A stack left on the cursor by an earlier hiccup goes away before anything else is clicked
        if (!carried.isEmpty()) return recoverBounded();

        for (int slot : carriedSlots()) {
            if (kitBuilder.holds(slot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            // A bundle with things in it is emptied, never put away as it is
            if (fullBundle(stack)) continue;
            if (!destinationsFor(stack).contains(target)) continue;

            if (canFit(stack)) {
                debug(() -> String.format("Click: shift-click carry slot %d into %s.", slot, xyz(target.pos)));
                InvUtils.shiftClick().slotId(inventoryToMenu(slot, size));
                moved++;
                return true;
            }

            for (int i = 0; i < size; i++) {
                ItemStack here = target.contents[i];
                // A click of one stack on a bundle puts it in the bundle, and a bundle on a stack takes it in: never
                // swapped
                if (here.isEmpty()
                        || isBundle(here)
                        || isBundle(stack)
                        || !misplaced(here, target)
                        || ItemStack.isSameItemSameComponents(here, stack)) continue;

                swapCarry = slot;
                swapSlot = i;
                swapStack = stack.copy();
                swapDisplaced = here.copy();
                stashHeld.put(slot, here.copy());
                swapStep = 1;
                InvUtils.click().slotId(inventoryToMenu(slot, size));
                return true;
            }
        }

        return false;
    }

    /**
     * recoverCursor, but gives up after a few tries in one job, so a click the server keeps rejecting can't loop forever.
     * Giving up counts as getting nothing done, so the box is skipped until something else moves.
     */
    private boolean recoverBounded() {
        if (++recoveries > 3) {
            actions = 0;
            return false;
        }
        return recoverCursor();
    }

    /**
     * Puts whatever is on the cursor into an empty slot, the player's own slots first.
     * @return false if there's nothing to put it in
     */
    private boolean recoverCursor() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu.getCarried().isEmpty()) return true;
        lastOwnAct = ticks;

        if (!ourMenu(menu) || menu.slots.size() < 36) return false;
        Set<Integer> keep = parseSlots(keepSlots.get());
        int size = menu.slots.size() - 36;
        // The carry slots first, so the stack is put away with the rest, and noted as the stash's own
        for (int pass = 0; pass < 2; pass++) {
            for (int i = size; i < menu.slots.size(); i++) {
                if (!menu.getSlot(i).getItem().isEmpty()) continue;
                int slot = i - size < 27 ? 9 + i - size : i - size - 27;
                if (keep.contains(slot) || (pass == 0) != carry.contains(slot)) continue;

                stashHeld.put(slot, menu.getCarried().copy());
                InvUtils.click().slotId(i);
                return true;
            }
        }
        // Only into the container we're working on, never some other menu
        if (target != null
                && menu instanceof ChestMenu
                && size == target.parts.size() * 27
                && InventorySync.confirmedSource(target.parts)) {
            for (int i = 0; i < size; i++) {
                if (menu.getSlot(i).getItem().isEmpty()) {
                    InvUtils.click().slotId(i);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Puts carried stacks down in a container with space, when nowhere they belong has room for them.
     */
    private boolean startStartupDeposit() {
        if (startupRetryAt == 0 && startupStock.isEmpty()) {
            Set<Integer> keep = parseSlots(keepSlots.get());
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (stack.isEmpty() || keep.contains(slot) || ownKind(stack) || orderRunner.claims(stack)) continue;
                startupStock.put(slot, stack.copy());
            }
        }
        startupStock
                .entrySet()
                .removeIf(entry -> !ItemStack.matches(
                        entry.getValue(), mc.player.getInventory().getItem(entry.getKey())));
        for (var entry : startupStock.entrySet()) {
            ItemStack stack = entry.getValue();
            Box home = nearest(destinationsFor(stack).stream()
                    .filter(box -> startupDestination(box, stack))
                    .toList());
            if (home == null)
                home = nearest(stash.boxes.stream()
                        .filter(box -> startupDestination(box, stack))
                        .toList());
            if (home != null) {
                startupDump = true;
                step("startup: putting away excess inventory before sorting.");
                return start(Job.Dump, home);
            }
        }
        if (!startupStock.isEmpty()) {
            if (startupRetryAt == 0)
                warning(
                        "Startup inventory: waiting for verified storage room for %d stacks; retrying automatically.",
                        startupStock.size());
            startupRetryAt = ticks + 20 * 60;
            startupDump = false;
            pickCarrySlots(false);
            return false;
        }
        startupDepositPending = false;
        startupDump = false;
        pickCarrySlots(false);
        return false;
    }

    private boolean startupDestination(Box box, ItemStack stack) {
        return returnable(box, stack) && box.sortable() && !box.foreign && !blocked(box, Job.Dump);
    }

    private boolean dumpOne() {
        if (startupDump && startupDepositPending) {
            if (!mc.player.containerMenu.getCarried().isEmpty()) return recoverBounded();
            for (var entry : startupStock.entrySet()) {
                int slot = entry.getKey();
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (!ItemStack.matches(stack, entry.getValue())
                        || parseSlots(keepSlots.get()).contains(slot)
                        || orderRunner.claims(stack)
                        || !startupDestination(target, stack)) continue;
                InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
                return true;
            }
            return false;
        }
        for (int slot : carriedSlots()) {
            if (kitBuilder.holds(slot)
                    || fullBundle(mc.player.getInventory().getItem(slot))
                    || !canFit(mc.player.getInventory().getItem(slot))) continue;

            InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
            return true;
        }
        return false;
    }

    private boolean canFit(ItemStack stack) {
        for (ItemStack here : target.contents) {
            if (here.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(here, stack) && here.getCount() < here.getMaxStackSize())
                return true;
        }
        return false;
    }

    // Planning

    /**
     * Works out where everything belongs. The single tier mode uses deterministic container planning.
     */
    private void plan() {
        tierBoxes.clear();

        List<Box> ordered = stash.boxes.stream()
                .filter(b -> b.sortable() && !b.dropOff)
                .sorted(Comparator.comparingInt(b -> b.order))
                .toList();

        // How many stacks of each tier there are, including drop-offs and what we're carrying
        int[] counts = new int[5];
        for (Box box : stash.boxes) {
            if (!box.sortable()) continue;
            for (ItemStack stack : box.contents) if (!stack.isEmpty()) counts[tier(stack)]++;
        }
        for (int slot : carriedSlots()) counts[tier(mc.player.getInventory().getItem(slot))]++;

        // Fill the containers in order, a tier at a time. A container can end up shared by two tiers.
        int index = 0, used = 0;
        for (int t = 0; t < counts.length; t++) {
            List<Box> list = new ArrayList<>();
            int need = counts[t];

            while (need > 0 && index < ordered.size()) {
                Box box = ordered.get(index);
                if (!list.contains(box)) list.add(box);

                int take = Math.min(need, box.contents.length - used);
                need -= take;
                used += take;

                if (used >= box.contents.length) {
                    index++;
                    used = 0;
                }
            }

            // More of this tier than room: the overflow can sit in the last container
            if (list.isEmpty() && !ordered.isEmpty()) list.add(ordered.get(Math.min(index, ordered.size() - 1)));
            tierBoxes.put(t, list);
        }
    }

    /**
     * Where a stack should go: the boxes it belongs in, all equally good. Empty if it doesn't belong anywhere in
     * particular, in which case it's left where it is. Never an excluded or drop-off container.
     */
    /** Read-only diagnostic uses the same matching and capacity predicates as the sorter. */
    public JsonObject explainSort(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("item", stack.isEmpty() ? "empty" : StashIndex.keyOf(stack));
        out.addProperty("mode", "Tiers");
        out.addProperty("permitted", sortingPermitted());
        JsonArray candidates = new JsonArray();
        out.add("candidates", candidates);
        if (ensureStash() == null || stack.isEmpty()) return out;
        List<Box> chosen = computeDestinations(stack);
        Box overflow = chosen.stream().anyMatch(box -> box.sortable() && !box.stale && roomFor(box, stack))
                ? null
                : spareBox();
        for (Box box : stash.boxes) {
            JsonObject entry = new JsonObject();
            entry.addProperty(
                    "container",
                    new com.porter.wesort.data.ContainerIdentity(
                                    stash.server,
                                    StashIndex.dimension(),
                                    box.parts.stream()
                                            .map(p -> new com.porter.wesort.data.ContainerIdentity.Position(
                                                    p.getX(), p.getY(), p.getZ()))
                                            .toList())
                            .id());
            String reason = box.delivery
                    ? "OUTBOUND_STAGING"
                    : box.protectedStorage
                            ? "PROTECTED"
                            : box.excluded
                                    ? "EXCLUDED"
                                    : isKitBox(box)
                                            ? "KIT_STORAGE"
                                            : box.dropOff
                                                    ? "DROP_OFF_SOURCE"
                                                    : !box.known() || box.stale
                                                            ? "RESCAN_REQUIRED"
                                                            : !box.sortable()
                                                                    ? "UNAVAILABLE"
                                                                    : box == overflow
                                                                            ? "TIER_OVERFLOW"
                                                                            : !chosen.contains(box)
                                                                                    ? "NO_MATCH_OR_BETTER_DESTINATION"
                                                                                    : !roomFor(box, stack)
                                                                                            ? "FULL"
                                                                                            : "MATCH";
            entry.addProperty("reason", reason);
            entry.addProperty("eligible", reason.equals("MATCH") || reason.endsWith("_OVERFLOW"));
            candidates.add(entry);
        }
        return out;
    }

    private final Set<String> blockedDropItems = new HashSet<>();

    private void reportBlockedDropOff() {
        for (Box box : stash.boxes) {
            if (!box.sortable() || !box.dropOff || box.stale) continue;
            for (ItemStack stack : box.contents) {
                if (!stack.isEmpty() && !canPlace(stack) && blockedDropItems.add(StashIndex.keyOf(stack)))
                    warning(
                            "Drop-off blocked: no unprotected destination has room for %s. Items remain in their source.",
                            StackInfo.label(stack));
            }
        }
    }

    private List<Box> destinationsFor(ItemStack stack) {
        if (stack.isEmpty() || orderRunner.claims(stack)) return List.of();
        if (destCache == null) return computeDestinations(stack);
        return destCache.computeIfAbsent(DestKey.of(stack), k -> computeDestinations(stack));
    }

    private List<Box> computeDestinations(ItemStack stack) {
        // Tier storage is the sole sorter; sign text never authorizes a destination.
        return tierBoxes.getOrDefault(tier(stack), List.of());
    }

    /**
     * In the wrong place: everything in a drop-off that has somewhere to go, or anything in a container it doesn't
     * belong in.
     */
    private boolean misplaced(ItemStack stack, Box box) {
        // Nothing stays in a drop-off
        if (box.dropOff) return true;

        // A bundle with things in it is taken out to be emptied, wherever it is
        if (fullBundle(stack)) return true;

        List<Box> places = destinationsFor(stack);
        return !places.isEmpty() && !places.contains(box);
    }

    /** Free slots in non-protected ordinary containers. */
    private int spareRoom(Box except) {
        int free = 0;
        for (Box box : stash.boxes) if (box != except && takesAnything(box)) free += box.free();
        return free - carriedSlots().size();
    }

    /**
     * Whether a carried stack has somewhere to go right now: a container it belongs in that has room, or one that
     * takes anything.
     */
    private boolean canPlace(ItemStack stack) {
        for (Box box : destinationsFor(stack)) {
            if (!blocked(box, Job.Deposit) && (roomFor(box, stack) || misplacedCount(box) > 0)) return true;
        }
        return spareBox() != null;
    }

    /** Choose a spare container by free capacity, independent of sign text. */
    private Box spareBox() {
        List<Box> candidates = stash.boxes.stream().filter(this::takesAnything).toList();
        Box used = nearest(
                candidates.stream().filter(b -> b.free() < b.contents.length).toList());
        return used != null ? used : nearest(candidates);
    }

    private boolean takesAnything(Box box) {
        return box.sortable() && !box.dropOff && box.free() > 0 && !blocked(box, Job.Dump) && !isKitBox(box);
    }

    /**
     * Two partial stacks of the same item in this container, {first, second}, or null if there are none to merge.
     */
    private static int[] mergeable(Box box) {
        ItemStack[] c = box.contents;
        for (int i = 0; i < c.length; i++) {
            ItemStack a = c[i];
            if (a.isEmpty() || isBundle(a) || a.getMaxStackSize() <= 1 || a.getCount() >= a.getMaxStackSize()) continue;
            for (int j = c.length - 1; j > i; j--) {
                ItemStack b = c[j];
                if (!b.isEmpty() && ItemStack.isSameItemSameComponents(a, b) && b.getCount() < b.getMaxStackSize())
                    return new int[] {i, j};
            }
        }
        return null;
    }

    /**
     * Merges one later partial stack into an earlier one: pick it up, drop it on the earlier stack, put back what's left.
     */
    private boolean compactOne() {
        int[] pair = mergeable(target);
        if (pair == null) return false;

        InvUtils.click().slotId(pair[1]);
        InvUtils.click().slotId(pair[0]);
        if (!mc.player.containerMenu.getCarried().isEmpty()) InvUtils.click().slotId(pair[1]);
        return true;
    }

    /**
     * A shulker holding more than one kind of item.
     */
    private static boolean mixedShulker(ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;
        Item first = null;
        for (ItemStack s : inside(stack)) {
            if (s == null || s.isEmpty()) continue;
            if (first == null) first = s.getItem();
            else if (s.getItem() != first) return true;
        }
        return false;
    }

    private int misplacedCount(Box box) {
        if (misplacedCache == null) return countMisplaced(box);
        Integer cached = misplacedCache.get(box);
        if (cached != null) return cached;
        int count = countMisplaced(box);
        misplacedCache.put(box, count);
        return count;
    }

    private int countMisplaced(Box box) {
        int count = 0;
        for (ItemStack stack : box.contents) if (!stack.isEmpty() && misplaced(stack, box)) count++;
        return count;
    }

    /**
     * 0 filled shulkers, 1 empty shulkers, 2 stacks to 64, 3 stacks to 16, 4 everything else.
     */
    private static int tier(ItemStack stack) {
        if (Utils.isShulker(stack.getItem())) {
            ItemStack[] contents = new ItemStack[27];
            Utils.getItemsInContainerItem(stack, contents);
            for (ItemStack s : contents) if (s != null && !s.isEmpty()) return 0;
            return 1;
        }
        if (stack.getMaxStackSize() == 64) return 2;
        if (stack.getMaxStackSize() == 16) return 3;
        return 4;
    }

    // Containers

    /**
     * Adds containers around us that aren't in the stash yet, and drops ones that have been broken.
     * @return how many were added
     */
    private int addNearbyBoxes() {
        BlockPos center = mc.player.blockPosition();
        int r = radius.get(), h = height.get();
        int added = 0;

        // With a perimeter, nothing outside it belongs to the stash
        // (the delivery container is wherever the user put it: outside the perimeter is fine)
        pruneForeign("scan");
        pruneStrays("scan");
        if (stash.boxes.removeIf(this::blockGone)) stash.markDirty();

        // A chest next to a saved one may have made it a double (or a double been halved): bring the parts up to date
        // and forget one that is now just the other half of an earlier box. Changed boxes get read again.
        // Any half counts: the fresh parts are read from whichever half is loaded.
        for (Box box : stash.boxes) {
            if (!anyPartLoaded(box)) continue;
            List<BlockPos> now = currentParts(box);
            if (new HashSet<>(now).equals(new HashSet<>(box.parts)) && now.size() == box.parts.size()) continue;

            boolean resized = now.size() != box.parts.size();
            box.parts.clear();
            box.parts.addAll(now);
            if (resized) box.contents = null;
            stash.markDirty();
        }
        for (int i = stash.boxes.size() - 1; i > 0; i--) {
            for (int j = 0; j < i; j++) {
                Box a = stash.boxes.get(j), b = stash.boxes.get(i);
                if (a.parts.stream().anyMatch(b::has) || b.parts.stream().anyMatch(a::has)) {
                    carryMarks(stash.boxes.get(i), stash.boxes.get(j));
                    surveyRetry.succeed(stash.boxes.get(i));
                    failedSurvey.remove(stash.boxes.get(i));
                    stash.boxes.remove(i);
                    stash.markDirty();
                    break;
                }
            }
        }

        // Nothing above takes the delivery container; if it was lost some other way it is made a box again from its
        // position
        stash.ensureDelivery();
        stash.ensureInput();

        // The perimeter replaces radius and height: every container inside it, in the chunks that are loaded
        if (stash.hasArea()) return added + scanArea();

        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -h, -r), center.offset(r, h, r))) {
            if (!isContainer(p) || stash.boxAt(p) != null) continue;
            stash.boxes.add(makeBox(p.immutable()));
            added++;
        }

        if (added > 0) stash.markDirty();
        return added;
    }

    /**
     * Containers outside the stash perimeter (a damaged or foreign save, a pick outside it): one never read is dropped
     * (nothing is known of it, and walking to one far away wastes the scan); one that was read is kept in the file but
     * flagged foreign, so scanning and sorting skip it. The delivery container and the input chest may be anywhere.
     */
    private void pruneForeign(String where) {
        if (stash == null) return;
        if (!stash.hasArea() || !inStashDimension()) {
            for (Box box : stash.boxes) box.foreign = false;
            return;
        }
        boolean any = stash.boxes.removeIf(box -> {
            StashSafety.Outside what = StashSafety.outside(
                    box.delivery || box.input, box.parts.stream().noneMatch(stash::inArea), box.neverRead());
            if (what == StashSafety.Outside.INSIDE) {
                box.foreign = false;
                return false;
            }
            if (what == StashSafety.Outside.SKIP) {
                if (!box.foreign)
                    WeSortAddon.LOG.info(
                            "[wesort-stash-sorter] kept the read container at {} ({}): it is outside the stash area {}; it is skipped, not deleted.",
                            xyz(box.pos),
                            where,
                            stash.areaText());
                box.foreign = true;
                return false;
            }
            surveyRetry.succeed(box);
            WeSortAddon.LOG.info(
                    "[wesort-stash-sorter] dropped the never-read container at {} ({}): it is outside the stash area {}.",
                    xyz(box.pos),
                    where,
                    stash.areaText());
            return true;
        });
        if (any) {
            stash.markDirty();
            failedSurvey.removeIf(b -> !stash.boxes.contains(b));
        }
    }

    /**
     * A stash with no area: never-read containers more than 20000 blocks from every read, non-delivery one are strays
     * (a bad save) and are removed, after the stash file is backed up. Needs at least 5 such reference containers.
     */
    private void pruneStrays(String where) {
        if (stash == null || stash.hasArea() || !inStashDimension()) return;
        List<StashSafety.Spot> spots = new ArrayList<>();
        for (Box b : stash.boxes)
            spots.add(new StashSafety.Spot(
                    new StashSafety.Pt(b.pos.getX(), b.pos.getZ()), !b.neverRead(), b.delivery || b.input));
        List<Integer> idx = StashSafety.strays(spots);
        if (idx.isEmpty()) return;
        if (!stash.backUpFile("Removing stray containers from the stash file")) {
            WeSortAddon.LOG.warn(
                    "[wesort-stash-sorter] {} stray never-read container(s) found ({}) but the stash file could not be backed up, so none were removed.",
                    idx.size(),
                    where);
            return;
        }
        List<Box> gone = new ArrayList<>();
        for (int i : idx) gone.add(stash.boxes.get(i));
        for (Box b : gone) {
            WeSortAddon.LOG.info(
                    "[wesort-stash-sorter] removed the stray never-read container at {} ({}): over {} blocks from every read container. Box: parts={}, excluded={}, dropOff={}, noCompact={}, lastRead={}.",
                    xyz(b.pos),
                    where,
                    StashSafety.STRAY_DISTANCE,
                    b.parts.stream().map(StashSorter::xyz).toList(),
                    b.excluded,
                    b.dropOff,
                    b.noCompact,
                    b.lastRead);
            stash.boxes.remove(b);
            surveyRetry.succeed(b);
            failedSurvey.remove(b);
        }
        stash.markDirty();
    }

    /**
     * Something a stash can hold is still at this block, whatever the barrels setting says: that setting only decides what
     * is added and read, never what is forgotten.
     */
    private boolean containerBlockHere(BlockPos pos) {
        BlockEntity entity = mc.level.getBlockEntity(pos);
        return entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity;
    }

    /**
     * The stash is known to belong to the dimension we are in (the perimeter's, else the stash's own). Nothing is
     * forgotten for lying outside the perimeter or for a missing block unless this holds.
     */
    private boolean inStashDimension() {
        if (stash == null || mc.level == null) return false;
        String dim = !stash.areaDimension.isEmpty() ? stash.areaDimension : stash.dimension;
        return !dim.isEmpty() && Stash.sameDimension(dim, Stash.currentDimension());
    }

    private boolean isContainer(BlockPos pos) {
        BlockEntity entity = mc.level.getBlockEntity(pos);
        return entity instanceof ChestBlockEntity || (barrels.get() && entity instanceof BarrelBlockEntity);
    }

    private Box makeBox(BlockPos pos) {
        List<BlockPos> parts = new ArrayList<>(List.of(pos));
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            parts.add(pos.relative(ChestBlock.getConnectedDirection(state)));
        }
        return new Box(pos, parts);
    }

    private void orderBoxes() {
        // Tiers fill the nearest containers first. Any half of a double chest counts.
        Vec3 start = mc.player.position();
        List<Box> sorted = new ArrayList<>(stash.boxes);
        sorted.sort(Comparator.<Box>comparingDouble(b -> start.distanceToSqr(Vec3.atCenterOf(nearestPart(b))))
                .thenComparingInt(b -> b.pos.getY())
                .thenComparingInt(b -> b.pos.getX())
                .thenComparingInt(b -> b.pos.getZ()));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).order = i;
    }

    // Picking containers

    /**
     * @return true when picking, so the click doesn't open the container
     */
    private boolean onRightClick() {
        if (pick.get() == Pick.Off) return false;
        if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;

        BlockPos pos = hit.getBlockPos();
        if (pick.get() == Pick.Corner1 || pick.get() == Pick.Corner2) {
            setCorner(pick.get() == Pick.Corner1 ? 1 : 2, pos.immutable());
            return true;
        }
        if (!isContainer(pos)) {
            notice("That isn't a chest or barrel.");
            return true;
        }

        // A refused delivery spot leaves no box behind
        if (pick.get() == Pick.Delivery && !deliverySpotSettable(pos)) return true;
        // The drop-off chest is Manager-owned on directed stashes: it is selected in
        // Settings > Stashes, never picked in the game. Refuse before any box is made.
        if (pick.get() == Pick.Input && stash != null && stash.directiveRev >= 0) {
            notice("The drop-off chest is set in the Manager (Settings > Stashes), not in the game.");
            return true;
        }
        if (ensureStash() == null) stash = new Stash(stashName(), Stash.currentServer());
        Box box = stash.boxAt(pos);
        if (box == null) {
            // The delivery and input chests may be outside the area; anything else out there isn't added
            if (stash.hasArea() && pick.get() != Pick.Delivery && pick.get() != Pick.Input && !stash.inArea(pos)) {
                notice("%s is outside the stash area, it isn't added.", pos.toShortString());
                return true;
            }
            box = makeBox(pos.immutable());
            stash.boxes.add(box);
        }

        if (pick.get() == Pick.CompactSkip) {
            box.noCompact = !box.noCompact;
            notice(
                    box.noCompact
                            ? "%s is never used to gather for compacting."
                            : "%s can be used for compacting again.",
                    box.pos.toShortString());
        } else if (pick.get() == Pick.Delivery) {
            if (box.deliveryExtra)
                notice(
                        "%s is already one of the delivery chests (put up above the delivery container). Change the delivery container to forget them.",
                        box.pos.toShortString());
            else {
                boolean set = markDelivery(box);
                notice(
                        set
                                ? "%s is where orders are delivered. It is never sorted."
                                : "%s is a normal container again.",
                        box.pos.toShortString());
            }
        } else if (pick.get() == Pick.Input) {
            if (box.delivery || box.deliveryExtra) {
                notice("%s is a delivery container, it can't also be the input chest.", box.pos.toShortString());
            } else {
                boolean set = markInput(box);
                notice(
                        set
                                ? "%s is the input chest. Unwanted items and shulkers left here are sorted into the stash."
                                : "%s is a normal container again.",
                        box.pos.toShortString());
            }
        } else if (pick.get() == Pick.Protect) {
            box.protectedStorage = !box.protectedStorage;
            box.stale = true;
            box.observationSession = "";
            notice(
                    box.protectedStorage ? "%s is protected from automated moves." : "%s is no longer protected.",
                    box.pos.toShortString());
        } else if (pick.get() == Pick.Exclude) {
            if (box.input && stash.directiveRev >= 0) {
                notice("The drop-off chest is owned by the Manager; clear it in Settings > Stashes.");
                return true;
            }
            box.excluded = !box.excluded;
            box.dropOff = false;
            if (box.input) unmarkInput(box);
            if (box.delivery && box.pos.equals(stash.deliveryPos)) stash.deliveryPos = null;
            forgetExtra(box);
            box.delivery = false;
            notice(box.excluded ? "%s won't be sorted." : "%s will be sorted again.", box.pos.toShortString());
        } else {
            // The drop-off pick on the input chest unmarks it (it already empties like one),
            // unless the Manager owns it: directed drop-off chests are only cleared in the Manager.
            if (box.input) {
                if (stash.directiveRev >= 0) {
                    notice("The drop-off chest is owned by the Manager; clear it in Settings > Stashes.");
                    return true;
                }
                unmarkInput(box);
                notice("%s is no longer the input chest.", box.pos.toShortString());
                stash.save();
                version++;
                return true;
            }
            box.dropOff = !box.dropOff;
            box.excluded = false;
            if (box.delivery && box.pos.equals(stash.deliveryPos)) stash.deliveryPos = null;
            forgetExtra(box);
            box.delivery = false;
            notice(
                    box.dropOff
                            ? "%s is a drop-off, it'll be emptied into the stash."
                            : "%s is a normal container again.",
                    box.pos.toShortString());
        }

        stash.save();
        version++;
        return true;
    }

    /**
     * Makes this box the delivery container, or a normal one again if it was. There is only one: the old one is restored
     * to what it was before. The same for the pick and for wesort-stash-manager.
     * @return true when it is now the delivery container
     */
    /** Whether the container's block is really gone (its chunk is loaded and it is no container). Any half of a double chest counts. */
    private boolean blockGone(Box box) {
        if (!anyPartLoaded(box)) return false;
        if (!box.delivery) {
            if (anyContainerBlockHere(box)) return false;
            surveyRetry.succeed(box);
            failedSurvey.remove(box);
            return true;
        }

        // A chest put up for the delivery storage: gone when it is no chest (syncDelivery takes it out of the storage)
        // Any half standing keeps it.
        for (BlockPos part : box.parts) {
            if (mc.level.isLoaded(part) && mc.level.getBlockEntity(part) instanceof ChestBlockEntity) return false;
        }

        // The delivery container counts as long as it is a chest or barrel, whatever the barrels setting says
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) continue;
            var entity = mc.level.getBlockEntity(part);
            if (entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity) return false;
        }
        stash.deliveryPos = null;
        alert("Delivery container at %s is gone (block removed), cleared.", box.pos.toShortString());
        return true;
    }

    private boolean markDelivery(Box box) {
        // A chest put up for the delivery storage is not the container: it is left as it is
        if (box.deliveryExtra) return true;
        boolean set = !box.delivery;
        // A different container (or none) forgets the chests put up above the old one
        dropDeliveryExtras();
        List<Box> unread = new ArrayList<>();
        for (Box other : stash.boxes) {
            if (other.delivery) {
                other.delivery = false;
                other.excluded = other.wasExcluded;
                other.dropOff = other.wasDropOff;
                other.wasExcluded = other.wasDropOff = false;
                // Not a delivery box any more: a never-read one is removed rather than kept as an ordinary container
                if (other.neverRead()) unread.add(other);
            }
        }
        for (Box gone : unread) {
            stash.boxes.remove(gone);
            surveyRetry.succeed(gone);
            failedSurvey.remove(gone);
            WeSortAddon.LOG.info(
                    "[wesort-stash-sorter] removed the never-read container at {} that stopped being a delivery container.",
                    xyz(gone.pos));
        }
        if (set) {
            box.wasExcluded = box.excluded;
            box.wasDropOff = box.dropOff;
            if (box.input) unmarkInput(box);
            box.delivery = true;
            box.excluded = true;
            box.dropOff = false;
        }
        stash.deliveryPos = set ? box.pos : null;
        return set;
    }

    /**
     * For wesort-stash-manager: marks or unmarks the container at this block as the delivery container.
     * @return 1 when it is now the delivery container, 0 when it was unmarked, -1 when it isn't a container, -2 when no
     *         stash is loaded, -3 when the position is not loaded and more than 128 blocks from the player
     */
    int toggleDelivery(BlockPos pos) {
        if (mc.level == null || ensureStash() == null) return -2;
        if (!deliverySpotSettable(pos)) return -3;
        if (!isContainer(pos)) return -1;

        Box box = stash.boxAt(pos);
        // A chest put up for the delivery storage is already a delivery chest
        if (box != null && box.deliveryExtra) return 1;
        if (box == null) {
            box = makeBox(pos.immutable());
            stash.boxes.add(box);
        }
        boolean set = markDelivery(box);
        stash.save();
        version++;
        return set ? 1 : 0;
    }

    /** A delivery spot is refused when it is not loaded and far from the player (a mistyped or stale position). */
    private boolean deliverySpotSettable(BlockPos pos) {
        if (mc.player == null) return false;
        boolean ok = StashSafety.spotSettable(
                mc.level.isLoaded(pos),
                new StashSafety.Pt(pos.getX(), pos.getZ()),
                new StashSafety.Pt(mc.player.getBlockX(), mc.player.getBlockZ()));
        if (!ok)
            notice(
                    "%s is not loaded and more than %d blocks from you, so it is not made the delivery container. Go there first.",
                    pos.toShortString(), StashSafety.FAR_LIMIT);
        return ok;
    }

    /** For wesort-stash-manager: no delivery container any more. @return false when there was none or no stash */
    boolean clearDelivery() {
        if (ensureStash() == null) return false;
        Box was = null;
        for (Box b : stash.boxes) if (b.delivery) was = b;
        if (was == null) return false;
        markDelivery(was);
        stash.save();
        version++;
        return true;
    }

    /**
     * Makes this box the input chest, or a normal one again if it was. There is only one. An input chest
     * empties like a drop-off, and may be outside the stash area.
     * @return true when it is now the input chest
     */
    private boolean markInput(Box box) {
        // A chest put up for the delivery storage is not the container: it is left as it is
        if (box.deliveryExtra) return true;
        boolean set = !box.input;
        for (Box other : stash.boxes) {
            if (other != box && other.input) {
                other.input = false;
                other.dropOff = false;
            }
        }
        if (set) {
            box.input = true;
            box.dropOff = true;
            box.excluded = false;
            box.stale = true;
            box.observationSession = "";
        } else {
            unmarkInput(box);
            return false;
        }
        stash.inputPos = box.pos;
        stash.inputSource = "set";
        return true;
    }

    private void unmarkInput(Box box) {
        box.input = false;
        box.dropOff = false;
        if (stash.inputPos != null && stash.inputPos.equals(box.pos)) {
            stash.inputPos = null;
            stash.inputSource = "";
        }
    }

    /** For wesort-stash-manager: no input chest any more. @return false when there was none or no stash */
    boolean clearInput() {
        if (ensureStash() == null) return false;
        Box was = null;
        for (Box b : stash.boxes) if (b.input) was = b;
        if (was == null && stash.inputPos == null) return false;
        if (was != null) unmarkInput(was);
        else {
            stash.inputPos = null;
            stash.inputSource = "";
        }
        stash.save();
        version++;
        return true;
    }

    // The stash perimeter

    /**
     * Makes the block the corner of the stash perimeter. The perimeter only applies once both corners are set.
     */
    private void setCorner(int which, BlockPos pos) {
        if (ensureStash() == null) stash = new Stash(stashName(), Stash.currentServer());
        // Corners set in another dimension don't go with this one
        String dimension = Stash.currentDimension();
        if (stash.hasCorners() || stash.corner1 != null || stash.corner2 != null) {
            if (!stash.areaDimension.isEmpty() && !stash.areaDimension.equals(dimension)) {
                stash.corner1 = null;
                stash.corner2 = null;
            }
        }
        BlockPos other = which == 1 ? stash.corner2 : stash.corner1;
        if (other != null && Stash.tooBig(pos, other)) {
            error(
                    "That area is %d\u00d7%d\u00d7%d, the largest allowed is %d\u00d7%d\u00d7%d (width \u00d7 height \u00d7 depth).",
                    Math.abs(pos.getX() - other.getX()) + 1,
                    Math.abs(pos.getY() - other.getY()) + 1,
                    Math.abs(pos.getZ() - other.getZ()) + 1,
                    Stash.MAX_X,
                    Stash.MAX_Y,
                    Stash.MAX_Z);
            return;
        }

        if (which == 1) stash.corner1 = pos;
        else stash.corner2 = pos;
        stash.areaDimension = dimension;
        stash.markDirty();
        stash.save();

        if (stash.hasArea()) {
            notice("Stash area set: %s.", stash.areaText());
            areaChanged();
        } else
            notice(
                    "Corner %d set at [%d, %d, %d]. Set corner %d to finish the stash area.",
                    which, pos.getX(), pos.getY(), pos.getZ(), which == 1 ? 2 : 1);
    }

    private void clearArea() {
        if (ensureStash() == null || (stash.corner1 == null && stash.corner2 == null)) {
            notice("There is no stash area set.");
            return;
        }
        boolean applied = stash.hasArea();
        stash.corner1 = null;
        stash.corner2 = null;
        stash.areaDimension = "";
        stash.markDirty();
        stash.save();
        notice("Stash area cleared, finding containers around you again.");
        if (applied) areaChanged();
    }

    /**
     * A Manager directive: the stash region cube, drop-off chest, delivery container, anvil and
     * workbench. The Manager owns placement; the bot only executes. Applied when the directive
     * revision changes. Positions are stored as given (the Manager is authoritative); the delivery
     * container is reconciled through ensureDelivery and the drop-off chest through ensureInput so
     * the flagged box and the saved position agree.
     *
     * @return true when anything changed
     */
    boolean applyDirective(com.google.gson.JsonObject d, long revision) {
        if (ensureStash() == null || d == null) return false;
        // Monotonic per-stash revisions, surviving restarts via the stash file: the same
        // revision is a no-op (already applied), a lower one is rejected outright (stale or
        // replayed), and only a higher one is applied. Unknown revisions (-1) are applied
        // once, then pinned.
        long seen = stash.directiveRev;
        if (revision >= 0 && revision == seen) return false;
        if (revision >= 0 && seen >= 0 && revision < seen) {
            warning(
                    "Ignoring stale Manager directive (revision %d < applied %d) for stash '%s'.",
                    revision, seen, stash.name);
            return false;
        }
        boolean changed = false;
        net.minecraft.core.BlockPos p1 = vec(d.get("pos1"));
        net.minecraft.core.BlockPos p2 = vec(d.get("pos2"));
        if (p1 != null && p2 != null && !(p1.equals(stash.corner1) && p2.equals(stash.corner2))) {
            stash.corner1 = p1;
            stash.corner2 = p2;
            changed = true;
        }
        String dim = str(d.get("dimension"));
        if (dim != null && !dim.isEmpty() && !dim.equals(stash.areaDimension)) {
            stash.areaDimension = dim;
            changed = true;
        }
        if (d.has("delivery")) {
            net.minecraft.core.BlockPos del = d.get("delivery").isJsonNull() ? null : vec(d.get("delivery"));
            if (!java.util.Objects.equals(del, stash.deliveryPos)) {
                for (Box b : stash.boxes)
                    if (b.delivery && !b.deliveryExtra) {
                        b.delivery = false;
                        b.excluded = b.wasExcluded;
                        b.dropOff = b.wasDropOff;
                        b.wasExcluded = b.wasDropOff = false;
                    }
                stash.deliveryPos = del;
                stash.ensureDelivery();
                changed = true;
            }
        }
        if (d.has("dropoff")) {
            net.minecraft.core.BlockPos drop = d.get("dropoff").isJsonNull() ? null : vec(d.get("dropoff"));
            if (!java.util.Objects.equals(drop, stash.inputPos)) {
                // The Manager owns the drop-off selection: any hand-picked input chest gives way.
                for (Box b : stash.boxes)
                    if (b.input) {
                        b.input = false;
                        b.dropOff = false;
                    }
                stash.inputPos = drop;
                stash.inputSource = drop == null ? "" : "manager";
                stash.ensureInput();
                changed = true;
            }
        }
        if (d.has("anvil")) {
            net.minecraft.core.BlockPos a = d.get("anvil").isJsonNull() ? null : vec(d.get("anvil"));
            if (!java.util.Objects.equals(a, stash.anvilPos)) {
                stash.anvilPos = a;
                stash.anvilSource = a == null ? "" : "manager";
                changed = true;
            }
        }
        if (d.has("table")) {
            net.minecraft.core.BlockPos t = d.get("table").isJsonNull() ? null : vec(d.get("table"));
            if (!java.util.Objects.equals(t, stash.craftingTablePos)) {
                stash.craftingTablePos = t;
                stash.craftingTableSource = t == null ? "" : "manager";
                changed = true;
            }
        }
        if (changed) {
            stash.markDirty();
            stash.directiveRev = revision;
            stash.save();
            version++;
            if (stash.hasArea()) areaChanged();
            info("Manager directive applied (revision %d).", revision);
        } else if (revision >= 0) {
            stash.directiveRev = revision;
            stash.markDirty();
            stash.save();
        }
        return changed;
    }

    private static net.minecraft.core.BlockPos vec(com.google.gson.JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        var o = e.getAsJsonObject();
        Integer x = whole(o.get("x")), y = whole(o.get("y")), z = whole(o.get("z"));
        if (x == null || y == null || z == null) return null;
        return new net.minecraft.core.BlockPos(x, y, z);
    }

    private static Integer whole(com.google.gson.JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            return e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static String str(com.google.gson.JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /**
     * The perimeter was set or cleared: nothing counts as explored or unreachable any more, and an exploration under
     * way ends at once. With the module on, dropping containers outside it, adding the ones inside and the new order wait
     * until between jobs, so nothing being worked on changes under it.
     */
    private void areaChanged() {
        forgetExploration();
        if (job == Job.Explore) finishExplore();
        if (running()) {
            areaDirty = true;
            version++;
            idle = false;
        } else applyArea();
    }

    private void applyArea() {
        areaDirty = false;
        if (mc.player == null || mc.level == null || stash == null) return;
        // An area change drops only never-read containers outside it; read ones are kept and skipped
        pruneForeign("area change");
        addNearbyBoxes();
        orderBoxes();
        stash.save();
        version++;
        idle = false;
    }

    /**
     * Starts the exploration over, and forgets what went wrong: containers that could not be reached or opened, surveys
     * and jobs given up on, so everything gets another try.
     */
    private void forgetExploration() {
        exploredChunks.clear();
        exploreSkipped.clear();
        exploring = false;
        exploreRan = false;
        unreachableReported.clear();
        failedSurvey.clear();
        surveyRetry.clear();
        returnRetry.clear();
        futile.clear();
        noOpReported.clear();
        cancelRetries.clear();
        clearStalled();
        if (stash != null) {
            for (Box box : stash.boxes) {
                box.unreachable = false;
            }
        }
    }

    /**
     * Adds the containers inside the perimeter in every chunk that is loaded, and notes those chunks as seen. Goes through
     * the chunks' block entities directly.
     * @return how many containers were added
     */
    private int scanArea() {
        if (stash == null || !stash.hasArea() || mc.level == null) return 0;
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        boolean withBarrels = barrels.get();
        int added = 0;

        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                exploredChunks.add(ChunkPos.asLong(cx, cz));

                for (BlockEntity entity :
                        mc.level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(entity instanceof ChestBlockEntity || (withBarrels && entity instanceof BarrelBlockEntity)))
                        continue;
                    BlockPos pos = entity.getBlockPos();
                    if (!stash.inArea(pos) || stash.boxAt(pos) != null) continue;
                    stash.boxes.add(makeBox(pos.immutable()));
                    added++;
                }
            }
        }

        if (added > 0) {
            stash.markDirty();
            orderBoxes();
        }
        return added;
    }

    private int columnsTouched() {
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        return ((max.getX() >> 4) - (min.getX() >> 4) + 1) * ((max.getZ() >> 4) - (min.getZ() >> 4) + 1);
    }

    private List<Long> pendingChunks() {
        List<Long> list = new ArrayList<>();
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (!exploredChunks.contains(key) && !exploreSkipped.contains(key)) list.add(key);
            }
        }
        return list;
    }

    /** The middle of the part of the chunk column inside the perimeter, in X. */
    private int columnX(long key) {
        int cx = (int) (key & 0xFFFFFFFFL);
        return (Math.max(stash.areaMin().getX(), cx * 16)
                        + Math.min(stash.areaMax().getX(), cx * 16 + 15))
                / 2;
    }

    /** The middle of the part of the chunk column inside the perimeter, in Z. */
    private int columnZ(long key) {
        int cz = (int) (key >>> 32);
        return (Math.max(stash.areaMin().getZ(), cz * 16)
                        + Math.min(stash.areaMax().getZ(), cz * 16 + 15))
                / 2;
    }

    private void warnNoBaritone() {
        if (baritoneWarned) return;
        baritoneWarned = true;
        warning(
                "Baritone isn't installed, so I can't walk the stash area or explore it. Containers in loaded chunks are still used.");
    }

    /**
     * The checks made when turning on: enough containers. With a perimeter they wait for
     * the exploration, which is what finds the containers.
     * @return false if the module was turned off for failing one
     */
    private boolean checkStash() {
        long sortable =
                stash.boxes.stream().filter(b -> !b.excluded && !b.dropOff).count();
        if (keepOn() && (sorting() ? sortable < 2 : stash.boxes.isEmpty())) {
            if (!waitingForContainers) warning("Waiting for stash containers; checking again automatically.");
            waitingForContainers = true;
            nextDiscoveryTick = ticks + 20 * 30;
            return false;
        }
        waitingForContainers = false;
        if (!sorting()) {
            if (stash.boxes.isEmpty()) {
                error("Found no containers within %d blocks.", radius.get());
                turnOff();
                return false;
            }
        } else if (sortable < 2) {
            error(
                    "Found %d container%s to sort within %d blocks, need at least 2.",
                    sortable, sortable == 1 ? "" : "s", radius.get());
            turnOff();
            return false;
        }

        return true;
    }

    /**
     * With a perimeter set: walks to the nearest chunk column of it not seen loaded yet, so it loads and its containers
     * can be added. The goal is the middle of the part of the column inside the perimeter, in X and Z only. The activation
     * checks follow the exploration.
     * @return true when a trip has begun
     */
    private boolean startExplore() {
        if (stash == null || !stash.hasArea()) return false;
        scanArea();
        if (!BARITONE) {
            warnNoBaritone();
            return false;
        }

        if (!exploreRan) {
            exploreRan = true;
            exploreRunStart = ticks;
            exploring = true;
            info("Exploring the stash area: %d chunks to look at.", columnsTouched());
        }
        if (!exploring) return false;
        // One run takes half an hour at the most: whatever is still unseen is given up on, and the run is reported
        if (ticks - exploreRunStart > EXPLORE_RUN_LIMIT) {
            for (long key : pendingChunks()) exploreSkipped.add(key);
            info("Exploring the stash area took more than 30 minutes, carrying on with what was found.");
        }

        List<Long> pending = pendingChunks();
        if (pending.isEmpty()) {
            exploring = false;
            idle = false;
            orderBoxes();
            stash.save();
            long inside = stash.boxes.stream()
                    .filter(b -> b.parts.stream().anyMatch(stash::inArea))
                    .count();
            info(
                    "Explored the stash area: found %d containers (%d chunks couldn't be reached).",
                    inside, exploreSkipped.size());
            checkStash();
            return false;
        }

        Vec3 here = mc.player.position();
        long best = pending.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (long key : pending) {
            double dx = columnX(key) - here.x, dz = columnZ(key) - here.z;
            if (dx * dx + dz * dz < bestDistance) {
                bestDistance = dx * dx + dz * dz;
                best = key;
            }
        }

        exploreTarget = best;
        exploreX = columnX(best);
        exploreZ = columnZ(best);
        int chunksTotal = columnsTouched();
        int chunksDone = Math.max(0, chunksTotal - pending.size());
        stepProgress(
                "explore",
                chunksDone,
                chunksTotal,
                () -> String.format(
                        "exploring the area: %d/%d chunks; next: chunk at %d %d.",
                        chunksDone, chunksTotal, exploreX, exploreZ));
        exploreTicks = 0;
        exploreIssue = 0;
        exploreStanding = 0;
        exploreBest = Double.MAX_VALUE;
        exploreBestTick = 0;

        job = Job.Explore;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        walkXZ(exploreX, exploreZ);
        return true;
    }

    private void exploreTick() {
        exploreTicks++;
        if (stash == null || !stash.hasArea() || !BARITONE) {
            finishExplore();
            return;
        }
        if (exploreTicks % 5 == 0) scanArea();
        // Done as soon as the chunk is loaded
        if (exploredChunks.contains(exploreTarget)) {
            finishExplore();
            return;
        }

        Vec3 here = mc.player.position();
        double distance =
                Math.sqrt((here.x - exploreX) * (here.x - exploreX) + (here.z - exploreZ) * (here.z - exploreZ));
        if (distance < exploreBest - 1) {
            exploreBest = distance;
            exploreBestTick = exploreTicks;
        }
        exploreStanding = distance <= 2 ? exploreStanding + 1 : 0;

        String why = null;
        if (leftArea()) why = "the way there leaves the stash area";
        else if (exploreTicks > EXPLORE_TIMEOUT) why = "it took longer than 40 seconds";
        else if (exploreTicks - exploreBestTick > EXPLORE_STUCK) why = "no way closer for 15 seconds";
        else if (exploreStanding >= EXPLORE_STANDING) why = "it is still not loaded at the spot";
        if (why != null) {
            exploreSkipped.add(exploreTarget);
            int cx = (int) (exploreTarget & 0xFFFFFFFFL), cz = (int) (exploreTarget >>> 32);
            if (safeWalk.get() && !why.startsWith("the way"))
                debug(
                        "Can't reach the chunk at %d, %d without breaking or placing blocks, jumping or falling, skipping it.",
                        cx, cz);
            else debug("Skipping the chunk at %d, %d: %s.", cx, cz, why);
            finishExplore();
            return;
        }

        // Baritone stopped short, told again
        if (exploreTicks >= 10 && exploreTicks - exploreIssue >= 60 && !BaritoneHelper.isMoving()) {
            exploreIssue = exploreTicks;
            walkXZ(exploreX, exploreZ);
        }
    }

    private void finishExplore() {
        releaseGoal();
        if (job == Job.Explore) job = null;
        idle = false;
        wait = delay.get();
    }

    // Containers that can't be got to

    private static String at(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * What the block is called in messages: chest, trapped chest or barrel.
     */
    private String blockName(Box box) {
        if (mc.level == null) return "chest";
        var block = mc.level.getBlockState(box.pos).getBlock();
        if (block instanceof TrappedChestBlock) return "trapped chest";
        if (block instanceof BarrelBlock) return "barrel";
        return "chest";
    }

    private enum Cause {
        Path,
        Open,
        Area
    }

    /**
     * Says once per container why it is skipped.
     */
    private void reportUnreachable(Box box, Cause cause) {
        if (!unreachableReported.add(box.pos)) return;
        String name = blockName(box);
        switch (cause) {
            case Path -> {
                if (safeWalk.get())
                    alert(
                            "Can't reach the %s at %s without breaking or placing blocks, jumping or falling, skipping it.",
                            name, at(box.pos));
                else
                    alert(
                            "Can't reach the %s at %s without breaking or placing blocks, skipping it.",
                            name, at(box.pos));
            }
            case Open -> alert("Couldn't open the %s at %s, skipping it.", name, at(box.pos));
            case Area -> alert("Skipping the %s at %s: the way there leaves the stash area.", name, at(box.pos));
        }
    }

    private String areaText() {
        if (ensureStash() == null || (stash.corner1 == null && stash.corner2 == null))
            return "Stash area: none, containers are found around you (radius and height).";
        if (stash.hasCorners() && !stash.hasArea()) return "Stash area: set in another dimension, not used here.";
        if (!stash.hasArea())
            return "Stash area: corner "
                    + (stash.corner1 != null
                            ? "1 is set at " + stash.corner1.toShortString()
                            : "2 is set at " + stash.corner2.toShortString())
                    + ", the other is missing.";
        return "Stash area: " + stash.areaText() + ".";
    }

    /**
     * Outlines the stash perimeter. Also called by wesort-stash-overlay when this module is off.
     */
    public void drawArea(Render3DEvent event) {
        // The tracker keeps the stash loaded every tick, even with the module off: nothing is looked up per frame
        Stash current = stash;
        if (mc.level == null || current == null || !current.hasArea()) return;
        if (current.corner1 != boxCorner1 || current.corner2 != boxCorner2) {
            boxCorner1 = current.corner1;
            boxCorner2 = current.corner2;
            BlockPos min = current.areaMin(), max = current.areaMax();
            boxMin = new double[] {min.getX(), min.getY(), min.getZ()};
            boxMax = new double[] {max.getX() + 1, max.getY() + 1, max.getZ() + 1};
        }
        event.renderer.box(
                boxMin[0],
                boxMin[1],
                boxMin[2],
                boxMax[0],
                boxMax[1],
                boxMax[2],
                areaColor.get(),
                areaColor.get(),
                ShapeMode.Lines,
                0);
    }

    // The outline as last worked out, for the corners it was worked out for
    private BlockPos boxCorner1, boxCorner2;
    private double[] boxMin = new double[3], boxMax = new double[3];

    // The stash, kept up to date

    /**
     * The stash named in the settings, loaded from disk the first time it's needed on this server.
     */
    private Stash ensureStash() {
        if (mc.level == null) return null;

        String server = Stash.currentServer();
        if (stash != null && stash.name.equals(stashName()) && stash.server.equals(server)) return stash;

        // Only try the disk once per name and server, a missing stash isn't looked for again every tick
        String key = server + "/" + stashName();
        if (!key.equals(triedKey)) {
            triedKey = key;
            stash = Stash.load(stashName());
            pruneForeign("load");
            pruneStrays("load");
            // Another stash, name or server: its exploration and what failed with the last one don't carry over
            forgetExploration();
            areaDirty = false;
        } else stash = null;

        return stash;
    }

    /**
     * The saved stash named in the settings, for other modules like wesort-stash-overlay. Null if there isn't one here.
     */
    public boolean hasPhysicalOrder() {
        return orderRunner.hasPhysicalAllocation();
    }

    public boolean orderMayTakeFrom(String source, int slot, ItemStack stack) {
        return orderRunner.mayTakeFrom(source, slot, stack);
    }

    public boolean orderMayDepositAutomatically(String source) {
        return orderRunner.mayDepositAutomatically(source);
    }

    /** Stable job identity for world permits throughout one physical repack operation. */
    public String activeRepackJobId() {
        return repackWork != null && !repackWork.terminal() ? repackWork.id() : null;
    }

    /** A durable shulker/workspace checkpoint must finish before another order is claimed. */
    public boolean pendingPhysicalRecovery() {
        return !repackConflict.isEmpty()
                || pendingResume != null
                || stash != null && (stash.inFlight != null || stash.recoveredBox != null)
                || repackWork != null && !repackWork.terminal();
    }

    public com.google.gson.JsonObject physicalJournalContext() {
        var context = new com.google.gson.JsonObject();
        var manager = Modules.get().get(StashManager.class);
        boolean repack = repackWork != null && !repackWork.terminal();
        context.addProperty("server", Stash.currentServer());
        context.addProperty("dimension", StashIndex.dimension());
        context.addProperty("agent", mc.player.getUUID().toString());
        context.addProperty("kind", repack ? "REPACK" : "DIRECT");
        if (stash != null) context.addProperty("stash", stash.name);
        String lease = manager == null ? null : manager.physicalLeaseFingerprint();
        if (lease != null) context.addProperty("leaseFingerprint", lease);
        if (orderRunner.orderId() != null) {
            context.addProperty("orderId", orderRunner.orderId());
            if (stagingPackage) context.addProperty("execution", "STAGE_PACKAGE");
        }
        return context;
    }

    public void capturePhysicalInspection(BlockPos source) {
        if (stash == null || source == null) return;
        var box = stash.boxAt(source);
        if (box != null) read(box);
    }

    private void journalShulkerPhase(com.porter.wesort.inventory.PhysicalWork.Phase phase) {
        var evidence = new com.google.gson.JsonObject();
        if (placed != null) {
            var block = new com.google.gson.JsonObject();
            block.addProperty("x", placed.getX());
            block.addProperty("y", placed.getY());
            block.addProperty("z", placed.getZ());
            evidence.add("workspace", block);
        }
        PhysicalJournalRuntime.phase(phase.name(), evidence);
    }

    public Stash currentStash() {
        return ensureStash();
    }

    private class Tracker {
        // Driven by wesort-stash-manager the module's own handlers are not subscribed: the loop is ticked from here
        @EventHandler
        private void onEngineTick(TickEvent.Pre event) {
            if (mc.player == null || mc.level == null || isActive()) return;
            // Lobby entry is a navigation-only bootstrap: it cannot depend on an already loaded
            // stash, a sorting lease or an enabled warehouse job. In particular it runs on re-login.
            StashManager manager = Modules.get().get(StashManager.class);
            if (portalModeOn() && manager != null && manager.dedicatedProfile()) {
                // Probe for positive lobby evidence even if the user manually crossed the
                // portal. A survival world without a nearby lobby portal proceeds normally.
                // Manager authorizes transit by holding this client as dedicated agent.
                // Navigation moves only the player; inventory mutations stay fenced in
                // PhysicalJournalRuntime.ensure() + InventorySync.allowInput(). A fenced
                // journal is reported (not silently dropped) while transiting, cursor must
                // be empty before contact, and arrival schedules a fresh survey because
                // pre-transit readback is stale. Scope (server+dimension+agent) is unchanged
                // across lobby->survival (same overworld), so pending inspection survives.
                boolean fenced = PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror();
                boolean cursorEmpty = mc.player.containerMenu == mc.player.inventoryMenu
                        && mc.player.containerMenu.getCarried().isEmpty();
                if (fenced) {
                    long now = System.currentTimeMillis();
                    if (now - lastFencedTransitWarnedAt >= 60_000) {
                        lastFencedTransitWarnedAt = now;
                        WeSortAddon.LOG.warn(
                                "6b6t portal transiting with fenced journal (navigation-only, physical work parked, cursorEmpty={}).",
                                cursorEmpty);
                    }
                    // Fence stays visible via portalTick(); no pre-set here to avoid
                    // flapping against its preserved TRANSITING_WITH_FENCED_JOURNAL.
                }
                if (!cursorEmpty) {
                    portalForward(false);
                    if (portalAwaiting()) reportPortalBlocker("CURSOR_OCCUPIED");
                    return;
                }
                if (portalTick()) return;
            }
            if (portalHoldingForward && (manager == null || !manager.dedicatedProfile())) stopPortalNavigation();
            if (driven) StashSorter.this.onTick(event);
        }

        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            // Cancel only the exact Baritone goal WeSort owned before losing
            // its identity. Otherwise an old warehouse path can keep running
            // after a kick and resume in the proxy lobby on reconnect.
            releaseGoal();
            portalForward(false);
            driven = false;
            setUp = false;
            // Before anything is forgotten: what is carried and the shulker standing in the world are written down
            if (stash != null) {
                BlockPos standing = standingShulker();
                offline = true;
                syncInFlight(true);
                if (standing != null && stash.inFlight != null)
                    alert(
                            "Disconnected mid-job: shulker at %s kept, it will be finished on return.",
                            standing.toShortString());
                syncHeld();
            }
            if (stash != null && stash.isDirty()) stash.save();
            job = null;
            target = null;
            handle = null;
            placed = null;
            placedConfirmed = false;
            resuming = false;
            resumeVerify = false;
            pendingResume = null;
            syncedHandle = null;
            syncedPlaced = null;
            stash = null;
            repackConflict = "";
            triedKey = null;
            trackedMenu = null;
            trackedBox = null;
            interactPos = null;
            interactMenu = null;
            replies.clear();
            alertQueue.clear();
            worldTicks = 0;
            kitBuilder.reset();
            crafter.reset();
            craftForOrder = false;
            orderRunner.reset();
            orderHeld.clear();
            heldFrom.clear();
            returnGaveUp.clear();
            returnWarned.clear();
            stashHeld.clear();
            compactor.reset();
            swapBackFrom = swapBackTo = -1;
            hotbarDisplaced = ItemStack.EMPTY;
            stashWatch.clear();
            deliveryWatch.clear();
            ownTouch.clear();
            surveyAttemptAt.clear();
            surveyBatch.clear();
            forcedSurvey.clear();
            requestedSurvey.clear();
            nextIdleSurveyAt = nextBackgroundBatchAt = 0;
            dropBackoffUntil.clear();
            staleBoxes.clear();
            lastPlayer = null;
            ground.reset();
            forgetExploration();
            // A logout/disconnect re-arms the portal even if the previous login succeeded.
            portalForward(false);
            portalSession.reset();
            portalTarget = portalApproach = null;
            portalLastPosition = null;
            portalLastScan = 0;
            portalEntryTicks = portalStuckTicks = portalTouchTicks = 0;
            portalEntering = portalOtherSide = false;
            portalBlocker = null;
            portalWaitWarnedAt = 0;
            // Whatever walk was ours is over with the world, and Baritone gets its settings back
            ourGoal = null;
            if (BARITONE) BaritoneHelper.unconfine();
            ledgeGuard(false);
            Kits.clear();
        }

        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            // Whatever the player drops or breaks is theirs, from any source: the keys, clicks, other modules
            ground.onPacket(event.packet);
        }

        @EventHandler
        private void onInteract(InteractBlockEvent event) {
            if (mc.player == null) return;
            interactPos = event.result.getBlockPos();
            interactMenu = mc.player.containerMenu;
            interactClock = clock;
            // Whoever's hand it is, the lid that opens next is not another player's
            if (stash != null && stash.boxAt(interactPos) != null) ownTouch.put(stash.boxAt(interactPos), clock);
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (mc.player == null || mc.level == null) return;
            offline = false;
            clock++;
            worldTicks++;
            Stash.remember();
            if (mc.level != null) cachedDimension = mc.level.dimension().toString();
            pumpAlerts();
            ground.update();
            // Not walking for us: Baritone's settings are the user's own
            if (BARITONE) {
                if (ourGoal == null) BaritoneHelper.unconfine();
                // The user's own Baritone run took over our goal: their settings are theirs at once, not after the
                // yield
                else if (BaritoneHelper.isActive() && !BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) ourGoal))
                    BaritoneHelper.unconfine();
            }
            // Safeguard for the sneak: it never outlives the module
            if (ledgeSneaking && !running()) ledgeGuard(false);
            placer.restoreHand();
            restoreHotbar();

            // A new player entity means joining, dying or changing dimension: whatever was held is not ours to claim
            // now
            if (mc.player != lastPlayer) {
                if (lastPlayer != null) {
                    stashHeld.clear();
                    swapBackFrom = swapBackTo = -1;
                    hotbarDisplaced = ItemStack.EMPTY;
                    // Dead, or in another dimension: no prior physical work is carried into the new player session
                    placer.abandon();
                    chestSession = false;
                    chestHolding = false;
                    // A crafting table being gathered, crafted or placed is lost with the old player: whoever waited
                    // for it is told
                    boolean tableWork = tableSession || (placingTable && job == Job.AnvilPlace);
                    if (job == Job.Ground
                            || job == Job.Explore
                            || job == Job.Unbundle
                            || job == Job.Name
                            || job == Job.Craft
                            || job == Job.AnvilPlace
                            || job == Job.ChestPlace) job = null;
                    anvilSlot = -1;
                    anvilSession = false;
                    tableSession = false;
                    placingTable = false;
                    if (tableWork) {
                        if (crafter.active()) crafter.finish();
                        crafter.clearLastHeld();
                        craftForOrder = false;
                        tableWhy = TableFinder.failure("the player changed (respawn or dimension)");
                        tableAcquired(false);
                    }
                    // Another dimension or a new life: nothing was explored there, and the area may not be used at all
                    forgetExploration();
                    // Another world or dimension: the ground is another ground
                    ground.reset();
                }
                lastPlayer = mc.player;
            }

            if (replyCooldown > 0) replyCooldown--;
            else if (!replies.isEmpty()) {
                ChatUtils.sendPlayerMsg(replies.poll());
                replyCooldown = REPLY_GAP_TICKS;
            }

            if (ensureStash() == null) return;
            watchStash();

            // The sorter reads the containers it opens itself
            if (running() && job != null) return;

            AbstractContainerMenu menu = mc.player.containerMenu;
            if (menu instanceof ChestMenu) {
                if (menu != trackedMenu) {
                    trackedMenu = menu;

                    // Only a menu that opened after our last interact, from that block, and of that box's size
                    trackedBox = null;
                    if (interactPos != null && interactMenu != menu && clock - interactClock <= INTERACT_WINDOW) {
                        Box box = stash.boxAt(interactPos);
                        if (box != null && menu.slots.size() - 36 == box.parts.size() * 27) trackedBox = box;
                    }
                    interactPos = null;
                    interactMenu = null;
                }

                if (trackedBox != null && changed(trackedBox, menu)) {
                    read(trackedBox);
                    version++;
                }
            } else if (trackedMenu != null) {
                trackedMenu = null;
                trackedBox = null;
                if (stash.isDirty()) stash.save();
            }
        }

        @EventHandler
        private void onMessage(ReceiveMessageEvent event) {
            if (!whisperSearch.get() || mc.player == null) return;

            String text = event.getMessage().getString().replaceAll("§.", "").trim();
            for (Pattern format : WHISPER_FORMATS) {
                Matcher m = format.matcher(text);
                if (m.matches()) {
                    onWhisper(m.group(1), m.group(2).trim());
                    return;
                }
            }
        }

        @EventHandler
        private void onRender(Render3DEvent event) {
            if (isActive()) drawArea(event);
            if (!render.get() || stash == null) return;

            if (isActive() || pick.get() != Pick.Off) {
                for (Box box : stash.boxes) {
                    SettingColor color = box.excluded
                            ? excludedColor.get()
                            : box.dropOff ? dropOffColor.get() : box.noCompact ? compactSkipColor.get() : null;
                    if (color == null) continue;
                    for (BlockPos part : box.parts) event.renderer.box(part, color, color, shapeMode.get(), 0);
                }
            }

            Box working = target;
            if (isActive() && working != null) {
                for (BlockPos part : working.parts)
                    event.renderer.box(part, workColor.get(), workColor.get(), shapeMode.get(), 0);
            }
        }
    }

    private static boolean changed(Box box, AbstractContainerMenu menu) {
        int size = menu.slots.size() - 36;
        if (!box.known() || box.contents.length != size) return true;
        for (int i = 0; i < size; i++) {
            if (!ItemStack.matches(box.contents[i], menu.getSlot(i).getItem())) return true;
        }
        return false;
    }

    /** Whether the box was read recently enough, by wesort-stash-manager's fresh-minutes, to be trusted without reading it again. */
    private boolean fresh(Box box) {
        StashManager manager =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(StashManager.class);
        long window = (manager == null ? 5 : manager.freshMinutes()) * 60_000L;
        return com.porter.wesort.compat.ObservationRuntime.current(box)
                && window > 0
                && box.known()
                && box.lastRead > 0
                && System.currentTimeMillis() - box.lastRead < window;
    }

    private boolean read(Box box) {
        if (!com.porter.wesort.compat.ObservationRuntime.canRead(box)) {
            box.stale = true;
            return false;
        }
        if (stash.dimension.isEmpty() && mc.level != null) {
            stash.dimension = Stash.currentDimension();
            stash.markDirty();
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        boolean differs = changed(box, menu);
        if (differs) stash.markDirty();
        int size = menu.slots.size() - 36;
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) contents[i] = menu.getSlot(i).getItem().copy();
        box.contents = contents;
        box.stale = false;
        box.unreachable = false;
        com.porter.wesort.compat.ObservationRuntime.record(box);
        if (!box.stale) {
            forcedSurvey.remove(box);
            requestedSurvey.remove(box);
            dropChecked(box, differs);
        }
        stash.markDirty();
        if (differs && !box.stale && actions == 0) {
            version++;
            stash.save();
            if (stash.isDirty()) box.stale = true;
        }
        return !box.stale;
    }

    // Watching the stash

    /**
     * Every tick, even with the module off: container lids that other players use.
     */
    private void watchStash() {
        // Our own and the player's opens are not other players'
        if (orderWork() || returning) lastOrderWork = clock;
        if (running() && (job != null || handle != null)) lastOwnJob = clock;
        if (running() && target != null && job != null) ownTouch.put(target, clock);
        if (trackedBox != null) ownTouch.put(trackedBox, clock);

        if (watch.get() && clock % LID_POLL_TICKS == 0) {
            stashWatch.tickLids(stash, clock, watchDelay.get() * 20L, this::openedByUs, this::accessed);
        }
        // The delivery chests are watched whatever the setting says: a player who emptied one makes it usable again
        if (clock % LID_POLL_TICKS == 0 && !stash.deliveryStorage.isEmpty()) {
            deliveryWatch.tickLids(stash, clock, 20L * 5, this::openedByUs, this::deliveryTouched);
        }
    }

    /**
     * Whether the sorter or the player opened this container just now, as opposed to somebody else.
     */
    private boolean openedByUs(Box box) {
        Integer at = ownTouch.get(box);
        if (at != null && clock - at <= OWN_TOUCH_TICKS) return true;
        // Working on an order, a lid near the bot soon after one of its jobs is its own: a shulker put down or taken up
        // beside
        // a chest, a close that arrives late
        return clock - lastOrderWork <= OWN_TOUCH_TICKS * 2
                && clock - lastOwnJob <= OWN_TOUCH_TICKS * 2
                && mc.player != null
                && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box))) <= 12 * 12;
    }

    /**
     * Another player opened this container of the stash and has closed it again. It's read again through the normal
     * jobs while sorting, between jobs. With the module off it's only forgotten, to be read the next time it runs.
     */
    private void accessed(Box box) {
        // Safety first: another player may have changed the contents, so the box
        // must not be trusted until re-read. Manager direction: this is a fact
        // report (operator-visible info + dirty flag); re-read itself is still
        // scheduled via the existing survey path until manager-ordered READ lands.
        info("Another player used the container at %s; queued for re-read.", box.pos.toShortString());
        if (running()) staleBoxes.add(box);
        else box.stale = true;
        if (stash != null) stash.markDirty();
    }

    /**
     * Forgets what is known of the containers that were used, so they're read again. Between jobs, so nothing being
     * worked on changes under it.
     */
    private void applyStale() {
        if (staleBoxes.isEmpty()) return;
        for (Box box : new ArrayList<>(staleBoxes)) {
            staleBoxes.remove(box);
            if (!stash.boxes.contains(box) || box.excluded) continue;

            box.stale = true;
            clearStalled();
            compacted.remove(box);
            // A lid event does not reset the retry or survey cooldown.
            stash.markDirty();
            idle = false;
            debug("A player used the chest at %s, queued for the next eligible survey batch.", box.pos.toShortString());
        }
    }

    // Searching

    private void onWhisper(String sender, String message) {
        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (whitelist.get().stream().noneMatch(name -> name.equalsIgnoreCase(sender))) return;

        String query = message;
        String p = prefix.get().trim();
        if (!p.isEmpty()) {
            if (!message.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT) + " ")) return;
            query = message.substring(p.length()).trim();
        }
        if (query.isEmpty() || ensureStash() == null) return;

        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) {
            replies.add(String.format("/whisper %s Couldn't find %s @ %s", sender, query, stash.name));
            return;
        }

        Stash.Result result = results.getFirst();
        Stash.Spot spot = result.best();
        BlockPos pos = spot.box().pos;
        String where = spot.inShulker() ? " (in a shulker)" : "";
        replies.add(String.format(
                "/whisper %s %s Can be Found At %d, %d, %d @ %s%s",
                sender, StackInfo.label(result.sample), pos.getX(), pos.getY(), pos.getZ(), stash.name, where));
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WLabel status = list.add(theme.label(statusText())).expandX().widget();
        list.add(theme.label("Compacting: " + (sortItems.get() ? compactor.status() : "off, scan only") + "."))
                .expandX();
        list.add(theme.label("Ground: " + groundStatus() + ".")).expandX();

        WLabel area = list.add(theme.label(areaText())).expandX().widget();
        WHorizontalList areaButtons = list.add(theme.horizontalList()).widget();
        WButton corner1 = areaButtons.add(theme.button("Set corner 1 here")).widget();
        corner1.tooltip = "Makes the block you are standing in corner 1 of the stash perimeter.";
        corner1.action = () -> {
            if (mc.player != null) setCorner(1, mc.player.blockPosition());
            area.set(areaText());
        };
        WButton corner2 = areaButtons.add(theme.button("Set corner 2 here")).widget();
        corner2.tooltip = "Makes the block you are standing in corner 2 of the stash perimeter.";
        corner2.action = () -> {
            if (mc.player != null) setCorner(2, mc.player.blockPosition());
            area.set(areaText());
        };
        WButton clearArea = areaButtons.add(theme.button("Clear area")).widget();
        clearArea.tooltip = "Removes the stash perimeter, so containers are found around you again.";
        clearArea.action = () -> {
            clearArea();
            area.set(areaText());
        };

        if (ensureStash() != null) {
            for (Stash.Orphan orphan : stash.orphans) {
                list.add(theme.label(String.format(
                                "Orphaned shulker: %d stacks of %s at %s (since %s). Pick it up by hand.",
                                orphan.stacks, orphan.item, orphan.pos.toShortString(), orphan.when)))
                        .expandX();
            }
        }

        WHorizontalList buttons = list.add(theme.horizontalList()).widget();
        WButton rescan = buttons.add(theme.button("Forget contents")).widget();
        rescan.tooltip =
                "Forgets what's in every container, so the next run reads them all again, nearest first. Keeps the containers, excluded and drop-off marks and kits.";
        rescan.action = () -> {
            if (ensureStash() == null) return;
            for (Box box : stash.boxes) box.contents = null;
            // The perimeter is explored again too, and containers that couldn't be reached get another try
            forgetExploration();
            stash.save();
            version++;
            idle = false;
            status.set(statusText());
        };

        WButton unstuck = buttons.add(theme.button("Unstuck")).widget();
        unstuck.tooltip =
                "Drops whatever the sorter is doing and starts over. A shulker step that was under way is not resumed: a shulker of ours still standing is left where it is and listed with the orphaned shulkers.";
        unstuck.action = () -> {
            unstuck();
            status.set(statusText());
        };

        WButton forget = buttons.add(theme.button("Forget stash")).widget();
        forget.tooltip = "Deletes the saved stash, including excluded and drop-off marks.";
        forget.action = () -> {
            Stash.delete(stashName());
            stash = null;
            triedKey = null;
            status.set(statusText());
        };

        list.add(theme.horizontalSeparator("Search"));
        WTextBox search = list.add(theme.textBox("")).expandX().widget();
        WTable results = list.add(theme.table()).expandX().widget();
        search.action = () -> fillResults(theme, results, search.get());

        return list;
    }

    private String statusText() {
        if (mc.level == null) return "Join a server to see its stashes.";
        if (ensureStash() == null)
            return String.format("No stash called \"%s\" here yet. Turn the module on to scan one.", stashName());

        long excluded = stash.boxes.stream().filter(b -> b.excluded).count();
        long dropOffs = stash.boxes.stream().filter(b -> b.dropOff).count();
        return String.format(
                "\"%s\": %d containers (%d read, %d excluded, %d drop-off), %,d items.%s",
                stash.name,
                stash.boxes.size(),
                stash.knownCount(),
                excluded,
                dropOffs,
                stash.itemCount(),
                sortItems.get() ? "" : " Scan only.");
    }

    private String groundStatus() {
        if (!sortItems.get()) return "off (scan only)";
        if (!collectGround.get() || !sorting()) return "off";
        if (!BARITONE) return "off, Baritone isn't installed";
        return ground.status();
    }

    private void fillResults(GuiTheme theme, WTable table, String query) {
        table.clear();
        if (ensureStash() == null) {
            table.add(theme.label("No stash loaded."));
            return;
        }

        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) {
            if (!Categories.normalize(query).isEmpty()) table.add(theme.label("Nothing found."));
            return;
        }

        for (Stash.Result result : results.subList(0, Math.min(25, results.size()))) {
            Stash.Spot best = result.best();
            BlockPos pos = best.box().pos;
            int more = result.spots.size() - 1;

            table.add(theme.item(result.sample));
            table.add(theme.label(String.format("%s x%,d", StackInfo.label(result.sample), result.total)))
                    .expandX();
            table.add(theme.label(String.format(
                    "%d, %d, %d%s%s",
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    best.inShulker() ? " (shulker)" : "",
                    more > 0 ? String.format(" +%d more", more) : "")));

            WButton view = table.add(theme.button("View")).widget();
            view.action = () -> ItemPreviewScreen.open(result.sample, result.total, stash.name);
            table.row();
        }
    }

    // Helpers

    /**
     * Inventory slots holding stash items we're carrying: not the player's own gear, food, totems and pearls, unless the
     * sorter put them there itself.
     */
    private List<Integer> carriedSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot : carry) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || isOwn(slot, stack)) continue;
            slots.add(slot);
        }
        return slots;
    }

    /**
     * Carried stacks that something could take right now, not the ones already found to have nowhere to go.
     */
    private List<Integer> activeCarried() {
        List<Integer> slots = new ArrayList<>();
        Set<Integer> setAside = compactor.reserved();
        for (int slot : carriedSlots()) {
            // What compacting is gathering and packing is not put away
            if (setAside.contains(slot)) continue;
            // A bundle with things in it is emptied, not put away
            if (fullBundle(mc.player.getInventory().getItem(slot))
                    || orderRunner.claims(mc.player.getInventory().getItem(slot))) continue;
            // The shulker an order is being built in
            if (orderKit() && kitBuilder.holds(slot)) continue;
            // What a craft gathered, and the boxes it made for an order
            if (crafter.holds(slot) || crafter.reserved(slot) || slot == anvilSlot) continue;
            // The chests gathered for the delivery storage, until they are put up
            if (chestHolding && plainChest(mc.player.getInventory().getItem(slot))) continue;
            if (!idOf(mc.player.getInventory().getItem(slot)).equals(stuckCarried.get(slot))) slots.add(slot);
        }
        return slots;
    }

    /**
     * The carry slots, less the ones holding the player's own gear.
     */
    private Set<Integer> ownedCarry() {
        Set<Integer> slots = new LinkedHashSet<>();
        for (int slot : carry) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !isOwn(slot, stack)) slots.add(slot);
        }
        // Not sorting, only what an order itself took counts as carried: the rest of the slots are the player's
        if (!sorting())
            slots.removeIf(slot -> !mc.player.getInventory().getItem(slot).isEmpty() && !orderHeld.containsKey(slot));
        return slots;
    }

    private boolean isOwn(int slot, ItemStack stack) {
        // Temporarily moved out of the way of a shulker
        if (slot == swapBackFrom && swapBackFrom != -1) return true;
        if (!ownKind(stack)) return false;
        ItemStack held = stashHeld.get(slot);
        return held == null || !ItemStack.isSameItemSameComponents(stack, held);
    }

    /**
     * Between jobs, with no screen open: a slot we filled that is now empty or holds something else is no longer ours.
     */
    private void pruneHeld() {
        if (mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()) return;
        stashHeld.entrySet().removeIf(entry -> {
            ItemStack stack = mc.player.getInventory().getItem(entry.getKey());
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, entry.getValue())) return true;
            // Keep the count as it is now, so a later start only takes back exactly what was left here
            entry.setValue(stack.copy());
            return false;
        });
    }

    /**
     * What the player keeps for themselves: tools, weapons, armor, food, totems and ender pearls.
     */
    private static boolean ownKind(ItemStack stack) {
        String id = idOf(stack);
        if (stack.has(DataComponents.FOOD) || id.equals("totem_of_undying") || id.equals("ender_pearl")) return true;
        for (String suffix : OWN_SUFFIXES) {
            if (id.endsWith(suffix)) return true;
        }
        return switch (id) {
            case "bow", "crossbow", "trident", "shield", "fishing_rod", "shears", "elytra", "mace", "flint_and_steel" ->
                true;
            default -> false;
        };
    }

    private static final List<String> OWN_SUFFIXES =
            List.of("_sword", "_axe", "_pickaxe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots");

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private int freeCarrySlot() {
        for (int slot : carry) if (mc.player.getInventory().getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /**
     * Where inventory slot {@code index} sits in a container menu: the main inventory follows the container's slots,
     * then the hotbar.
     */
    private static int inventoryToMenu(int index, int containerSize) {
        return index < 9 ? containerSize + 27 + index : containerSize + index - 9;
    }

    private boolean inReach(Box box) {
        Vec3 eyes = mc.player.getEyePosition();
        double reachSq = reach.get() * reach.get();
        for (BlockPos part : box.parts) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(part)) <= reachSq) return true;
        }
        return false;
    }

    /** Any half of a double chest counts: the box is loaded if any of its parts is. */
    private boolean anyPartLoaded(Box box) {
        for (BlockPos part : box.parts) if (mc.level.isLoaded(part)) return true;
        return false;
    }

    /** Whether any half of this box is still a chest/barrel block in a loaded chunk. */
    private boolean anyContainerBlockHere(Box box) {
        boolean seenLoaded = false;
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) continue;
            seenLoaded = true;
            if (containerBlockHere(part)) return true;
        }
        return !seenLoaded && containerBlockHere(box.pos);
    }

    /** Whether any half of this box is a usable container under the current settings. */
    private boolean anyIsContainer(Box box) {
        boolean seenLoaded = false;
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) continue;
            seenLoaded = true;
            if (isContainer(part)) return true;
        }
        return !seenLoaded && isContainer(box.pos);
    }

    /**
     * The blocks making up this box right now, read from whichever half is loaded and still a
     * container. Falls back to the primary half so a fully gone box keeps a stable answer.
     */
    private List<BlockPos> currentParts(Box box) {
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) continue;
            if (!containerBlockHere(part) && !isContainer(part)) continue;
            List<BlockPos> now = makeBox(part).parts;
            // makeBox from the secondary half returns [secondary, primary]; normalise to the same
            // set comparison the callers use, keeping the primary first when it is part of it.
            if (now.contains(box.pos)) {
                List<BlockPos> ordered = new ArrayList<>();
                ordered.add(box.pos);
                for (BlockPos p : now) if (!p.equals(box.pos)) ordered.add(p);
                return ordered;
            }
            return now;
        }
        if (mc.level.isLoaded(box.pos)) return makeBox(box.pos).parts;
        return new ArrayList<>(box.parts);
    }

    private BlockPos nearestPart(Box box) {
        Vec3 eyes = mc.player.getEyePosition();
        return box.parts.stream()
                .min(Comparator.comparingDouble(p -> eyes.distanceToSqr(Vec3.atCenterOf(p))))
                .orElse(box.pos);
    }

    private Box nearest(List<Box> list) {
        Vec3 eyes = mc.player.getEyePosition();
        return list.stream()
                .min(Comparator.comparingDouble(b -> eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(b)))))
                .orElse(null);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, StashSorter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
