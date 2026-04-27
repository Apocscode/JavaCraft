-- ============================================================================
-- lib_protocol.lua — Structured Wireless Protocol
-- ============================================================================
--
-- WHAT THIS FILE DOES:
--   Defines the "language" that the factory server and pocket computer use
--   to talk to each other over wireless modems.  Every wireless message
--   is wrapped in a structured "packet" (a Lua table with specific fields)
--   so both sides know how to read it.
--
-- WHY IT EXISTS:
--   Without a shared protocol, each program would invent its own message
--   format, making it easy for bugs to slip in.  This library provides:
--     • A consistent packet structure (_proto, type, sender, target, data)
--     • Version checking (so old/new clients don't mix incompatibly)
--     • Builder functions for common packet types (status, alert, response)
--     • Send/receive helpers that handle modem channel details
--
-- HOW TO USE:
--   local protocol = require("v3.lib_protocol")
--   -- Build a packet and send it:
--   local pkt = protocol.statusPacket("IDLE", true, false, {}, nil, 0)
--   protocol.send(modem, pkt)
--   -- Receive a packet:
--   local pkt = protocol.receive(5)  -- wait up to 5 seconds
--
-- KEY CONCEPTS:
--   • MSG types are sent FROM the server TO clients (broadcasts / replies).
--   • CMD types are sent FROM clients (pocket) TO the server (requests).
--   • Every packet has a "type" field that tells the receiver what to do.
--   • Packets can be "targeted" (sent to one computer) or "broadcast"
--     (target = nil, meaning everyone on the channel hears it).
--
-- ============================================================================

local config = require("v3.config")

local protocol = {}

-- Protocol version number.  If you change the packet format in a way that
-- would break older code, bump this number.  The receiver will reject
-- packets with a mismatched version so you get a clear error instead of
-- mysterious failures.
protocol.VERSION = 1

-- ============================================================================
-- MESSAGE TYPES (Server → Client)
-- ============================================================================
-- These are the "type" values the server puts in packets it sends out.
-- The pocket computer checks packet.type to decide how to handle it.
protocol.MSG = {
    STATUS          = "STATUS",        -- Periodic factory status broadcast (mode, low items, etc.)
    ALERT           = "ALERT",         -- One-off alert (e.g., "Iron Ingot is empty!")
    TRAIN_ALERT     = "TRAIN_ALERT",   -- Train-specific alert (derailed, stalled, etc.)
    INVENTORY       = "INVENTORY",     -- Full inventory list (response to GET_INVENTORY)
    SCAN_RESULT     = "SCAN_RESULT",   -- Result of a gauge scan
    RESPONSE        = "RESPONSE",      -- Generic reply to any command (wraps command + result)
    HEARTBEAT       = "HEARTBEAT",     -- Periodic "I'm alive" keepalive
}

-- ============================================================================
-- COMMAND TYPES (Client → Server)
-- ============================================================================
-- These are the "type" values the pocket computer puts in packets it sends
-- to the server.  The server checks packet.type to decide which action to take.
protocol.CMD = {
    GET_STATUS      = "GET_STATUS",       -- "Tell me your current status"
    TOGGLE_MODE     = "TOGGLE_MODE",      -- "Switch between Auto and Manual mode"
    TOGGLE_MANUAL   = "TOGGLE_MANUAL",    -- "In Manual mode, toggle conveyor ON/OFF"
    RELOAD_CONFIG   = "RELOAD_CONFIG",    -- "Re-read the config file from disk"
    SCAN_GAUGES     = "SCAN_GAUGES",      -- "Force an immediate gauge re-scan"
    REQUEST_ITEM    = "REQUEST_ITEM",     -- "Deliver X of item Y to destination Z"
    CHECK_ITEM      = "CHECK_ITEM",       -- "Search for an item by name"
    GET_INVENTORY   = "GET_INVENTORY",    -- "Send me the full gauge item list"
    GET_TRAINS      = "GET_TRAINS",       -- "Send me live train data"
    GET_REDSTONE    = "GET_REDSTONE",     -- "Send me all redstone channels + presets"
    SET_REDSTONE    = "SET_REDSTONE",     -- "Set a specific channel's power level"
    ADD_REDSTONE    = "ADD_REDSTONE",     -- "Create a new redstone channel"
    REMOVE_REDSTONE = "REMOVE_REDSTONE",  -- "Delete a redstone channel"
    ALL_REDSTONE    = "ALL_REDSTONE",     -- "Set ALL channels to a power level (kill switch)"
    TIMER_REDSTONE  = "TIMER_REDSTONE",   -- "Pulse a channel ON for N seconds, optionally repeating"
    PING            = "PING",             -- "Are you there?" (connectivity test)
}

-- ============================================================================
-- SEVERITY LEVELS
-- ============================================================================
-- Used in ALERT and TRAIN_ALERT packets to indicate how serious a problem is.
-- The pocket computer colors these differently: INFO=blue, WARN=orange, CRIT=red.
protocol.SEVERITY = {
    INFO = "INFO",   -- Informational (no action needed)
    WARN = "WARN",   -- Warning (something may need attention)
    CRIT = "CRIT",   -- Critical (something is broken and needs immediate attention)
}

-----------------------------------------------------------------------
-- PACKET CONSTRUCTION
-----------------------------------------------------------------------
-- Every wireless message is wrapped in a "packet" — a Lua table with
-- standard fields so the receiver always knows how to parse it.
-- The packet looks like this:
--   {
--     _proto = 1,              -- Protocol version (for compatibility checks)
--     type = "STATUS",         -- What kind of message this is
--     sender = 31,             -- Which computer sent it
--     target = nil,            -- Who should receive it (nil = everyone)
--     timestamp = 1234567890,  -- When it was created (milliseconds since epoch)
--     data = { ... },          -- The actual payload (varies by type)
--   }

--- Build a protocol-compliant packet.
-- This is the low-level builder.  Most code uses the convenience functions
-- below (statusPacket, alertPacket, etc.) which call pack() internally.
-- @param msgType string  One of protocol.MSG or protocol.CMD values
-- @param data    table   Payload data (type-specific)
-- @param target  number  Optional target computer ID (nil = broadcast)
-- @return table  Well-formed packet ready to send via protocol.send()
function protocol.pack(msgType, data, target)
    return {
        _proto = protocol.VERSION,
        type = msgType,
        sender = os.getComputerID(),
        target = target,
        timestamp = os.epoch("utc"),
        data = data or {},
    }
end

-----------------------------------------------------------------------
-- PACKET VALIDATION
-----------------------------------------------------------------------
-- When a packet arrives over the modem, we need to check that it's
-- actually a valid protocol packet (not random garbage from another
-- mod or a corrupted transmission).  unpack() does that validation.

--- Validate an incoming packet. Returns the packet if valid, nil + reason if not.
-- Also handles backward compatibility: old-format packets that use
-- {command="X"} instead of {type="X"} are automatically converted.
-- @param raw     any     Raw modem_message payload (could be anything)
-- @return table|nil, string  Validated packet or nil + error reason
function protocol.unpack(raw)
    if type(raw) ~= "table" then
        return nil, "not a table"
    end

    -- Accept both old-format (no _proto) and new-format packets.
    -- This lets newer servers talk to older clients during upgrades.
    if raw._proto and raw._proto ~= protocol.VERSION then
        return nil, "version mismatch: got " .. tostring(raw._proto)
                    .. ", expected " .. protocol.VERSION
    end

    if not raw.type and not raw.command then
        return nil, "missing type/command field"
    end

    -- Normalize old-format packets: {command="X"} → {type="X"}
    -- This lets the system understand messages from older pocket clients
    -- that haven't been updated to the new format yet.
    if raw.command and not raw.type then
        raw.type = raw.command
        raw.data = raw.data or raw
    end

    return raw
end

--- Check if a packet is targeted at us (or is a broadcast).
-- Broadcast packets (target = nil) are accepted by everyone.
-- Targeted packets are only accepted if target matches our computer ID.
-- @param packet table  Validated packet
-- @return boolean  true if we should process this packet
function protocol.isForUs(packet)
    if not packet.target then return true end  -- broadcast
    return packet.target == os.getComputerID()
end

-----------------------------------------------------------------------
-- STATUS PACKET BUILDERS
-----------------------------------------------------------------------
-- These convenience functions create common packet types with the right
-- structure.  Using these instead of raw pack() prevents typos in field
-- names and ensures all required fields are present.

--- Build a STATUS message (server → clients).
-- This is the main broadcast that the server sends every few seconds.
-- The pocket computer uses this to update its Dashboard page.
-- @param status        string   "WORKING" | "IDLE" | "MANUAL ON" | "MANUAL OFF"
-- @param autoMode      boolean  true if factory is in automatic mode
-- @param manualOn      boolean  true if manual override is active
-- @param lowItems      table    Array of {name, count, target} for items below target
-- @param storagePercent number  Overall storage usage 0-100 (or nil if unknown)
-- @param trainAlerts   number   Count of active train problems
function protocol.statusPacket(status, autoMode, manualOn, lowItems, storagePercent, trainAlerts)
    return protocol.pack(protocol.MSG.STATUS, {
        status = status,              -- "WORKING" | "IDLE" | "MANUAL ON" | "MANUAL OFF"
        autoMode = autoMode,          -- boolean
        manualOn = manualOn,          -- boolean
        lowItems = lowItems or {},    -- [{name, count, target}] (max 10)
        storage = storagePercent,     -- number 0-100 or nil
        trainAlerts = trainAlerts,    -- number (count of active train alerts)
    })
end

--- Build an ALERT message (server → clients).
-- Sent when a critical event happens (e.g., an item's stock hits zero).
-- The pocket computer displays these on its Alerts page.
-- @param message  string  Human-readable alert text
-- @param severity string  One of protocol.SEVERITY values (INFO/WARN/CRIT)
-- @param source   string  Where the alert came from ("factory", "trains", "system")
function protocol.alertPacket(message, severity, source)
    return protocol.pack(protocol.MSG.ALERT, {
        message = message,
        severity = severity or protocol.SEVERITY.WARN,
        source = source or "factory",  -- "factory" | "trains" | "system"
    })
end

--- Build a TRAIN_ALERT message (server → clients).
-- Sent when the train network has problems (derailments, stalls, etc.).
-- Contains an array of diagnostic entries from the train controller.
-- @param diagnostics table  Array of {type, severity, desc, trainName, x, z}
function protocol.trainAlertPacket(diagnostics)
    return protocol.pack(protocol.MSG.TRAIN_ALERT, {
        diagnostics = diagnostics or {},  -- [{type, severity, desc, trainName, x, z}]
    })
end

--- Build a RESPONSE packet (reply to a command).
-- When the server finishes handling a command from the pocket, it sends
-- back a RESPONSE so the pocket knows whether the action succeeded.
-- @param cmdType  string  Which command this is replying to (e.g., CMD.REQUEST_ITEM)
-- @param success  boolean Whether the command succeeded
-- @param data     table   Result data (varies by command)
-- @param targetId number  Computer ID to send the reply to
function protocol.responsePacket(cmdType, success, data, targetId)
    return protocol.pack(protocol.MSG.RESPONSE, {
        command = cmdType,
        success = success,
        result = data,
    }, targetId)
end

--- Build a HEARTBEAT packet (periodic keepalive).
-- Sent periodically to confirm the computer is still running.
-- Contains uptime and computer identification for monitoring.
function protocol.heartbeatPacket()
    return protocol.pack(protocol.MSG.HEARTBEAT, {
        uptime = os.clock(),
        computerID = os.getComputerID(),
        label = os.getComputerLabel() or "unknown",
    })
end

-----------------------------------------------------------------------
-- COMMAND PACKET BUILDERS (client → server)
-----------------------------------------------------------------------

--- Build a command packet (client → server).
-- The pocket computer calls this to create a request for the server.
-- The cmdType tells the server what action to perform; data holds
-- any parameters the command needs (e.g., item ID and quantity).
-- @param cmdType  string  One of protocol.CMD values
-- @param data     table   Command-specific parameters (or empty table)
-- @param serverId number  The server's computer ID (from config)
function protocol.commandPacket(cmdType, data, serverId)
    return protocol.pack(cmdType, data or {}, serverId)
end

-----------------------------------------------------------------------
-- TRANSMISSION HELPERS
-----------------------------------------------------------------------
-- These wrap the low-level modem.transmit() and os.pullEvent() calls
-- so the rest of the code doesn't need to worry about channel numbers
-- or event loop details.

--- Send a packet via modem.
-- Serializes the packet table and transmits it on the wireless channel.
-- @param modem   peripheral  Modem handle (from peripheral.find("modem"))
-- @param packet  table       Protocol packet (from pack/statusPacket/etc.)
-- @param channel number      Target channel (default: config.WIRELESS_CHANNEL)
-- @param reply   number      Reply channel (default: this computer's ID)
function protocol.send(modem, packet, channel, reply)
    if not modem then return false end
    channel = channel or config.WIRELESS_CHANNEL
    reply = reply or os.getComputerID()
    modem.transmit(channel, reply, packet)
    return true
end

--- Listen for one packet with timeout.
-- Blocks the program until a valid packet arrives or the timeout expires.
-- Automatically validates the packet and checks if it's addressed to us.
-- @param timeout number  Seconds to wait (nil = wait forever)
-- @return table|nil  Validated packet or nil if timed out
function protocol.receive(timeout)
    local timer
    if timeout then timer = os.startTimer(timeout) end

    while true do
        local event, p1, p2, p3, p4, p5 = os.pullEvent()

        if event == "modem_message" then
            local packet, err = protocol.unpack(p4)
            if packet and protocol.isForUs(packet) then
                if timer then os.cancelTimer(timer) end
                return packet, p5  -- packet, distance
            end
        elseif event == "timer" and p1 == timer then
            return nil  -- timeout
        end
    end
end

return protocol
