-- ============================================================================
-- lib_peripherals.lua — Peripheral Discovery, Caching & Auto-Reconnect
-- ============================================================================
--
-- WHAT THIS FILE DOES:
--   Provides a single, reliable way to find and use peripherals (monitors,
--   modems, LogicLink blocks, etc.) attached to the computer.  Instead of
--   every program calling peripheral.find() and handling disconnections
--   itself, they all call peripherals.get("logiclink") and this library
--   takes care of:
--     • Finding the peripheral the first time
--     • Caching the handle so repeated calls are instant
--     • Detecting when a peripheral disconnects (block broken, cable cut)
--     • Automatically re-finding it after a cooldown period
--
-- WHY IT EXISTS:
--   In CC:Tweaked, peripherals can disconnect at any time (cables get broken,
--   chunks unload, blocks get mined).  Without this library, every program
--   would need its own reconnection logic — lots of duplicated code and
--   inconsistent error handling.
--
-- HOW TO USE:
--   local peripherals = require("v3.lib_peripherals")
--   peripherals.initFactory()        -- register + discover all standard peripherals
--   local ll = peripherals.get("logiclink")  -- cached, auto-reconnecting
--   local mon = peripherals.get("monitor")   -- same pattern for any type
--
-- KEY CONCEPTS:
--   • "Registry" — an internal table that tracks each peripheral type:
--     its handle, when we last tried to find it, and whether it's required.
--   • "Required" — if a peripheral is marked required and can't be found,
--     the system logs a warning.  Non-required ones fail silently.
--   • "Liveness check" — before returning a cached handle, we call
--     peripheral.getName() on it.  If that throws an error, the peripheral
--     has disconnected and we clear the cache.
--
-- ============================================================================

local config = require("v3.config")

local peripherals = {}

-- Internal registry: maps peripheral type name → {handle, lastCheck, required}
-- This is a module-level table (not exposed outside) that stores the state
-- for every registered peripheral type.
local registry = {}

-- Minimum milliseconds between reconnection attempts for a disconnected
-- peripheral.  Prevents spamming peripheral.find() every tick when a
-- device is genuinely gone.
local RECONNECT_INTERVAL = 5000  -- 5 seconds

-----------------------------------------------------------------------
-- REGISTRATION
-----------------------------------------------------------------------
-- Before you can get() a peripheral, you must register its type.
-- Registration tells the library: "I might need a peripheral of this
-- type — please track it for me."

--- Register a peripheral type for managed discovery.
-- Call this once per type, typically at startup.  After registering,
-- you can call peripherals.get(typeName) to find and cache it.
-- @param typeName string  CC peripheral type (e.g. "logiclink", "monitor")
-- @param required boolean  If true, get() logs warnings when it can't find one
-- @param alias   string   Optional short name for get() lookups (e.g. "ll" for "logiclink")
function peripherals.register(typeName, required, alias)
    registry[typeName] = {
        handle = nil,
        lastCheck = 0,
        required = required or false,
        alias = alias or typeName
    }
    -- Also register alias → same entry
    if alias and alias ~= typeName then
        registry[alias] = registry[typeName]
    end
end

-----------------------------------------------------------------------
-- DISCOVERY
-----------------------------------------------------------------------
-- These functions find peripherals and return their handles.
-- A "handle" is a Lua object that lets you call methods on the
-- peripheral (e.g., monitor.clear(), modem.transmit()).

--- Find a peripheral by type or alias. Caches handle, auto-reconnects.
-- This is the main function other programs call.  It works like this:
--   1. Check if we have a cached handle that's still alive
--   2. If cached handle is dead, clear it
--   3. If enough time has passed since our last attempt, try to find it again
--   4. Return the handle (or nil if not found)
-- @param name string  Peripheral type or registered alias
-- @return peripheral handle or nil
function peripherals.get(name)
    local entry = registry[name]
    if not entry then
        -- Not registered — do a one-shot find
        local handle = peripheral.find(name)
        return handle
    end

    -- Return cached handle if still valid.
    -- pcall() runs a function in "protected" mode — if it errors, pcall
    -- returns false instead of crashing the program.  We use this to test
    -- whether the peripheral is still connected.
    if entry.handle then
        local ok = pcall(function()
            -- Quick liveness check — peripheral.getName() is a cheap call
            -- that will error if the peripheral has been disconnected.
            peripheral.getName(entry.handle)
        end)
        if ok then return entry.handle end
        -- Handle went stale
        entry.handle = nil
    end

    -- Rate-limit reconnection attempts
    local now = os.epoch("utc")
    if now - entry.lastCheck < RECONNECT_INTERVAL then
        return nil
    end
    entry.lastCheck = now

    -- Attempt discovery
    local handle = peripheral.find(entry.alias ~= name and entry.alias or name)

    if handle then
        entry.handle = handle
        return handle
    end

    if entry.required then
        peripherals.log("WARN", "Required peripheral '" .. name .. "' not found")
    end

    return nil
end

--- Get all peripherals of a given type (returns array).
-- Unlike get() which returns one handle, this returns ALL peripherals
-- matching the type.  Useful for finding all monitors when you don't
-- know how many are connected.
-- @param typeName string  CC peripheral type (e.g. "monitor")
-- @return table  Array of peripheral handles (may be empty)
function peripherals.getAll(typeName)
    local results = {peripheral.find(typeName)}
    return results
end

--- Force refresh a cached peripheral (clear cache and re-discover).
-- Use this when you know a peripheral was just reconnected (e.g.,
-- after a "peripheral" or "peripheral_detach" event).
-- @param name string  Peripheral type or alias
-- @return peripheral handle or nil
function peripherals.refresh(name)
    local entry = registry[name]
    if entry then
        entry.handle = nil
        entry.lastCheck = 0
    end
    return peripherals.get(name)
