-- ============================================================================
-- config.lua — Centralized Configuration (V3)
-- ============================================================================
--
-- WHAT THIS FILE DOES:
--   This is the single "settings" file for the entire Create Factory Control
--   System.  Every other Lua program in the v3/ folder reads its settings
--   from here, so you only need to change values in one place.
--
-- HOW IT WORKS:
--   In Lua, a "table" is like a container that holds named values (similar
--   to a settings list).  We create an empty table called "config", fill it
--   with settings, and then "return" it at the bottom so other programs can
--   use it by writing:
--       local config = require("v3.config")
--
-- HOW TO EDIT:
--   • Open this file on your ComputerCraft computer (or edit it on disk).
--   • Change ONLY the values on the right side of the "=" sign.
--   • Strings (text) must stay inside "quotes".
--   • Numbers have no quotes.
--   • true / false are boolean (on / off) switches — no quotes.
--   • Save the file, then reboot the computer (Ctrl+R in CC) to apply.
--
-- IMPORTANT:
--   Do NOT rename the variable "config" or remove the "return config" at
--   the bottom — doing so will break every program that depends on this file.
--
-- DEPENDENCIES:
--   • LogicLink mod (required)  — provides inventory, gauge, sensor, and
--     redstone-link peripherals that this system reads from.
--   • Advanced Peripherals (optional) — only needed if you enable the
--     chat box or player detector features.
--
-- ============================================================================

-- Create an empty table to hold all of our settings.
-- A "table" in Lua is like a dictionary or settings list: it stores
-- key = value pairs that other programs can look up by name.
local config = {}

-- ============================================================================
-- SYSTEM IDENTITY
-- ============================================================================
-- These identify the software version and give your factory a display name.
-- The version string is shown on screen so you know which release is running.
config.VERSION = "3.0.0"
config.FACTORY_NAME = "Create Factory Control System"

-- ============================================================================
-- PERIPHERAL TYPES
-- ============================================================================
-- "Peripherals" are blocks or devices attached to (or near) your
-- ComputerCraft computer.  Each peripheral has a "type" — a short string
-- the computer uses to find it.  The values below must match the type
-- strings registered by the mods you have installed.
--
-- LogicLink peripherals (the main mod this system is built on):
config.LOGICLINK = "logiclink"            -- The LogicLink block: gives access to inventory counts,
                                          --   gauge readings, item requests, and sensor data.
config.TRAIN_CONTROLLER = "train_controller"  -- Reads data from the Create train network
                                              --   (schedules, positions, alerts, etc.).

-- Advanced Peripherals (optional — only needed if you place these blocks):
config.CHAT_BOX = "chat_box"             -- Lets the computer send/receive Minecraft chat messages.
                                          -- Used for in-game alert notifications.
config.PLAYER_DETECTOR = "playerDetector" -- Detects nearby players; can be used for auto-display
                                          -- wake-up or access control.

-- ============================================================================
-- MONITOR ASSIGNMENTS
-- ============================================================================
-- Monitors are the large screens you attach to the computer.  Each monitor
-- gets a "name" that ComputerCraft assigns automatically:
--   • If the monitor is directly on a side of the computer, the name is
--     that side: "top", "bottom", "left", "right", "front", or "back".
--   • If the monitor is connected via a wired modem (networking cable),
--     the name looks like "monitor_0", "monitor_1", etc.
--
-- You can find a monitor's name by right-clicking the modem attached to it
-- (the modem will print the peripheral name in chat).
--
-- Change these values to match YOUR monitor setup:
config.MONITOR_MAIN = "top"               -- Main factory display (sits on top of the computer)
config.MONITOR_BOILER = "monitor_20"      -- Shows boiler / steam engine sensor data
config.MONITOR_MACHINES = "monitor_19"    -- Shows machine status (presses, mixers, etc.)
config.MONITOR_REQUEST = "monitor_18"     -- Shows the item request / delivery log

-- ============================================================================
-- WIRELESS CONFIGURATION
-- ============================================================================
-- These settings control how computers talk to each other wirelessly using
-- modems.  A "channel" is like a radio frequency — both sender and receiver
-- must be tuned to the same channel number to communicate.
--
-- If you only have ONE factory computer, you can leave these as-is.
-- If you have multiple systems, give each one a unique channel to avoid
-- cross-talk.
config.WIRELESS_CHANNEL = 65535           -- The channel number used for broadcasts (0–65535).
                                          -- 65535 is the maximum value, chosen to avoid
                                          -- conflicts with other systems.
config.SERVER_COMPUTER_ID = 31            -- The ComputerCraft "ID" of the main factory controller.
                                          -- You can find a computer's ID by running "id" in its
                                          -- terminal, or by checking the label on the computer block.
