"""Patches CC31 startup.lua for ByteBlock VFS compatibility."""
import sys, re

src = r"C:\Users\travf\curseforge\minecraft\Instances\All the Mods 10 - ATM10\saves\Train2map\computercraft\computer\31\v3\startup.lua"
dst = r"F:\JavaCraft\lua\factory_controller\startup.lua"

with open(src, "r", encoding="utf-8") as f:
    content = f.read()

OLD = (
    "-- Ensure require() can find v3/ modules when running from computer root.\n"
    "-- CC:Tweaked's default package.path doesn't include subdirectories.\n"
    'package.path = "/?.lua;/?/init.lua;" .. package.path'
)

NEW = """\
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
    if not modname:match("^v3[%%./]") then return _origRequire(modname) end
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
end"""

if OLD not in content:
    # Try CRLF variant
    OLD_CRLF = OLD.replace("\n", "\r\n")
    if OLD_CRLF in content:
        OLD = OLD_CRLF
        NEW = NEW.replace("\n", "\r\n")
        print("Using CRLF variant")
    else:
        print("ERROR: target block not found in source file")
        sys.exit(1)

patched = content.replace(OLD, NEW, 1)
assert patched != content, "Replacement had no effect"

with open(dst, "w", encoding="utf-8", newline="\n") as f:
    f.write(patched)

print(f"OK: wrote {len(patched)} chars to {dst}")
