-- ============================================================================
-- lib_storage.lua — Inventory, Gauges & Item Requests via LogicLink
-- ============================================================================
--
-- WHAT THIS FILE DOES:
--   This is the "data layer" for the factory.  It talks to the LogicLink
--   peripheral to read inventory levels, gauge targets, and request item
--   deliveries.  It also manages the request history log (saved to disk
--   so the Request Monitor can display it).
--
-- WHY IT EXISTS:
--   In V2, inventory scanning required 3 different peripherals (StockTicker,
--   block_reader, RedstoneRequester) with ~560 lines of code.  LogicLink
--   replaces all of that with a single peripheral, so this library is
--   much simpler.
--
-- HOW IT WORKS:
--   1. init() — checks that LogicLink is available and linked to the network
--   2. getGauges() — reads gauge data (item targets set in Create's UI)
--   3. getInventory() — gets full item list, cross-referenced with gauges
--   4. requestItem() — asks LogicLink to deliver items to a destination
--   5. findItem() — searches cached inventory by name (for pocket search)
--   6. Request logging — saves delivery history to a shared file
--
-- KEY CONCEPTS:
--   • "Gauges" are Create mod components that track target amounts for items.
--     LogicLink reads these targets so the system knows what to keep in stock.
--   • "Inventory" is the full list of all items in the connected storage network.
--   • Items in the inventory that have a gauge target appear as "Gauge Items."
--     Items without a target appear as "Inventory Only."
--   • The request log is stored as a file so multiple programs (tabs) running
--     on the same computer can share it.
--
-- ============================================================================

local config = require("v3.config")
local peripherals = require("v3.lib_peripherals")

local storage = {}

-- Caches — these hold the most recent scan results in memory so other
-- code can access them without re-scanning the network every time.
storage.gaugeCache = {}     -- Array of gauge items: [{item, displayName, target, count}]
storage.invCache = {}       -- Array of non-gauge items: [{item, displayName, count}]
storage.requestLog = {}     -- Array of request history: [{time, itemId, quantity, destination, success}]

-----------------------------------------------------------------------
-- INITIALIZATION
-----------------------------------------------------------------------

--- Initialize storage system. Returns true if logiclink is available.
-- Call this once at startup.  Checks that the LogicLink peripheral
-- exists AND is linked to a Create logistics network (not just placed
-- next to the computer, but actually connected via network cables).
-- @return boolean, string  true if ready, or false + error description
function storage.init()
    local ll = peripherals.get("logiclink")
    if not ll then
        return false, "LogicLink peripheral not found"
    end
    if not ll.isLinked() then
        return false, "LogicLink not connected to network"
    end
    return true
end

-----------------------------------------------------------------------
-- GAUGE/INVENTORY SCANNING
-----------------------------------------------------------------------
-- These functions read the current state of the factory's storage network.
-- "Gauges" are Create mod components that define target stock levels;
-- "Inventory" is the full list of every item type in the network.

--- Get all gauge data from LogicLink.
-- A "gauge" in Create tells the factory "I want N of item X in stock."
-- This function reads all gauges and returns their targets + current counts.
-- The main controller uses this to decide whether items need restocking.
-- @return table  Array of gauge entries: [{item, displayName, target, count, satisfied, address}]
function storage.getGauges()
    local ll = peripherals.get("logiclink")
    if not ll then return {} end

    local ok, gauges = pcall(ll.getGauges)
    if not ok or not gauges then return {} end

    local result = {}
    for _, g in ipairs(gauges) do
        table.insert(result, {
            item = g.item or "",
            displayName = g.itemDisplayName or g.item or "Unknown",
            target = g.targetAmount or 0,
            count = g.currentStock or 0,
            satisfied = g.satisfied or false,
            address = g.address or "",
        })
    end

    storage.gaugeCache = result
    return result
end

--- Get full network inventory from LogicLink.
-- Returns TWO lists:
--   1. gaugeItems — items that have a gauge target (tracked items)
--   2. invItems — items in the network with no gauge (untracked surplus)
-- Items are cross-referenced against gauges so the display can show
-- "stocked vs target" for tracked items and just "count" for others.
-- Also loads extra targets from the set_amount config file.
-- @param gauges  table  Gauge data from getGauges() (for cross-reference)
-- @return gaugeItems, invItems  Two arrays of item tables
function storage.getInventory(gauges)
    local ll = peripherals.get("logiclink")
    if not ll then return {}, {} end

    local ok, items = pcall(ll.list)
    if not ok or not items then return {}, {} end

    -- Build gauge lookup: maps itemId → gauge entry for O(1) access.
    -- This lets us quickly check "does this inventory item have a gauge?"
    local gaugeLookup = {}
    for _, g in ipairs(gauges or {}) do
        if g.item and g.item ~= "" then
            gaugeLookup[g.item] = g
        end
    end

    -- Also load targets from the set_amount config file.
    -- This lets users define target amounts in a simple text file
    -- without needing to set up Create gauges in-game.
    local fileTargets = storage.loadTargetsFile()
    for itemId, target in pairs(fileTargets) do
        if not gaugeLookup[itemId] then
            gaugeLookup[itemId] = {
                item = itemId,
                displayName = itemId:match("([^:]+)$") or itemId,
                target = target,
                count = 0,
            }
        end
    end

    -- Walk through every item in the network and sort it into either
    -- the gauge list (has a target) or the inventory list (no target).
    local gaugeItems = {}
    local invItems = {}
    local seen = {}  -- track which items we've processed (avoid duplicates)

    for _, item in ipairs(items) do
        local id = item.name or ""
        if id ~= "" then
            seen[id] = true
            local gauge = gaugeLookup[id]
            if gauge then
                table.insert(gaugeItems, {
                    item = id,
                    displayName = item.displayName or gauge.displayName or id:match("([^:]+)$") or id,
                    count = item.count or 0,
                    target = gauge.target or 0,
                })
            else
                table.insert(invItems, {
                    item = id,
                    displayName = item.displayName or id:match("([^:]+)$") or id,
                    count = item.count or 0,
                })
            end
        end
    end

    -- Add gauge items with 0 stock that weren't in the inventory scan.
    -- E.g., if you have a gauge for "iron_ingot" but currently have 0,
    -- it won't appear in ll.list() — but we still want to show it as
    -- "0 / 256" on the display so you know it needs restocking.
    for itemId, gauge in pairs(gaugeLookup) do
        if not seen[itemId] then
            table.insert(gaugeItems, {
                item = itemId,
                displayName = gauge.displayName or itemId:match("([^:]+)$") or itemId,
                count = 0,
                target = gauge.target or 0,
            })
        end
    end

    -- Sort both lists by mod namespace (e.g., "create", "minecraft") and
    -- then alphabetically within each namespace.  This groups related
    -- items together on the display (all Create items together, etc.).
    local function sortByCategoryThenName(a, b)
        local modA = a.item:match("^([^:]+):") or "zzz"
        local modB = b.item:match("^([^:]+):") or "zzz"
        if modA ~= modB then return modA < modB end
        return a.displayName < b.displayName
    end

    table.sort(gaugeItems, sortByCategoryThenName)
    table.sort(invItems, sortByCategoryThenName)

    storage.gaugeCache = gaugeItems
    storage.invCache = invItems
    return gaugeItems, invItems
end

--- Get network storage statistics.
-- Returns high-level info about the storage network (how many item types,
-- total item count).  Used for the status bar on the main monitor.
-- @return table {itemTypes, totalItems} or nil if LogicLink unavailable
function storage.getStorageStats()
    local ll = peripherals.get("logiclink")
    if not ll then return nil end

    local ok, info = pcall(ll.getNetworkInfo)
    if not ok or not info then return nil end

    return {
        itemTypes = info.itemTypes or 0,
        totalItems = info.totalItems or 0,
    }
end

-----------------------------------------------------------------------
-- ITEM REQUESTS
-----------------------------------------------------------------------
-- These functions let the pocket computer (or other code) ask the
-- Create logistics network to deliver items to a specific location.
-- LogicLink handles the actual item movement — we just tell it what
-- item, how many, and where to send it.

--- Request an item from the logistics network.
-- Tells LogicLink to extract items from storage and deliver them to
-- the specified destination (a frogport label in the Create network).
-- The result is logged to the shared request log file.
-- @param itemId      string  Full Minecraft item ID (e.g., "minecraft:iron_ingot")
-- @param quantity    number  How many to request
-- @param destination string  Where to deliver (frogport label, default from config)
-- @return boolean, string  true + success message, or false + error message
function storage.requestItem(itemId, quantity, destination)
    local ll = peripherals.get("logiclink")
    if not ll then
        return false, "LogicLink not available"
    end

    destination = destination or config.REQUEST_DESTINATION
    quantity = quantity or 1

    local ok, result = pcall(ll.requestItem, itemId, quantity, destination)

    storage.logRequest(itemId, quantity, destination, ok and result)

    if ok and result then
        return true, "Requested " .. quantity .. "x " .. itemId
    else
        return false, "Request failed: " .. tostring(result)
    end
end

--- Bulk request multiple items at once.
-- Sends a batch of item requests to LogicLink in a single call.
-- More efficient than calling requestItem() in a loop.
-- @param items       table   Array of {name="minecraft:iron_ingot", count=64}
-- @param destination string  Where to deliver all items
-- @return boolean, string  true + summary message, or false + error
function storage.requestItems(items, destination)
    local ll = peripherals.get("logiclink")
    if not ll then
        return false, "LogicLink not available"
    end

    destination = destination or config.REQUEST_DESTINATION

    local ok, result = pcall(ll.requestItems, items, destination)

    if ok and result then
        for _, item in ipairs(items) do
            storage.logRequest(item.name, item.count, destination, true)
        end
        return true, "Bulk request sent (" .. #items .. " items)"
    else
        return false, "Bulk request failed: " .. tostring(result)
    end
end

-----------------------------------------------------------------------
-- SEARCH
-----------------------------------------------------------------------
-- Lets the pocket computer search for items by partial name.
-- Searches the cached gauge and inventory lists (no network call needed).

--- Search for items matching a query string.
-- Does a case-insensitive substring search against both the display name
-- and the full Minecraft item ID.  Used by the pocket's "Check Item" feature.
-- @param searchTerm string  Partial item name (e.g., "iron" matches "Iron Ingot")
-- @return table  Array of matching items: [{item, displayName, count, target}]
function storage.findItem(searchTerm)
    local results = {}
    local term = searchTerm:lower()

    for _, item in ipairs(storage.gaugeCache) do
        if item.displayName:lower():find(term, 1, true)
            or item.item:lower():find(term, 1, true) then
            table.insert(results, item)
        end
    end
    for _, item in ipairs(storage.invCache) do
        if item.displayName:lower():find(term, 1, true)
            or item.item:lower():find(term, 1, true) then
            table.insert(results, item)
        end
    end

    return results
end

-----------------------------------------------------------------------
-- LOGGING & HISTORY (file-backed for cross-tab sharing)
-----------------------------------------------------------------------
-- The request log is saved to a file on disk so that multiple programs
-- running in different shell tabs on the same computer can all see it.
-- For example, startup.lua writes to the log when fulfilling a request,
-- and monitor_request.lua reads the log to display it on a monitor.

local REQUEST_LOG_FILE = config.REQUEST_LOG_FILE or "v3/request_log.dat"

--- Save the in-memory request log to disk.
-- Uses textutils.serialise() to convert the Lua table to a string
-- that can be written to a file and read back later.
local function saveRequestLog()
    local f = fs.open(REQUEST_LOG_FILE, "w")
    if f then
        f.write(textutils.serialise(storage.requestLog))
        f.close()
    end
end

--- Load request log from disk (used by monitor_request tab).
-- Reads the file and converts the serialised string back to a Lua table.
-- Returns empty array if the file doesn't exist or is corrupted.
local function loadRequestLog()
    if not fs.exists(REQUEST_LOG_FILE) then return {} end
    local f = fs.open(REQUEST_LOG_FILE, "r")
    if not f then return {} end
    local data = f.readAll()
    f.close()
    local ok, result = pcall(textutils.unserialise, data)
    if ok and type(result) == "table" then
        return result
    end
    return {}
end

--- Log a request to the history and persist to file.
-- Inserts the new entry at position 1 (newest first) and keeps
-- only the last 20 entries to prevent the file from growing forever.
-- @param itemId      string   Item that was requested
-- @param quantity    number   How many were requested
-- @param destination string   Where they were sent
-- @param success     boolean  Whether the request succeeded
function storage.logRequest(itemId, quantity, destination, success)
    table.insert(storage.requestLog, 1, {
        time = os.date("%H:%M:%S"),
        itemId = itemId,
        quantity = quantity,
        destination = destination,
        success = success,
    })
    -- Keep last 20
    while #storage.requestLog > 20 do
        table.remove(storage.requestLog)
    end
    saveRequestLog()
end

--- Get request history (reads from shared file to pick up cross-tab entries).
-- Re-reads the file each time because another tab might have added entries
-- since we last checked.
-- @return table  Array of log entries (newest first)
function storage.getRequestLog()
    storage.requestLog = loadRequestLog()
    return storage.requestLog
end

-----------------------------------------------------------------------
-- CONFIG FILE
-----------------------------------------------------------------------
-- Reads item targets from the "set_amount" text file.
-- This is an alternative to Create gauges — you can define targets
-- in a simple text file without setting up in-game gauge blocks.

--- Load item targets from set_amount config file.
-- File format: one line per item, each line is "itemId amount"
-- Lines starting with # are comments.  Blank lines are ignored.
-- Example:
--   minecraft:iron_ingot 256
--   create:brass_ingot 64
-- @return table  Map of {itemId = targetAmount}
function storage.loadTargetsFile()
    local targets = {}
    if not fs.exists(config.TARGET_FILE) then return targets end

    local f = fs.open(config.TARGET_FILE, "r")
    if not f then return targets end

    local line = f.readLine()
    while line do
        line = line:match("^%s*(.-)%s*$")  -- trim
        if line ~= "" and not line:match("^#") then
            local id, amount = line:match("^(%S+)%s+(%d+)")
            if id and amount then
                targets[id] = tonumber(amount)
            end
        end
        line = f.readLine()
    end
    f.close()

    return targets
end

return storage