end

-----------------------------------------------------------------------
-- MONITOR HELPERS
-----------------------------------------------------------------------
-- Convenience functions for working with monitors specifically.
-- Monitors are the most common peripheral and often need special
-- handling (finding the biggest one, ranking by size, etc.).

--- Find the largest connected monitor by screen area (width × height).
-- Useful when you want your main display on the biggest available screen.
-- @return monitor handle, width, height (or nil if no monitors found)
function peripherals.getLargestMonitor()
    local monitors = peripherals.getAll("monitor")
    if #monitors == 0 then return nil end

    local best, bestArea = nil, 0
    for _, m in ipairs(monitors) do
        local ok, w, h = pcall(m.getSize)
        if ok then
            local area = w * h
            if area > bestArea then
                bestArea = area
                best = m
            end
        end
    end

    if best then
        local w, h = best.getSize()
        return best, w, h
    end
    return nil
end

--- Find the Nth largest monitor (1 = largest, 2 = second largest, etc.).
-- Sorts all monitors by screen area and returns the one at the given rank.
-- Useful for assigning specific monitors to specific displays by size.
-- @param rank number  1-based rank (1 = biggest)
-- @return monitor handle, width, height (or nil if rank exceeds count)
function peripherals.getMonitorByRank(rank)
    local monitors = peripherals.getAll("monitor")
    if #monitors == 0 then return nil end

    -- Sort by area descending
    local sorted = {}
    for _, m in ipairs(monitors) do
        local ok, w, h = pcall(m.getSize)
        if ok then
            table.insert(sorted, {handle = m, w = w, h = h, area = w * h})
        end
    end
    table.sort(sorted, function(a, b) return a.area > b.area end)

    local entry = sorted[rank]
    if entry then
        return entry.handle, entry.w, entry.h
    end
    return nil
end

--- Find a wireless modem specifically (prefers wireless over wired).
-- CC:Tweaked has two types of modems: wired (networking cables) and
-- wireless (ender modems for cross-dimension communication).
-- This function checks each modem and picks a wireless one if available,
-- falling back to any modem if no wireless is found.
-- @return modem handle or nil
function peripherals.getWirelessModem()
    for _, name in ipairs(peripheral.getNames()) do
        local p = peripheral.wrap(name)
        if p and peripheral.getType(name) == "modem" then
            if p.isWireless and p.isWireless() then
                return p
            end
        end
    end
    -- Fallback: any modem
    return peripheral.find("modem")
end

-----------------------------------------------------------------------
-- BULK INIT
-----------------------------------------------------------------------
-- These functions register and discover all peripherals needed for a
-- specific role.  Call the appropriate one at startup based on what
-- type of computer this is (factory server vs pocket remote).

--- Register and discover all standard peripherals for the factory system.
-- This is the "one-call setup" for the main factory controller computer.
-- It registers logiclink (required), monitor (required), and several
-- optional peripherals (redstone controller, train controller, etc.),
-- then immediately tries to find each one.
-- Call this once at startup.
function peripherals.initFactory()
    peripherals.register("logiclink", true)
    peripherals.register("redstone_controller", false)
    peripherals.register("train_controller", false)
    peripherals.register("monitor", true)
    peripherals.register("chat_box", false)
    peripherals.register("playerDetector", false)

    -- Do initial discovery
    for typeName, entry in pairs(registry) do
        if typeName == entry.alias then  -- avoid double-discover for aliases
            peripherals.get(typeName)
        end
    end

    return true
end

--- Register peripherals for pocket computer (minimal set).
-- Pocket computers only need a wireless modem — they don't have
-- monitors, LogicLink blocks, or other peripherals attached.
-- @return boolean  true if a wireless modem was found
function peripherals.initPocket()
    -- Pocket computers only need modem
    return peripherals.getWirelessModem() ~= nil
end

-----------------------------------------------------------------------
-- STATUS
-----------------------------------------------------------------------
-- Functions for checking the health of all registered peripherals.
-- Used at startup to print a status report and during runtime to
-- detect when something disconnects.

--- Get status of all registered peripherals.
-- Returns a table mapping each type name to its connection status.
-- Used by startup.lua to print the peripheral status on the terminal.
-- @return table  {typeName = {connected=bool, required=bool}}
function peripherals.status()
    local result = {}
    local seen = {}
    for typeName, entry in pairs(registry) do
        if not seen[entry] then
            seen[entry] = true
            result[typeName] = {
                connected = entry.handle ~= nil,
                required = entry.required
            }
        end
    end
    return result
end

--- Check if all required peripherals are connected.
-- Iterates through the registry and tries to get() each required peripheral.
-- If any required peripheral can't be found, returns false + its name.
-- Used at startup to abort if critical hardware is missing.
-- @return boolean, string  true if all OK, or false + name of first missing peripheral
function peripherals.allRequired()
    local seen = {}
    for typeName, entry in pairs(registry) do
        if not seen[entry] and entry.required then
            seen[entry] = true
            if not peripherals.get(typeName) then
                return false, typeName
            end
        end
    end
    return true
end

-----------------------------------------------------------------------
-- LOGGING
-----------------------------------------------------------------------
-- Internal logging for peripheral discovery issues.
-- Only writes to disk when config.DEBUG_MODE is true.

--- Write a debug log entry for peripheral operations.
-- @param level   string  "WARN", "INFO", "ERROR", etc.
-- @param message string  Description of what happened
function peripherals.log(level, message)
    if config.DEBUG_MODE then
        local f = fs.open("peripheral_debug.log", "a")
        if f then
            f.writeLine("[" .. os.date("%H:%M:%S") .. "] " .. level .. ": " .. message)
            f.close()
        end
    end
end

return peripherals
