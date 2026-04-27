-- ============================================================================
-- startup.lua — Create Factory Controller V3.0.0
-- ============================================================================
--
-- WHAT THIS FILE DOES:
--   This is the MAIN PROGRAM that runs on the factory controller computer
--   (Computer #31).  It is the "brain" of the factory automation system.
--   When the computer boots, this script starts and runs forever, doing:
--
--   1. INVENTORY MONITORING — Polls LogicLink every few seconds to get
--      current item counts and gauge targets.  Determines which items
--      are below their target ("low") and need restocking.
--
--   2. MONITOR DISPLAY — Renders a multi-column dashboard on the main
--      monitor showing all tracked items with color-coded stock levels
--      (green = stocked, orange = low, red = empty).
--
--   3. REDSTONE CONTROL — Controls Create Redstone Links via the
--      redstone_controller peripheral.  In Auto mode, the conveyor
--      clutch turns ON when items need restocking and OFF when done.
--
--   4. WIRELESS SERVER — Listens for commands from the pocket computer
--      (toggle mode, request items, control redstone channels) and
--      broadcasts factory status every scan cycle.
--
--   5. ALERTS — Sends chat messages (via Chat Box) and wireless alerts
--      when critical conditions occur (item stock hits zero, train issues).
--
--   6. CHILD PROGRAMS — Launches monitor_sensors.lua and monitor_request.lua
--      in background shell tabs to drive additional monitors.
--
-- HOW IT STARTS:
--   The install.lua script creates a startup.lua in the computer root that
--   runs this file.  When the computer boots, CC:Tweaked auto-runs startup.lua.
--
-- ARCHITECTURE:
--   The program runs two loops in parallel (using CC's parallel API):
--     • logicLoop() — Main loop: scan inventory, update display, handle
--       wireless messages, manage redstone timers
--     • inputLoop() — Listens for monitor touch events (button clicks)
--
-- ============================================================================

-- ============================================================================
-- ByteBlock VFS-aware require() override
-- ============================================================================
-- ByteBlock's PackageLib reads from the Java filesystem, NOT the in-game VFS.
-- This override intercepts require("v3.*") calls and loads them from the VFS
-- using fs.open() + load(), which DO work with ByteBlock's virtual filesystem.
-- All other require() calls (built-ins, etc.) pass through to the original.
-- ============================================================================
local _vfsLoaded = {}
local _origRequire = require
require = function(modname)
    if not modname:match("^v3[%./]") then return _origRequire(modname) end
    if _vfsLoaded[modname] then return _vfsLoaded[modname] end
    local relpath = modname:gsub("%.", "/") .. ".lua"
    local candidates = {
        relpath,
        "Documents/" .. relpath,
        "/Users/User/Documents/" .. relpath,
        "/Users/User/Desktop/" .. relpath,
        "/" .. relpath,
    }
    for _, p in ipairs(candidates) do
        local f = fs.open(p, "r")
        if f then
            local src = f.readAll(); f.close()
            local chunk, err = load(src, "@" .. p)
            if not chunk then error("Module '" .. modname .. "' compile error: " .. tostring(err), 2) end
            local ok, result = pcall(chunk)
            if not ok then error("Module '" .. modname .. "' load error: " .. tostring(result), 2) end
            _vfsLoaded[modname] = result or true
            return result
        end
    end
    error("Module '" .. modname .. "' not found in ByteBlock VFS (tried: " .. table.concat(candidates, ", ") .. ")", 2)
end

-- Load shared libraries
local config = require("v3.config")           -- All settings (colors, thresholds, etc.)
local peripherals = require("v3.lib_peripherals") -- Peripheral discovery + caching
local storage = require("v3.lib_storage")     -- Inventory scanning + item requests
local protocol = require("v3.lib_protocol")   -- Wireless packet format

-----------------------------------------------------------------------
-- PERIPHERAL DISCOVERY
-----------------------------------------------------------------------
-- Find and validate all required hardware before starting the main loops.
-- If critical peripherals (LogicLink, monitor) are missing, we retry once
-- after 5 seconds, then give up.  Optional peripherals (chat box, player
-- detector) are simply skipped if not found.
print("Create Factory Controller V" .. config.VERSION .. " starting...")

peripherals.initFactory()
local allOk, missing = peripherals.allRequired()
if not allOk then
    print("ERROR: Required peripheral '" .. missing .. "' not found.")
    print("Retrying in 5 seconds...")
    sleep(5)
    peripherals.initFactory()
    allOk, missing = peripherals.allRequired()
    if not allOk then
        print("FATAL: Cannot start without '" .. missing .. "'. Exiting.")
        return
    end
end

-- Wrap specific peripherals we'll use directly in this file.
-- The main monitor is expected on top of the computer; others are found
-- by type via the peripherals library.
local mon = peripheral.wrap("top") or peripherals.get("monitor")
local modem = peripherals.getWirelessModem()
local chatBox = peripherals.get("chat_box")
local playerDetector = peripherals.get("playerDetector")

-- Initialize the storage library (connects to LogicLink network).
local storageOk, storageErr = storage.init()
if not storageOk then
    print("WARNING: Storage init failed: " .. tostring(storageErr))
end

-----------------------------------------------------------------------
-- STATE
-----------------------------------------------------------------------
-- These variables track the current state of the factory controller.
-- They persist in memory as long as the program is running.
local autoMode = true            -- true = automatic conveyor control, false = manual
local manualOverride = false     -- In manual mode: true = conveyor ON, false = OFF
local lastAnyRequest = false     -- true if any gauge item was below target last scan
local lastGaugeItems = {}        -- Cached array of gauge items from last inventory scan
local lastInvItems = {}          -- Cached array of non-gauge items from last scan
local lastTrainAlerts = {}       -- Cached array of train diagnostic entries
local buttons = {}               -- Array of clickable button regions on the monitor
local w, h = 0, 0               -- Current monitor dimensions (characters)
local cachedHeaderLine = ""      -- Pre-built string of spaces for clearing header lines
local needsFullRedraw = true     -- true = next render should clear and redraw everything
local lastAlertTime = 0          -- Timestamp of last critical alert (for rate limiting)
local alertedItems = {}          -- Set of item IDs we've already alerted about (prevents spam)
local lastWirelessRx = os.epoch("utc")  -- Timestamp of last wireless packet received
local deliveryClutchUntil = 0    -- Epoch ms: keep conveyor clutch ON until this time (for delivery)
local statePending = false       -- true = a state change happened, trigger immediate refresh
local scanImmediately = false    -- true = do a full inventory scan on next loop iteration
local previousStatus = "IDLE"    -- Last status string (for detecting status changes → alerts)

-- Layout state (calculated dynamically based on monitor size and item count)
local colWidth = 31              -- Width of each column in characters
local numColumns = 2             -- Number of columns to display
local itemsPerColumn = 0         -- Items per column (calculated)

-----------------------------------------------------------------------
-- HELPERS
-----------------------------------------------------------------------
-- Utility functions used throughout this file. These handle logging,
-- redstone control, layout calculation, and user channel persistence.

-- Maximum log file size before rotation (50 KB). Prevents the log
-- from growing indefinitely and filling up the computer's limited
-- storage. When exceeded, the old log is deleted and a fresh one
-- is started.
local MAX_LOG_SIZE = 51200  -- 50KB max log file size

--- Write a timestamped debug message to the log file.
-- Only writes when config.DEBUG_MODE is true. Rotates the log when
-- it exceeds MAX_LOG_SIZE by deleting and recreating it.
-- @param message  string  The text to log
local function debugLog(message)
    if not config.DEBUG_MODE then return end
    -- Rotate log if too large (prevents filling the computer's storage)
    if fs.exists(config.LOG_FILE) and fs.getSize(config.LOG_FILE) > MAX_LOG_SIZE then
        fs.delete(config.LOG_FILE)
    end
    -- Open in append mode ("a") if file exists, otherwise create ("w")
    local f = fs.open(config.LOG_FILE, fs.exists(config.LOG_FILE) and "a" or "w")
    if f then
        f.writeLine("[" .. os.date("%H:%M:%S") .. "] " .. message)
        f.close()
    end
end

-- Look up the redstone_controller peripheral (from the Create mod's
-- Redstone Link API). This lets us send wireless redstone signals
-- to control conveyors, machines, etc. via paired frequency items.
local rsController = peripherals.get("redstone_controller")
if not rsController then
    print("WARNING: redstone_controller not found - clutch disabled")
end

-- Timer tracking for timed redstone pulses. Maps CC timer IDs to info
-- tables: {freq1, freq2, duration, power, repeatsLeft, phase}.
-- Timer IDs are returned by os.startTimer() and expire via "timer" events.
-- We store them here so we know what to do when each timer fires.
local rsTimers = {}

-- Persistent user-created redstone channels.
-- These are saved to disk (rs_channels.dat) so they survive reboots.
-- Users create them from the pocket computer's Redstone page.
local userRsChannels = {}

--- Save all user-created redstone channels to disk.
-- Uses textutils.serialise() to convert the Lua table to a string
-- that can be read back later with textutils.unserialise().
local function saveUserRsChannels()
    local f = fs.open(config.RS_CHANNELS_FILE, "w")
    if f then
        f.write(textutils.serialise(userRsChannels))
        f.close()
    end
end

--- Load user-created redstone channels from disk on startup.
-- Reads the serialised table back from the file. If the file doesn't
-- exist yet (first run), userRsChannels stays as an empty table.
local function loadUserRsChannels()
    if fs.exists(config.RS_CHANNELS_FILE) then
        local f = fs.open(config.RS_CHANNELS_FILE, "r")
        if f then
            local data = f.readAll()
            f.close()
            userRsChannels = textutils.unserialise(data) or {}
            debugLog("Loaded " .. #userRsChannels .. " user RS channels")
        end
    end
end

--- Find a user channel by its two frequency items.
-- Redstone Links use two items as an address (like a radio frequency).
-- @param freq1  string  First frequency item ID
-- @param freq2  string  Second frequency item ID
-- @return index, channel  or nil if not found
local function findUserChannel(freq1, freq2)
    for i, ch in ipairs(userRsChannels) do
        if ch.item1 == freq1 and ch.item2 == freq2 then return i, ch end
    end
    return nil
end

--- Merge hardware channels with user-created channels.
-- Hardware channels come from the actual redstone_controller peripheral.
-- User channels are ones created from the pocket computer but may not
-- exist in hardware yet. This produces a combined list for display,
-- removing duplicates (hardware takes priority).
-- @param hwChannels  table  Array of channel tables from the controller
-- @return merged  table  Combined array of all unique channels
local function mergeRsChannels(hwChannels)
    -- Start with hardware channels (these are the "source of truth")
    local merged = {}
    local seen = {}  -- Track which freq pairs we've already added
    for _, ch in ipairs(hwChannels or {}) do
        local key = (ch.item1 or "") .. "|" .. (ch.item2 or "")
        seen[key] = true
        table.insert(merged, ch)
    end
    -- Add user channels not already in hardware list
    for _, ch in ipairs(userRsChannels) do
        local key = (ch.item1 or "") .. "|" .. (ch.item2 or "")
        if not seen[key] then
            table.insert(merged, ch)
            seen[key] = true
        end
    end
    return merged
end

-- Load saved channels immediately on startup so they're available
-- before the first wireless command arrives.
loadUserRsChannels()

--- Update the main conveyor clutch redstone signal.
-- This controls the Create mod conveyor belt via a Redstone Link.
--
-- IMPORTANT: Create Clutches use INVERTED logic:
--   power=15 → clutch ENGAGED → conveyor STOPS
--   power=0  → clutch RELEASED → conveyor RUNS
--
-- The logic determines the signal based on:
--   Auto mode:  power=15 (stop) only when all items stocked AND no
--               active delivery. Otherwise power=0 (run).
--   Manual mode: directly controlled by manualOverride flag.
local function updateRedstone()
    -- Check if we're in a delivery window (items being conveyed to player)
    local deliveryActive = os.epoch("utc") < deliveryClutchUntil
    -- Determine if the conveyor should be STOPPED (rsSignal=true → power=15)
    local rsSignal = autoMode and (not lastAnyRequest) and (not deliveryActive) or manualOverride
    if rsController then
        local power = rsSignal and 15 or 0
        rsController.setOutput(config.REDSTONE_FREQ1, config.REDSTONE_FREQ2, power)
        debugLog("Redstone Link: power=" .. power .. (deliveryActive and " (delivery)" or ""))
    end
end

--- Calculate how many columns and rows to use for the item display.
-- Dynamically adapts the layout to fit the current monitor size and
-- the number of items we need to show. Adjusts the module-level
-- variables: colWidth, numColumns, itemsPerColumn.
-- @param totalItems  number  Total items to display (gauges + inventory)
local function calculateLayout(totalItems)
    local availableWidth = w - 2       -- Leave 1-char margin each side
    local minColWidth = 28             -- Minimum readable column width
    local maxColumns = math.floor(availableWidth / minColWidth)
    local availableHeight = h - 8      -- Reserve rows for header/buttons

    -- Pick the optimal column count: enough to fit items without too
    -- much empty space, clamped between 2 and 5 columns.
    local optimalColumns = math.min(maxColumns, math.ceil(totalItems / (availableHeight * 0.7)))
    optimalColumns = math.max(2, math.min(optimalColumns, 5))

    colWidth = math.floor(availableWidth / optimalColumns) - 1
    numColumns = optimalColumns
    itemsPerColumn = math.ceil(totalItems / numColumns)
end

--- Automatically adjust the monitor text scale so all items fit.
-- CC monitors support text scales from 0.5 to 5.0 in 0.5 steps.
-- Larger scale = bigger text but fewer characters fit. This function
-- starts at the user's preferred maximum scale and shrinks until all
-- items can be displayed, or we hit the minimum scale.
-- @param m           monitor  The monitor peripheral
-- @param totalItems  number   Total items to display
local function autoScale(m, totalItems)
    local scale = config.MAX_TEXT_SCALE
    repeat
        m.setTextScale(scale)
        w, h = m.getSize()  -- getSize() returns characters at current scale
        cachedHeaderLine = string.rep(" ", w)  -- Pre-build blank line
        local maxSlots = math.floor(w / (colWidth + 1)) * (h - 6)
        if totalItems <= maxSlots or scale <= config.MIN_TEXT_SCALE then break end
        scale = scale - 0.5
    until scale < config.MIN_TEXT_SCALE
    needsFullRedraw = true  -- Scale changed, so everything must be redrawn
end

-----------------------------------------------------------------------
-- INVENTORY / LOGISTICS
-----------------------------------------------------------------------
-- Functions that scan the factory's storage system (via LogicLink)
-- and determine what items need to be produced.

--- Scan all gauges and inventory, return whether any items are low.
-- This is the core data-gathering function called every scan cycle.
-- It talks to LogicLink to get gauge readings (target vs actual) and
-- cross-references with the full inventory.
-- @return anyLow      boolean  true if at least one gauge item is below target
-- @return gaugeItems  table    Array of items tracked by gauges
-- @return invItems    table    Array of items in storage but NOT in gauges
local function getLogistics()
    -- Get gauges from LogicLink (these define what items to track)
    local gauges = storage.getGauges()

    -- Get full inventory cross-referenced with gauges.
    -- gaugeItems have .count and .target; invItems have .count only.
    local gaugeItems, invItems = storage.getInventory(gauges)

    -- Check if any gauge item is below its target amount.
    -- This determines whether the conveyor should be running.
    local anyLow = false
    for _, item in ipairs(gaugeItems) do
        if item.count < item.target then
            anyLow = true
            break
        end
    end

    return anyLow, gaugeItems, invItems
end

-----------------------------------------------------------------------
-- TRAIN MONITORING
-----------------------------------------------------------------------
-- Checks the Create mod train network for problems (derailed trains,
-- overstressed trains, blocked signals). Requires a train_controller
-- peripheral from Create Advanced Logistics.

--- Scan the train network and return an array of alert objects.
-- Each alert has: type (string), severity (SEVERITY constant),
-- desc (human-readable), and optionally trainName.
-- @return alerts  table  Array of alert tables (empty if no issues)
local function getTrainAlerts()
    local tc = peripherals.get("train_controller")
    if not tc then return {} end  -- No train controller → no alerts

    local alerts = {}

    -- Check each train for derailment or overstress conditions.
    -- pcall() wraps the API call so a peripheral disconnect won't crash.
    local ok, trains = pcall(tc.getTrains)
    if ok and trains then
        for _, train in ipairs(trains) do
            if train.derailed then
                table.insert(alerts, {
                    type = "DERAILED",
                    severity = protocol.SEVERITY.CRIT,
                    desc = train.name .. " is DERAILED",
                    trainName = train.name,
                })
            end
            if train.overStressed then
                table.insert(alerts, {
                    type = "OVERSTRESSED",
                    severity = protocol.SEVERITY.WARN,
                    desc = train.name .. " is overstressed",
                    trainName = train.name,
                })
            end
        end
    end

    -- Check signals for prolonged blockages (possible deadlocks).
    -- Ticks are 1/20th of a second in Minecraft.
    local sigOk, signals = pcall(tc.getSignals)
    if sigOk and signals then
        for _, sig in ipairs(signals) do
            if sig.state == "RED" and sig.trainBlockedTicks and sig.trainBlockedTicks > config.TRAIN_SIGNAL_BLOCK_CRIT * 20 then
                table.insert(alerts, {
                    type = "TRAIN_SIGNAL_BLOCKED",
                    severity = protocol.SEVERITY.CRIT,
                    desc = "Signal blocked for extended period",
                })
            end
        end
    end

    return alerts
end

-----------------------------------------------------------------------
-- RENDER ENGINE
-----------------------------------------------------------------------
-- Functions that draw the factory dashboard on the main monitor.
-- CC monitors use a character grid with colored backgrounds/text.
-- Coordinates are 1-based: (1,1) is top-left.

--- Draw a colored section header bar spanning one column width.
-- Used to visually separate "GAUGE ITEMS" and "INVENTORY ONLY" sections.
-- @param m     monitor  The monitor peripheral
-- @param text  string   Header text (e.g., "GAUGE ITEMS (12)")
-- @param y     number   Starting row (draws on y and y+1)
-- @param x     number   Starting column
-- @param bg    number   Background color constant (e.g., colors.blue)
local function drawSectionHeader(m, text, y, x, bg)
    local C = config.COLORS
    m.setBackgroundColor(bg)
    m.setTextColor(colors.black)
    -- First line: solid color bar
    m.setCursorPos(x, y)
    m.write(string.rep(" ", colWidth))
    -- Second line: centered text on colored background
    m.setCursorPos(x, y + 1)
    local padding = math.floor((colWidth - #text) / 2)
    m.write(string.rep(" ", padding) .. text .. string.rep(" ", colWidth - #text - padding))
end

--- Draw a clickable button on the monitor.
-- Also registers the button in the global `buttons` table so click
-- events can be matched to buttons in handleClick().
-- @param m        monitor  The monitor peripheral
-- @param x        number   Left column of the button
-- @param y        number   Top row of the button
-- @param width    number   Button width in characters
-- @param height   number   Button height in rows
-- @param text     string   Label text (centered in the button)
-- @param bgColor  number   Background color constant
local function drawButton(m, x, y, width, height, text, bgColor)
    m.setBackgroundColor(bgColor)
    m.setTextColor(colors.black)
    -- Fill the button rectangle with the background color
    for i = 0, height - 1 do
        m.setCursorPos(x, y + i)
        m.write(string.rep(" ", width))
    end
    -- Center the label text within the button
    m.setCursorPos(x + math.floor((width - #text) / 2), y + math.floor(height / 2))
    m.write(text)
    -- Register this button for click detection
    table.insert(buttons, {x = x, y = y, w = width, h = height, text = text})
end

--- Main display rendering function — draws the entire factory dashboard.
-- This is the most complex function in the file. It renders:
--   1. Header bar with factory name and WiFi/train indicators
--   2. Status bar showing conveyor state (WORKING/IDLE/MANUAL)
--   3. Gauge items in columns (items with targets — color-coded)
--   4. Inventory-only items (items without targets — just counts)
--   5. Control buttons (Auto/Manual, ON/OFF, Refresh)
--
-- @param anyRequest  boolean  true if any gauge item is below target
-- @param gaugeList   table    Array of gauge items (with .count, .target)
-- @param invList     table    Array of inventory-only items
local function refreshMonitor(anyRequest, gaugeList, invList)
    local C = config.COLORS
    local CAT = config.CAT_COLORS
    -- Re-wrap monitor in case it was replaced (e.g., chunk reload)
    mon = peripheral.wrap("top") or peripherals.get("monitor")
    if not mon then return end

    -- Only clear the entire screen when layout changed (needsFullRedraw).
    -- This reduces flicker during normal refreshes.
    if needsFullRedraw then
        buttons = {}  -- Clear old button regions
        mon.setBackgroundColor(C.background)
        mon.clear()
        needsFullRedraw = false
    end

    -- ===== 1. MAIN HEADER =====
    -- Two-row header bar with factory name centered
    mon.setBackgroundColor(C.header)
    mon.setTextColor(colors.black)
    mon.setCursorPos(1, 1); mon.write(cachedHeaderLine)
    mon.setCursorPos(1, 2); mon.write(cachedHeaderLine)
    local title = config.FACTORY_NAME .. " V" .. config.VERSION
    mon.setCursorPos(math.floor((w - #title) / 2) + 1, 2)
    mon.write(title)

    -- WiFi indicator (top-right corner): green if pocket client
    -- has communicated recently, red if timed out
    if modem then
        local wifiUp = (os.epoch("utc") - lastWirelessRx) < config.WIFI_TIMEOUT
        mon.setCursorPos(w - 5, 1)
        mon.setBackgroundColor(wifiUp and C.success or C.error)
        mon.write(" WIFI ")
    end

    -- Train alert badge: shows count of active train issues
    if #lastTrainAlerts > 0 then
        local trainLabel = " TRAIN:" .. #lastTrainAlerts .. " "
        mon.setCursorPos(w - 5 - #trainLabel - 1, 1)
        mon.setBackgroundColor(C.warning)
        mon.setTextColor(colors.black)
        mon.write(trainLabel)
    end

    -- ===== 2. STATUS BAR =====
    -- Single row showing the current factory state and item count.
    -- Background color: green = conveyor stopped (items stocked),
    --                   red = conveyor running (items needed).
    local deliveryActive = os.epoch("utc") < deliveryClutchUntil
    local rsSignal = autoMode and (not anyRequest) and (not deliveryActive) or manualOverride
    -- Note: actual redstone output handled by updateRedstone(), not here.
    -- This just determines the display text and color.
    local currentStatus
    if autoMode then
        if deliveryActive then
            currentStatus = "STATUS: DELIVERING (Conveyor ON)"
        elseif anyRequest then
            currentStatus = "STATUS: WORKING (Conveyor ON)"
        else
            currentStatus = "STATUS: IDLE (Conveyor OFF)"
        end
    else
        currentStatus = manualOverride and "STATUS: MANUAL ON" or "STATUS: MANUAL OFF"
    end

    -- Storage stats
    local stats = storage.getStorageStats()
    if stats and stats.totalItems > 0 then
        currentStatus = currentStatus .. string.format(" | Items: %d types", stats.itemTypes)
    end

    mon.setBackgroundColor((not rsSignal) and C.success or C.error)
    mon.setTextColor(C.text)
    mon.setCursorPos(1, 3); mon.write(cachedHeaderLine)
    mon.setCursorPos(math.floor((w - #currentStatus) / 2) + 1, 3)
    mon.write(currentStatus)

    -- Calculate dynamic layout
    calculateLayout(#gaugeList + #invList)

    local x, y = 1, 5
    local curMod = ""

    -- ===== 3. GAUGE ITEMS =====
    -- These are items with production targets (set via LogicLink gauges).
    -- Items are grouped by mod namespace (e.g., "minecraft", "create")
    -- and color-coded: red=empty, orange=below target, green=stocked.
    drawSectionHeader(mon, "GAUGE ITEMS (" .. #gaugeList .. ")", y, x, C.header)
    y = y + 2

    for _, item in ipairs(gaugeList) do
        if y > h - 4 then
            y = 7
            x = x + colWidth + 1
            if x + colWidth > w then break end
            drawSectionHeader(mon, "GAUGE ITEMS (cont.)", 5, x, C.header)
        end

        -- Category headers — group items by their mod namespace.
        -- This makes it easy to find items from a specific mod.
        local mod = item.item:match("([^:]+):") or "other"
        if mod ~= curMod then
            curMod = mod
            mon.setCursorPos(x, y)
            mon.setBackgroundColor(CAT[curMod] or CAT.default)
            mon.setTextColor(C.text)
            local catLabel = "[" .. curMod:upper():sub(1, 4) .. "]"
            mon.write(string.format("%-7s", catLabel))
            mon.setBackgroundColor(C.background)
            mon.write(string.rep(" ", colWidth - 7))
            y = y + 1
        end

        mon.setCursorPos(x, y)
        mon.setBackgroundColor((y % 2 == 0) and C.background or C.backgroundAlt)
        mon.setTextColor(C.text)

        local nameWidth = math.min(colWidth - 16, 15)
        local displayName = item.displayName:sub(1, nameWidth)
        mon.write(string.format("%-" .. nameWidth .. "s ", displayName))

        -- Color logic: red=empty (0), orange=below target, green=at/above target
        local countColor = C.success
        if item.count == 0 then
            countColor = C.error
        elseif item.count < item.target then
            countColor = C.warning
        end
        mon.setTextColor(countColor)
        mon.write(string.format("%5d", item.count))
        mon.setTextColor(C.text)
        mon.write("/")
        mon.setTextColor(colors.yellow)
        mon.write(string.format("%-5d", item.target))
        y = y + 1
    end

    -- ===== 4. INVENTORY ONLY =====
    -- Items found in storage but NOT tracked by any gauge.
    -- These are shown with just a count (no target) for reference.
    if #invList > 0 then
        y = y + 1
        if y > h - 6 then y = 7; x = x + colWidth + 1 end

        if x + colWidth <= w then
            drawSectionHeader(mon, "INVENTORY ONLY (" .. #invList .. ")", y, x, C.header)
            y = y + 2
            curMod = ""

            for _, item in ipairs(invList) do
                if y > h - 4 then
                    y = 7
                    x = x + colWidth + 1
                    if x + colWidth > w then break end
                    drawSectionHeader(mon, "INVENTORY (cont.)", 5, x, C.header)
                    y = 7
                end

                local mod = item.item:match("([^:]+):") or "other"
                if mod ~= curMod then
                    curMod = mod
                    mon.setCursorPos(x, y)
                    mon.setBackgroundColor(CAT[curMod] or CAT.default)
                    mon.setTextColor(C.text)
                    local catLabel = "[" .. curMod:upper():sub(1, 4) .. "]"
                    mon.write(string.format("%-7s", catLabel))
                    mon.setBackgroundColor(C.background)
                    mon.write(string.rep(" ", colWidth - 7))
                    y = y + 1
                end

                mon.setCursorPos(x, y)
                mon.setBackgroundColor((y % 2 == 0) and C.background or C.backgroundAlt)
                mon.setTextColor(C.text)

                local nameWidth = math.min(colWidth - 10, 18)
                local displayName = item.displayName:sub(1, nameWidth)
                mon.write(string.format("%-" .. nameWidth .. "s", displayName))
                mon.setTextColor(C.textDim)
                mon.write(string.format("%9d", item.count))
                y = y + 1
            end
        end
    end

    -- ===== 5. CONTROL BUTTONS =====
    -- Fixed at the bottom-right of the monitor. These respond to
    -- monitor_touch events (handled by handleClick).
    local bx = w - 10
    -- Auto/Manual toggle button
    drawButton(mon, bx, h - 2, 10, 1, autoMode and "Auto" or "Manual", colors.blue)

    -- ON/OFF button: only active in Manual mode (gray when in Auto)
    local onOffColor = colors.gray
    if not autoMode then
        onOffColor = manualOverride and C.error or C.success
    end
    drawButton(mon, bx, h - 1, 10, 1, "ON/OFF", onOffColor)
    drawButton(mon, bx, h, 10, 1, "Refresh", colors.lightGray)
end

-----------------------------------------------------------------------
-- WIRELESS SERVER & ALERTS
-----------------------------------------------------------------------
-- This section implements the wireless server that pocket computers
-- connect to. It handles incoming commands (toggle mode, request items,
-- control redstone, etc.) and broadcasts status updates.

--- Build a short status string for wireless broadcasts.
-- @return string  One of: "WORKING", "IDLE", "MANUAL ON", "MANUAL OFF"
local function getStatusString()
    if autoMode then
        return lastAnyRequest and "WORKING" or "IDLE"
    else
        return manualOverride and "MANUAL ON" or "MANUAL OFF"
    end
end

--- Build a list of gauge items that are below their target.
-- Sent to pocket computers so they can display which items need
-- production. Capped at 10 entries to keep wireless packets small.
-- @return table  Array of {name, count, target} tables
local function buildLowItemsList()
    local low = {}
    for _, item in ipairs(lastGaugeItems) do
        if item.count < item.target then
            table.insert(low, {
                name = item.displayName,
                count = item.count,
                target = item.target,
            })
        end
    end
    -- Cap at 10 to avoid packet bloat
    if #low > 10 then
        local capped = {}
        for i = 1, 10 do capped[i] = low[i] end
        return capped
    end
    return low
end

--- Broadcast the current factory status to all listening pocket computers.
-- Sends a status packet containing: mode, conveyor state, low items list,
-- and train alert count. All pocket computers on WIRELESS_CHANNEL receive it.
local function broadcastStatus()
    if not modem then return end

    local packet = protocol.statusPacket(
        getStatusString(),
        autoMode,
        manualOverride,
        buildLowItemsList(),
        nil,  -- storage percent (logiclink doesn't expose capacity)
        #lastTrainAlerts
    )
    protocol.send(modem, packet)
    debugLog("Status broadcast: " .. getStatusString())
end

--- Handle an incoming wireless command from a pocket computer.
-- This is the main command dispatcher. It unpacks the protocol packet,
-- validates it, then routes to the appropriate handler based on packet.type.
-- Each handler performs an action and usually sends a response packet back.
--
-- Supported commands (see lib_protocol.lua CMD table):
--   GET_STATUS      → broadcast current status
--   TOGGLE_MODE     → switch between auto/manual mode
--   TOGGLE_MANUAL   → toggle conveyor ON/OFF in manual mode
--   RELOAD_CONFIG   → force a full inventory re-scan
--   REQUEST_ITEM    → request items from LogicLink storage
--   CHECK_ITEM      → search for an item by name
--   GET_INVENTORY   → return cached gauge items list
--   GET_TRAINS      → return train network data
--   GET_REDSTONE    → return all redstone channels + presets
--   SET_REDSTONE    → set a specific channel's power level
--   ADD_REDSTONE    → create a new redstone channel
--   REMOVE_REDSTONE → delete a redstone channel
--   ALL_REDSTONE    → set ALL channels to same power (kill switch)
--   TIMER_REDSTONE  → timed pulse with optional repeats
--   PING            → respond with pong (connectivity check)
--
-- @param senderID  number  CC computer ID of the sender
-- @param raw       string  Raw serialised packet data
local function handleWirelessCommand(senderID, raw)
    -- Unpack and validate the packet format
    local packet, err = protocol.unpack(raw)
    if not packet then
        debugLog("Invalid packet from " .. tostring(senderID) .. ": " .. tostring(err))
        return
    end

    lastWirelessRx = os.epoch("utc")
    debugLog("CMD from " .. tostring(senderID) .. ": " .. tostring(packet.type))

    if packet.type == protocol.CMD.GET_STATUS then
        broadcastStatus()

    elseif packet.type == protocol.CMD.TOGGLE_MODE then
        local wasManual = not autoMode
        autoMode = not autoMode
        manualOverride = false
        updateRedstone()
        needsFullRedraw = true
        statePending = true
        if autoMode and wasManual then scanImmediately = true end
        os.queueEvent("manual_refresh")
        broadcastStatus()

    elseif packet.type == protocol.CMD.TOGGLE_MANUAL then
        if not autoMode then
            manualOverride = not manualOverride
            updateRedstone()
            needsFullRedraw = true
            statePending = true
            os.queueEvent("manual_refresh")
            broadcastStatus()
        end

    elseif packet.type == protocol.CMD.RELOAD_CONFIG then
        -- Force re-scan gauges
        needsFullRedraw = true
        scanImmediately = true
        statePending = true
        os.queueEvent("manual_refresh")
        broadcastStatus()

    elseif packet.type == protocol.CMD.REQUEST_ITEM then
        local d = packet.data or {}
        if d.itemId and d.quantity then
            local ok, msg = storage.requestItem(d.itemId, d.quantity, d.destination)
            local resp = protocol.responsePacket(protocol.CMD.REQUEST_ITEM, ok, {message = msg}, senderID)
            protocol.send(modem, resp)

            -- Keep clutch on for delivery window so Create conveyor can move items
            if ok then
                local clutchMs = (config.DELIVERY_CLUTCH_TIME or 10) * 1000
                deliveryClutchUntil = os.epoch("utc") + clutchMs
                debugLog("Clutch ON for " .. (config.DELIVERY_CLUTCH_TIME or 10) .. "s delivery window")
            end

            updateRedstone()

            -- Trigger scan to update display
            scanImmediately = true
            statePending = true
            os.queueEvent("manual_refresh")
        end

    elseif packet.type == protocol.CMD.CHECK_ITEM then
        local d = packet.data or {}
        if d.search then
            local results = storage.findItem(d.search)
            if #results > 0 then
                -- Return best match (first result)
                local best = results[1]
                local resp = protocol.responsePacket(protocol.CMD.CHECK_ITEM, true, {
                    exists = true,
                    id = best.item,
                    displayName = best.displayName,
                    count = best.count,
                    results = math.min(#results, 10),
                }, senderID)
                protocol.send(modem, resp)
            else
                local resp = protocol.responsePacket(protocol.CMD.CHECK_ITEM, true, {
                    exists = false,
                }, senderID)
                protocol.send(modem, resp)
            end
        end

    elseif packet.type == protocol.CMD.GET_INVENTORY then
        -- Send cached gauge items
        local items = {}
        for _, g in ipairs(lastGaugeItems) do
            table.insert(items, {name = g.displayName, item = g.item, count = g.count, target = g.target})
        end
        local resp = protocol.responsePacket(protocol.CMD.GET_INVENTORY, true, {items = items}, senderID)
        protocol.send(modem, resp)

    elseif packet.type == protocol.CMD.GET_TRAINS then
        local tc = peripherals.get("train_controller")
        if tc then
            local ok, trains = pcall(tc.getTrains)
            local resp = protocol.responsePacket(protocol.CMD.GET_TRAINS, ok, {trains = ok and trains or {}}, senderID)
            protocol.send(modem, resp)
        end

    elseif packet.type == protocol.CMD.GET_REDSTONE then
        -- Return all channels: hardware + user-created
        local hwChannels = {}
        if rsController then
            local ok, channels = pcall(rsController.getChannels)
            if ok then hwChannels = channels or {} end
        end
        -- Sync user channel power from hardware state
        for i, uch in ipairs(userRsChannels) do
            for _, hch in ipairs(hwChannels) do
                if hch.item1 == uch.item1 and hch.item2 == uch.item2 then
                    userRsChannels[i].power = hch.power or 0
                    userRsChannels[i].mode = hch.mode or "transmit"
                end
            end
        end
        local merged = mergeRsChannels(hwChannels)
        local resp = protocol.responsePacket(protocol.CMD.GET_REDSTONE, true, {
            channels = merged,
            presets = config.REDSTONE_PRESETS or {},
        }, senderID)
        protocol.send(modem, resp)

    elseif packet.type == protocol.CMD.SET_REDSTONE then
        -- Set a specific channel's output power
        local d = packet.data or {}
        if d.freq1 and d.freq2 and d.power then
            local ok, err = true, nil
            if rsController then
                ok, err = pcall(rsController.setOutput, d.freq1, d.freq2, d.power)
            end
            -- Update user channel power
            local idx = findUserChannel(d.freq1, d.freq2)
            if idx then
                userRsChannels[idx].power = d.power
                saveUserRsChannels()
            end
            local resp = protocol.responsePacket(protocol.CMD.SET_REDSTONE, ok, {
                message = ok and ("Set " .. d.freq1 .. "+" .. d.freq2 .. " = " .. d.power) or ("HW error: " .. tostring(err))
            }, senderID)
            protocol.send(modem, resp)
            debugLog("RS set: " .. d.freq1 .. "+" .. d.freq2 .. " = " .. d.power .. (ok and "" or " ERR: " .. tostring(err)))
        end

    elseif packet.type == protocol.CMD.ADD_REDSTONE then
        -- Add/create a new channel (persisted locally)
        local d = packet.data or {}
        if d.freq1 and d.freq2 then
            local power = d.power or 0
            -- Try to set output on hardware
            local ok, err = true, nil
            if rsController then
                ok, err = pcall(rsController.setOutput, d.freq1, d.freq2, power)
            end
            -- Always persist to user channels regardless of hardware result
            local idx = findUserChannel(d.freq1, d.freq2)
            if not idx then
                table.insert(userRsChannels, {
                    item1 = d.freq1, item2 = d.freq2,
                    mode = "transmit", power = power
                })
            else
                userRsChannels[idx].power = power
            end
            saveUserRsChannels()
            local resp = protocol.responsePacket(protocol.CMD.ADD_REDSTONE, true, {
                message = "Channel created" .. (ok and "" or " (hw fail)")
            }, senderID)
            protocol.send(modem, resp)
            debugLog("RS add: " .. d.freq1 .. "+" .. d.freq2 .. " @ " .. power)
        end

    elseif packet.type == protocol.CMD.REMOVE_REDSTONE then
        -- Remove a channel from both hardware and user list
        local d = packet.data or {}
        if d.freq1 and d.freq2 then
            if rsController then
                pcall(rsController.removeChannel, d.freq1, d.freq2)
            end
            -- Remove from user channels
            local idx = findUserChannel(d.freq1, d.freq2)
            if idx then
                table.remove(userRsChannels, idx)
                saveUserRsChannels()
            end
            local resp = protocol.responsePacket(protocol.CMD.REMOVE_REDSTONE, true, {
                message = "Channel removed"
            }, senderID)
            protocol.send(modem, resp)
            debugLog("RS remove: " .. d.freq1 .. "+" .. d.freq2)
        end

    elseif packet.type == protocol.CMD.ALL_REDSTONE then
        -- Set all outputs at once (kill switch)
        local d = packet.data or {}
        if d.power then
            local ok = true
            if rsController then
                ok = ({pcall(rsController.setAllOutputs, d.power)})[1]
            end
            -- Update all user channels too
            for i, ch in ipairs(userRsChannels) do
                userRsChannels[i].power = d.power
            end
            if #userRsChannels > 0 then saveUserRsChannels() end
            local resp = protocol.responsePacket(protocol.CMD.ALL_REDSTONE, true, {
                message = "All outputs set to " .. d.power
            }, senderID)
            protocol.send(modem, resp)
            debugLog("RS all: " .. d.power)
        end

    elseif packet.type == protocol.CMD.TIMER_REDSTONE then
        -- Timed output: set power, wait duration, then turn off. Supports repeats.
        local d = packet.data or {}
        local key = (d.freq1 or "") .. "|" .. (d.freq2 or "")

        -- Stop command: cancel all timers for this channel
        if d.stop then
            for tid, info in pairs(rsTimers) do
                if info.freq1 == d.freq1 and info.freq2 == d.freq2 then
                    os.cancelTimer(tid)
                    rsTimers[tid] = nil
                end
            end
            -- Turn off
            if rsController then
                pcall(rsController.setOutput, d.freq1, d.freq2, 0)
            end
            local idx = findUserChannel(d.freq1, d.freq2)
            if idx then userRsChannels[idx].power = 0; saveUserRsChannels() end
            local resp = protocol.responsePacket(protocol.CMD.TIMER_REDSTONE, true, {
                message = "Timer stopped"
            }, senderID)
            protocol.send(modem, resp)
            debugLog("RS timer stopped: " .. (d.freq1 or "") .. "+" .. (d.freq2 or ""))

        elseif d.freq1 and d.freq2 and d.power and d.duration then
            local ok = true
            if rsController then
                ok = ({pcall(rsController.setOutput, d.freq1, d.freq2, d.power)})[1]
            end
            local idx = findUserChannel(d.freq1, d.freq2)
            if idx then userRsChannels[idx].power = d.power; saveUserRsChannels() end

            -- Schedule turn-off after duration
            local timerId = os.startTimer(d.duration)
            local repeatsLeft = (d.repeats or 1) - 1  -- -1 consumed this pulse
            if d.repeats == -1 then repeatsLeft = -1 end  -- infinite stays -1
            rsTimers[timerId] = {
                freq1 = d.freq1, freq2 = d.freq2,
                duration = d.duration, power = d.power,
                repeatsLeft = repeatsLeft,  -- -1=infinite, 0=last pulse, >0=more to go
                phase = "on",  -- "on" -> will turn off, then "off" -> will turn on
            }
            local repLabel = (d.repeats or 1) == -1 and "infinite" or tostring(d.repeats or 1) .. "x"
            local resp = protocol.responsePacket(protocol.CMD.TIMER_REDSTONE, true, {
                message = "Timer: " .. d.duration .. "s " .. repLabel
            }, senderID)
            protocol.send(modem, resp)
            debugLog("RS timer: " .. d.freq1 .. "+" .. d.freq2 .. " @ " .. d.power .. " for " .. d.duration .. "s " .. repLabel)
        end

    elseif packet.type == protocol.CMD.PING then
        local resp = protocol.responsePacket(protocol.CMD.PING, true, {pong = true}, senderID)
        protocol.send(modem, resp)

    else
        debugLog("Unknown command: " .. tostring(packet.type))
    end
end

-----------------------------------------------------------------------
-- CHAT ALERTS
-----------------------------------------------------------------------
-- In-game chat notifications via CC:Tweaked's chatBox peripheral.
-- Alerts players when the factory starts/stops or when critical items
-- run out. Rate-limited to prevent chat spam.

--- Send a chat message in-game via the chatBox peripheral.
-- Messages appear as "[Factory Alert] message" in the Minecraft chat.
-- @param message  string  The alert text
-- @param player   string  Optional: send only to this player (nil = all)
local function sendChatAlert(message, player)
    if not chatBox or not config.CHAT_ALERTS_ENABLED then return end
    local ok, err = pcall(function()
        if player then
            chatBox.sendMessageToPlayer(message, player, "Factory Alert")
        else
            chatBox.sendMessage(message, "Factory Alert")
        end
    end)
    if not ok then
        debugLog("Chat alert failed: " .. tostring(err))
    end
end

--- Check for conditions that warrant chat/wireless alerts.
-- Called every scan cycle. Handles two types of alerts:
--   1. Status change: factory went IDLE→WORKING or WORKING→IDLE
--   2. Critical items: a gauge item's count hit zero (rate-limited
--      by ALERT_COOLDOWN, only alerts once per item until restocked)
local function checkAndAlert()
    local currentTime = os.epoch("utc")
    local currentStatus = getStatusString()

    -- Status change alerts (auto mode only)
    if autoMode and currentStatus ~= previousStatus then
        if currentStatus == "WORKING" and previousStatus == "IDLE" then
            local lowCount = 0
            for _, item in ipairs(lastGaugeItems) do
                if item.count < item.target then lowCount = lowCount + 1 end
            end
            sendChatAlert("Factory started: " .. lowCount .. " item(s) requested", nil)
        elseif currentStatus == "IDLE" and previousStatus == "WORKING" then
            sendChatAlert("Factory idle: All item requests completed", nil)
        end
    end
    previousStatus = currentStatus

    -- Critical item alerts (rate-limited)
    if currentTime - lastAlertTime < (config.ALERT_COOLDOWN * 1000) then return end

    for _, item in ipairs(lastGaugeItems) do
        if item.count == 0 and not alertedItems[item.item] then
            sendChatAlert("CRITICAL: " .. item.displayName .. " is empty!", nil)

            -- Also broadcast wireless alert
            if modem then
                local pkt = protocol.alertPacket(
                    item.displayName .. " is empty!",
                    protocol.SEVERITY.CRIT,
                    "factory"
                )
                protocol.send(modem, pkt)
            end

            alertedItems[item.item] = true
            lastAlertTime = currentTime
            break  -- One alert per cycle
        elseif item.count > 0 and alertedItems[item.item] then
            alertedItems[item.item] = nil
        end
    end

    -- Train alerts
    if #lastTrainAlerts > 0 and config.TRAIN_ALERTS_ENABLED then
        for _, alert in ipairs(lastTrainAlerts) do
            if alert.severity == protocol.SEVERITY.CRIT then
                sendChatAlert("TRAIN: " .. alert.desc, nil)
                if modem then
                    local pkt = protocol.trainAlertPacket(lastTrainAlerts)
                    protocol.send(modem, pkt)
                end
                break  -- One per cycle
            end
        end
    end
end

-----------------------------------------------------------------------
-- CLICK HANDLER
-----------------------------------------------------------------------
-- Processes monitor_touch events by checking if the click coordinates
-- fall within any registered button's bounding box.

--- Handle a touch/click event on the main monitor.
-- Iterates through all registered buttons and checks if the click
-- position is within any button's bounds. If so, performs the action
-- and provides visual feedback (brief white flash).
-- @param clickX  number  X coordinate of the touch
-- @param clickY  number  Y coordinate of the touch
-- @return boolean  true if a button was clicked, false otherwise
local function handleClick(clickX, clickY)
    debugLog("Click at (" .. clickX .. ", " .. clickY .. ")")
    for _, btn in ipairs(buttons) do
        if clickX >= btn.x and clickX <= btn.x + btn.w - 1
            and clickY >= btn.y and clickY <= btn.y + btn.h - 1 then
            debugLog("Button: " .. btn.text)

            -- Flash feedback
            mon.setBackgroundColor(colors.white)
            mon.setTextColor(colors.black)
            for dy = 0, btn.h - 1 do
                mon.setCursorPos(btn.x, btn.y + dy)
                mon.write(string.rep(" ", btn.w))
            end
            mon.setCursorPos(btn.x + math.floor((btn.w - #btn.text) / 2), btn.y + math.floor(btn.h / 2))
            mon.write(btn.text)
            sleep(0.1)

            if btn.text == "Auto" or btn.text == "Manual" then
                local wasManual = not autoMode
                autoMode = not autoMode
                manualOverride = false
                updateRedstone()
                needsFullRedraw = true
                statePending = true
                if autoMode and wasManual then scanImmediately = true end

            elseif btn.text == "ON/OFF" and not autoMode then
                manualOverride = not manualOverride
                updateRedstone()
                needsFullRedraw = true
                statePending = true

            elseif btn.text == "Refresh" then
                needsFullRedraw = true
                statePending = true
                scanImmediately = true
            end

            broadcastStatus()
            os.queueEvent("manual_refresh")
            return true
        end
    end
    return false
end

-----------------------------------------------------------------------
-- CORE LOOPS
-----------------------------------------------------------------------
-- The program runs two parallel loops using CC's parallel API:
--   logicLoop() — handles scanning, rendering, wireless, and timers
--   inputLoop() — handles monitor touch events
-- parallel.waitForAny() runs both simultaneously. If either crashes,
-- the program exits.

--- Main logic loop: scans inventory, renders display, handles wireless.
-- This loop runs forever. Each iteration:
--   1. Checks that the monitor is still connected
--   2. If a state change is pending, does a quick refresh
--   3. Otherwise, does a full scan cycle (inventory + render + broadcast)
--   4. Waits for the next timer tick or event (wireless, click, etc.)
--   5. During the wait, also processes redstone timer expirations
local function logicLoop()
    -- Open wireless modem channels for receiving commands.
    -- We listen on both our unique computer ID (for targeted messages)
    -- and the shared broadcast channel (for discovery).
    if modem then
        modem.open(os.getComputerID())
        modem.open(config.WIRELESS_CHANNEL)
        print("Wireless: channels " .. os.getComputerID() .. " + " .. config.WIRELESS_CHANNEL)
        sleep(1)
        broadcastStatus()
    else
        print("Warning: No wireless modem — remote features disabled")
    end

    while true do
        -- Peripheral health check
        mon = peripheral.wrap("top") or peripherals.get("monitor")
        if not mon then
            print("Monitor disconnected — waiting...")
            sleep(5)
        else
            if statePending then
                statePending = false
                pcall(refreshMonitor, lastAnyRequest, lastGaugeItems, lastInvItems)

                if scanImmediately then
                    scanImmediately = false
                    needsFullRedraw = true
                    local ok, low, gauge, inv = pcall(getLogistics)
                    if ok then
                        if #gauge + #inv ~= #lastGaugeItems + #lastInvItems then
                            autoScale(mon, #gauge + #inv)
                        end
                        lastAnyRequest, lastGaugeItems, lastInvItems = low, gauge, inv
                        pcall(refreshMonitor, low, gauge, inv)
                        checkAndAlert()
                        broadcastStatus()
                    end
                end
            else
                -- Full scan cycle
                local ok, low, gauge, inv = pcall(getLogistics)
                if ok then
                    if #gauge + #inv ~= #lastGaugeItems + #lastInvItems then
                        autoScale(mon, #gauge + #inv)
                    end
                    lastAnyRequest, lastGaugeItems, lastInvItems = low, gauge, inv

                    -- Get train alerts
                    lastTrainAlerts = getTrainAlerts()

                    pcall(refreshMonitor, low, gauge, inv)
                    checkAndAlert()
                    broadcastStatus()
                else
                    print("Scan error: " .. tostring(low))
                    sleep(2)
                end
            end

            -- Wait for the next scan cycle OR an interrupting event.
            -- os.startTimer() creates a one-shot timer that fires a "timer"
            -- event after the specified seconds. We also handle other events
            -- that arrive while waiting (wireless messages, touch, etc.).
            local timer = os.startTimer(config.REFRESH_RATES.inventory)
            repeat
                local event, p1, p2, p3, p4, p5 = os.pullEvent()
                -- Main scan timer expired — time for next cycle
                if event == "timer" and p1 == timer then break end

                -- Handle redstone timer expiry (timed pulses from pocket).
                -- rsTimers maps timer IDs to channel info. When a timer fires,
                -- we toggle the channel between ON and OFF phases, and
                -- optionally schedule the next phase for repeating pulses.
                if event == "timer" and rsTimers[p1] then
                    local info = rsTimers[p1]
                    rsTimers[p1] = nil

                    if info.phase == "on" then
                        -- ON phase ended -> turn OFF
                        if rsController then
                            pcall(rsController.setOutput, info.freq1, info.freq2, 0)
                        end
                        local idx = findUserChannel(info.freq1, info.freq2)
                        if idx then userRsChannels[idx].power = 0; saveUserRsChannels() end
                        debugLog("RS timer off: " .. info.freq1 .. "+" .. info.freq2)

                        -- Check if we need to repeat
                        if info.repeatsLeft == -1 or info.repeatsLeft > 0 then
                            -- Schedule OFF gap (uses same duration as ON phase)
                            local nextTimer = os.startTimer(info.duration)
                            rsTimers[nextTimer] = {
                                freq1 = info.freq1, freq2 = info.freq2,
                                duration = info.duration, power = info.power,
                                repeatsLeft = info.repeatsLeft == -1 and -1 or (info.repeatsLeft - 1),
                                phase = "off",
                            }
                            debugLog("RS timer gap: " .. info.duration .. "s, reps=" .. tostring(info.repeatsLeft))
                        end
                    elseif info.phase == "off" then
                        -- OFF gap ended -> turn ON again
                        if rsController then
                            pcall(rsController.setOutput, info.freq1, info.freq2, info.power)
                        end
                        local idx = findUserChannel(info.freq1, info.freq2)
                        if idx then userRsChannels[idx].power = info.power; saveUserRsChannels() end
                        debugLog("RS timer on: " .. info.freq1 .. "+" .. info.freq2 .. " rep")

                        -- Schedule next OFF (ON duration matches OFF gap)
                        local nextTimer = os.startTimer(info.duration)
                        rsTimers[nextTimer] = {
                            freq1 = info.freq1, freq2 = info.freq2,
                            duration = info.duration, power = info.power,
                            repeatsLeft = info.repeatsLeft,
                            phase = "on",
                        }
                    else
                        -- Legacy timer format (no phase) — just turn off
                        if rsController then
                            pcall(rsController.setOutput, info.freq1, info.freq2, 0)
                        end
                        local idx = findUserChannel(info.freq1, info.freq2)
                        if idx then userRsChannels[idx].power = 0; saveUserRsChannels() end
                        debugLog("RS timer expired: " .. info.freq1 .. "+" .. info.freq2 .. " -> 0")
                    end
                end
                -- "manual_refresh" is a custom event we queue when a button
                -- is pressed or a wireless command changes state. It causes
                -- the loop to skip the remaining wait time and refresh now.
                if event == "manual_refresh" then
                    if timer then os.cancelTimer(timer) end
                    break
                end
                -- Peripheral connect/disconnect: a monitor or LogicLink was
                -- added or removed. Refresh the peripheral cache and redraw.
                if event == "peripheral" or event == "peripheral_detach" then
                    if timer then os.cancelTimer(timer) end
                    peripherals.refresh("monitor")
                    peripherals.refresh("logiclink")
                    needsFullRedraw = true
                    sleep(0.5)
                    break
                end
                -- Wireless message received: route to command handler.
                -- modem_message events carry: side, senderChannel,
                -- replyChannel, message, distance.
                if event == "modem_message" then
                    handleWirelessCommand(p3, p4)
                end
            until false
        end
    end
end

--- Input loop: listens for monitor touch events.
-- Runs in parallel with logicLoop(). When the player touches the
-- monitor, CC fires a "monitor_touch" event with x,y coordinates.
-- We pass these to handleClick() which checks against registered buttons.
local function inputLoop()
    while true do
        local event, side, x, y = os.pullEvent("monitor_touch")
        pcall(handleClick, x, y)
    end
end

-----------------------------------------------------------------------
-- ENTRY POINT
-----------------------------------------------------------------------
-- This code runs once when the program starts. It prints a diagnostic
-- summary to the terminal (the computer's built-in screen), launches
-- child monitor programs as background tabs, then starts the main loops.
term.clear()
term.setCursorPos(1, 1)
print("Create Factory Controller V" .. config.VERSION .. " Online")
print("")
print("Peripherals:")
local pStatus = peripherals.status()
for name, info in pairs(pStatus) do
    local mark = info.connected and "OK" or (info.required and "MISSING" or "--")
    print("  [" .. mark .. "] " .. name)
end
print("  [" .. (mon and "OK" or "MISSING") .. "] monitor (top)")
print("  [" .. (modem and "OK" or "--") .. "] wireless modem")
print("")

-- Launch child monitor programs in background tabs.
-- CC:Tweaked's shell.openTab() runs a program in a separate tab/thread.
-- Each child program claims its own monitor and runs independently.
-- If the file doesn't exist, we skip it gracefully.
local function launchChild(file, args, label)
    if not fs.exists(file) then
        print("  [--] " .. label .. " (" .. file .. " not found)")
        return nil
    end
    local tabId
    if args then
        tabId = shell.openTab(file, table.unpack(args))
    else
        tabId = shell.openTab(file)
    end
    if tabId then
        print("  [OK] " .. label .. " (tab " .. tabId .. ")")
    else
        print("  [!!] " .. label .. " failed to launch")
    end
    return tabId
end

-- Launch the three child monitors:
--   1. Boiler sensor display on MONITOR_BOILER
--   2. Machine sensor display on MONITOR_MACHINES
--   3. Request log display on MONITOR_REQUEST
print("Child Monitors:")
launchChild("v3/monitor_sensors.lua", {config.MONITOR_BOILER, "boiler"}, "Boiler Monitor -> " .. config.MONITOR_BOILER)
launchChild("v3/monitor_sensors.lua", {config.MONITOR_MACHINES, "machine"}, "Machine Monitor -> " .. config.MONITOR_MACHINES)
launchChild("v3/monitor_request.lua", {}, "Request Monitor -> " .. config.MONITOR_REQUEST)
print("")

-- Give children time to claim their monitors before we start
-- drawing on ours (prevents monitor conflicts).
sleep(2)

-- Start the two parallel loops. waitForAny() runs both coroutines
-- simultaneously and exits if either one returns or errors.
parallel.waitForAny(logicLoop, inputLoop)