config.COMPUTER_CHANNEL = 2               -- Legacy reply channel; kept for backward compatibility
                                          -- with older pocket-computer programs.
config.WIFI_TIMEOUT = 15000               -- Milliseconds (15 000 ms = 15 seconds).
                                          -- If a wireless client hasn't been heard from in this
                                          -- long, it is considered disconnected.

-- ============================================================================
-- REDSTONE CONTROLLER (Create Redstone Links via LogicLink)
-- ============================================================================
-- Create's "Redstone Link" blocks transmit a redstone signal wirelessly
-- between two points.  Each link uses a two-item "frequency" to pair
-- sender and receiver — think of it like a lock that needs two keys.
--
-- LogicLink exposes these links as a peripheral so the computer can
-- turn them on/off from code.
config.REDSTONE_CONTROLLER = "redstone_controller"  -- Peripheral type name for the
                                                     -- LogicLink redstone controller block.
config.REDSTONE_FREQ1 = "create:cogwheel"   -- First item in the frequency pair (Minecraft item ID).
config.REDSTONE_FREQ2 = "create:cogwheel"   -- Second item in the frequency pair.
                                             -- Together, freq1 + freq2 uniquely identify
                                             -- which Redstone Link to control.

-- ============================================================================
-- REFRESH RATES (in seconds)
-- ============================================================================
-- How often each subsystem updates its data.  Lower numbers = faster updates
-- but more CPU/network work.  Higher numbers = less load but slower response.
-- A value of 5 means "run this task once every 5 seconds."
config.REFRESH_RATES = {
    inventory = 5,      -- How often to re-scan all inventories for item counts
    trains = 3,         -- How often to poll the train network for status updates
    broadcast = 5,      -- How often to broadcast status to wireless clients (pocket computers)
    config_cache = 30,  -- How often to re-read the config / set_amount file from disk
                        --   (so you can edit targets without rebooting)
    gauges = 10,        -- How often to read boiler gauges, stress meters, etc.
}

-- ============================================================================
-- REQUEST SYSTEM
-- ============================================================================
-- The request system lets you (or a pocket computer) ask the factory to
-- deliver items.  These settings control how requests are processed.
config.REQUEST_DESTINATION = "player"     -- Where delivered items go.
                                          --   "player" = directly to the player's inventory.
config.ALLOW_PARTIAL = false              -- If true, the system will deliver whatever quantity
                                          --   it has, even if less than requested.
                                          -- If false, it will only deliver if the full amount
                                          --   is available.
config.REQUEST_COOLDOWN = 1               -- Minimum seconds between consecutive requests.
                                          --   Prevents spamming the system.
config.MAX_QUEUE_SIZE = 10                -- Maximum number of requests that can be waiting
                                          --   in the queue at the same time.
config.DELIVERY_CLUTCH_TIME = 10          -- After an item request, the conveyor clutch (a Create
                                          --   mechanical component) stays engaged for this many
                                          --   seconds to allow the items to travel the belt.

-- ============================================================================
-- REDSTONE LINK PRESETS
-- ============================================================================
-- Presets are pre-configured Redstone Link channels that appear as quick-
-- access buttons on the monitor.  Each preset has:
--   name     — The label shown on screen
--   freq1    — First frequency item (Minecraft item ID, e.g. "create:cogwheel")
--   freq2    — Second frequency item
--   color    — The CC color used for the button (see: https://tweaked.cc/module/colors.html)
--   desc     — A short description shown as a tooltip
--   inverted — (optional) If true, the signal is ON by default and turns OFF
--              when activated.  Useful for clutches that should normally spin.
--
-- To add a new preset, copy one of the example lines below, uncomment it
-- (remove the leading "--"), and fill in your own values.
config.REDSTONE_PRESETS = {
    {name = "Clutch",   freq1 = "create:cogwheel", freq2 = "create:cogwheel", color = colors.lime,   desc = "Main conveyor clutch", inverted = true},
    -- Add more presets here:
    -- {name = "Furnaces", freq1 = "minecraft:redstone", freq2 = "minecraft:coal", color = colors.orange, desc = "Smelter array"},
    -- {name = "Lamps",    freq1 = "minecraft:redstone", freq2 = "minecraft:glowstone", color = colors.yellow, desc = "Factory lighting"},
}

-- File paths for persistent data (saved to the computer's virtual file system):
config.REQUEST_LOG_FILE = "v3/request_log.dat"  -- Stores the history of item requests so the
                                                 --   request log tab can display past deliveries.
config.RS_CHANNELS_FILE = "v3/rs_channels.dat"  -- Stores redstone channels that you create at
                                                 --   runtime (not presets above, but user-added ones).

-- ============================================================================
-- ALERT CONFIGURATION
-- ============================================================================
-- Alerts warn you when something goes wrong (e.g., a train is stuck).
-- These settings control how and when alerts fire.
config.ALERT_COOLDOWN = 60                -- Seconds between repeated alerts for the SAME problem.
                                          --   Prevents chat spam when an issue persists.
config.CHAT_ALERTS_ENABLED = true         -- If true, critical alerts are sent to Minecraft chat
                                          --   via the Chat Box peripheral (requires Advanced Peripherals).
config.TRAIN_ALERTS_ENABLED = true        -- If true, train-related alerts are active.
                                          --   Set to false if you don't use Create trains.

-- ============================================================================
-- TRAIN ALERT THRESHOLDS
-- ============================================================================
-- Create trains can get stuck at signals or encounter routing problems.
-- These thresholds control when the system reports a problem.
config.TRAIN_SIGNAL_BLOCK_WARN = 5        -- If a train is waiting at a signal for more than this
                                          --   many seconds, show a WARNING (yellow).
config.TRAIN_SIGNAL_BLOCK_CRIT = 60       -- If a train is waiting at a signal for more than this
                                          --   many seconds, escalate to CRITICAL (red).

-- List of train alert types the system watches for.
-- These correspond to specific error conditions in the Create train network.
-- You generally don't need to change this list unless you want to ignore
-- certain alert types.
config.TRAIN_ALERT_TYPES = {
    "SCHEDULE_INVALID_DEST",              -- A train's schedule points to a station that doesn't exist
    "TRAIN_SIGNAL_BLOCKED",               -- A train is stuck waiting at a signal
    "DEADLOCK",                           -- Two or more trains are blocking each other
    "ROUTE_UNSIGNALED",                   -- A section of track has no signals (risky for collisions)
    "STATION_UNREACHABLE",                -- A station exists but no track path leads to it
    "NO_PATH",                            -- The train cannot find any route to its destination
    "SIGNAL_CONFLICT",                    -- Two signals are giving contradictory instructions
    "JUNCTION_UNSIGNALED",                -- A track junction (split/merge) has no signal to manage traffic
}

-- ============================================================================
-- DISPLAY CONFIGURATION
-- ============================================================================
-- Controls how text is sized on monitors.
-- ComputerCraft monitors support text scaling from 0.5 (tiny) to 5.0 (huge).
-- AUTO_SCALE lets the system pick the best size based on monitor dimensions.
config.AUTO_SCALE = true                  -- If true, the system automatically picks a text scale
                                          --   that fits nicely on each monitor.
config.MIN_TEXT_SCALE = 0.5               -- The smallest text scale the auto-scaler will use.
                                          --   0.5 = very small text, fits lots of data.
config.MAX_TEXT_SCALE = 1.5               -- The largest text scale the auto-scaler will use.
                                          --   1.5 = large text, easy to read from a distance.

-- ============================================================================
-- COLOR THEMES
-- ============================================================================
-- These define the colors used throughout the UI for different types of
-- information.  ComputerCraft has a fixed palette of 16 colors — you can
-- see all available colors at: https://tweaked.cc/module/colors.html
--
-- "header"        — Color for section titles and headers
-- "success"       — Color for "everything is OK" messages
-- "warning"       — Color for "something might be wrong" messages
-- "error"         — Color for "something is definitely wrong" messages
-- "info"          — Color for neutral informational text
-- "background"    — Main screen background color
-- "backgroundAlt" — Alternate background (used for striped rows, etc.)
-- "text"          — Normal text color
-- "textDim"       — Dimmed/secondary text (less important info)
config.COLORS = {
    header      = colors.lightBlue,
    success     = colors.green,
    warning     = colors.orange,
    error       = colors.red,
    info        = colors.cyan,
    background  = colors.black,
    backgroundAlt = colors.gray,
    text        = colors.white,
    textDim     = colors.lightGray,
}

-- ============================================================================
-- CATEGORY COLORS (by Minecraft mod namespace)
-- ============================================================================
-- Items in Minecraft have IDs like "create:cogwheel" or "minecraft:iron_ingot".
-- The part before the colon is the "namespace" — it tells you which mod added
-- the item.  These colors are used on the inventory display to visually
-- group items by their source mod.
--
-- If an item's namespace isn't listed here, the "default" color is used.
config.CAT_COLORS = {
    minecraft = colors.green,             -- Vanilla Minecraft items (iron, diamonds, etc.)
    create    = colors.purple,            -- Create mod items (gears, shafts, etc.)
    thermal   = colors.orange,            -- Thermal series mod items
    mekanism  = colors.cyan,             -- Mekanism mod items
    allthemodium = colors.yellow,         -- AllTheModium items (rare ores from ATM packs)
    default   = colors.blue,             -- Fallback for any mod not listed above
}

-- ============================================================================
-- BOILER DISPLAY THRESHOLDS
-- ============================================================================
-- These thresholds control when the boiler monitor changes color to warn you
-- about problems.  Values are percentages (%) unless noted otherwise.
--
-- For example, if water level drops below 30%, the display turns yellow
-- (warning).  If it drops below 15%, it turns red (critical).
config.BOILER_THRESHOLDS = {
    water_warning  = 30,   -- Water level below this % → yellow warning
    water_critical = 15,   -- Water level below this % → red critical alert
    heat_warning   = 20,   -- Heat level below this % → low heat warning
    stress_warning = 70,   -- Stress capacity above this % → caution (getting high)
    stress_critical = 90,  -- Stress capacity above this % → danger (near breaking)
    speed_max      = 256,  -- Maximum RPM used for scaling the speed bar display.
                           --   256 RPM is the max speed in most Create setups.
}

-- ============================================================================
-- MACHINE CATEGORY CLASSIFICATION
-- ============================================================================
-- This section tells the machine monitor how to group Create machines into
-- display categories.  Each entry has:
--   name     — The label shown on the monitor (e.g., "Presses")
--   color    — The color used for that category's display row
--   patterns — A list of text fragments to match against a machine's block ID
--
-- HOW MATCHING WORKS:
--   When the system finds a machine, it checks the machine's block ID
--   (e.g., "create:mechanical_press") against each category's patterns.
--   If the block ID CONTAINS any of the pattern strings, it goes in that
--   category.  The first matching category wins, so order matters!
--
-- EXAMPLE:
--   A block with ID "create:mechanical_press" contains the substring
--   "mechanical_press", so it matches the "Presses" category.
config.MACHINE_CATEGORIES = {
    { name = "Crafters",  color = colors.yellow,    patterns = {"mechanical_crafter"} },
    { name = "Presses",   color = colors.cyan,      patterns = {"mechanical_press", "compacting"} },
    { name = "Mixers",    color = colors.purple,    patterns = {"mechanical_mixer", "basin"} },
    { name = "Crushers",  color = colors.orange,    patterns = {"crushing_wheel", "millstone"} },
    { name = "Saws",      color = colors.brown,     patterns = {"mechanical_saw", "saw"} },
    { name = "Deployers", color = colors.lime,      patterns = {"deployer"} },
    { name = "Arms",      color = colors.lightBlue, patterns = {"mechanical_arm"} },
    { name = "Fans",      color = colors.lightGray, patterns = {"encased_fan", "fan"} },
    { name = "Drills",    color = colors.red,       patterns = {"mechanical_drill", "drill"} },
    { name = "Belts",     color = colors.green,     patterns = {"belt", "chute", "funnel", "depot"} },
    { name = "Kinetic",   color = colors.yellow,    patterns = {"gearbox", "shaft", "cog", "speed_controller", "clutch", "gantry"} },
    { name = "Storage",   color = colors.blue,      patterns = {"vault", "barrel", "chest", "crate"} },
}

-- The order in which machine categories appear on the monitor.
-- Categories that have zero machines detected are automatically hidden.
-- "Other" is a catch-all for any machine that didn't match the patterns above.
config.MACHINE_CATEGORY_ORDER = {
    "Crafters", "Presses", "Mixers", "Crushers", "Saws",
    "Deployers", "Arms", "Fans", "Drills", "Belts",
    "Kinetic", "Storage", "Other",
}

-- ============================================================================
-- DEBUG SETTINGS
-- ============================================================================
-- These are for troubleshooting.  In normal use, leave DEBUG_MODE as false.
config.DEBUG_MODE = false                 -- If true, the system prints extra diagnostic info
                                          --   to the terminal and writes detailed logs to disk.
                                          --   Turn this on when something isn't working and you
                                          --   need to see what the code is doing step-by-step.
config.LOG_FILE = "factory_debug.log"     -- File path where debug messages are saved.
                                          --   You can read this file with: edit factory_debug.log

-- ============================================================================
-- ITEM TARGET FILE
-- ============================================================================
-- The "set_amount" file lists the items your factory should keep in stock
-- and how many of each.  The system reads this file to know what target
-- quantities to display on the main monitor.
--
-- You can edit the set_amount file separately — see the project README for
-- the expected format.
config.TARGET_FILE = "set_amount"

-- ============================================================================
-- Return the config table so other programs can use it.
-- In Lua, "require" runs a file and gets back whatever that file "return"s.
-- Without this line, no other program could access these settings!
-- ============================================================================
return config
