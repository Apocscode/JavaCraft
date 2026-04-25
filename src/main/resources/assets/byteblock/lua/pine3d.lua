-- ════════════════════════════════════════════════════════════════════════
--  pine3d.lua — minimal Pine3D-style 3D wireframe renderer for ByteBlock
--  Renders to a glasses canvas (true-color, smooth lines/triangles).
--
--  Usage:
--    local pine3d = require("pine3d")
--    local cam = pine3d.newCamera({ x=0, y=0, z=-5 })
--    local cube = pine3d.cube(2, 0xFF8800)
--    local c = glasses.canvas()
--    pine3d.render(c, cam, { cube }, 200, 100, 100, 80)  -- canvas, cam, objs, cx, cy, vw, vh
--    c:add(); glasses.flush()
-- ════════════════════════════════════════════════════════════════════════
local M = {}

local sin, cos, tan = math.sin, math.cos, math.tan
local pi = math.pi

function M.newCamera(opts)
    opts = opts or {}
    return {
        x = opts.x or 0, y = opts.y or 0, z = opts.z or 0,
        rx = opts.rx or 0, ry = opts.ry or 0, rz = opts.rz or 0,
        fov = opts.fov or 70,
    }
end

-- Build a unit cube of half-size `s` centered on origin.
function M.cube(s, color)
    s = s or 1
    color = color or 0xFFFFFF
    local v = {
        {-s,-s,-s}, { s,-s,-s}, { s, s,-s}, {-s, s,-s},
        {-s,-s, s}, { s,-s, s}, { s, s, s}, {-s, s, s},
    }
    local e = {
        {1,2},{2,3},{3,4},{4,1},
        {5,6},{6,7},{7,8},{8,5},
        {1,5},{2,6},{3,7},{4,8},
    }
    return { verts = v, edges = e, color = color, x=0,y=0,z=0, rx=0,ry=0,rz=0 }
end

-- Build a regular pyramid (4 triangular faces) of half-base `s` and height `h`.
function M.pyramid(s, h, color)
    s = s or 1; h = h or 2; color = color or 0xFFFFFF
    local v = { {-s,-s/2,-s},{ s,-s/2,-s},{ s,-s/2, s},{-s,-s/2, s},{ 0, h-s/2, 0} }
    local e = { {1,2},{2,3},{3,4},{4,1},{1,5},{2,5},{3,5},{4,5} }
    return { verts = v, edges = e, color = color, x=0,y=0,z=0, rx=0,ry=0,rz=0 }
end

-- Build an arbitrary mesh from vertex/edge tables.
function M.mesh(verts, edges, color)
    return { verts = verts, edges = edges, color = color or 0xFFFFFF,
             x=0,y=0,z=0, rx=0,ry=0,rz=0 }
end

local function rotate(x, y, z, rx, ry, rz)
    -- X
    local cy_, sy_ = cos(rx), sin(rx)
    local y2 = y * cy_ - z * sy_
    local z2 = y * sy_ + z * cy_
    -- Y
    local cz_, sz_ = cos(ry), sin(ry)
    local x2 = x * cz_ + z2 * sz_
    z2       = -x * sz_ + z2 * cz_
    -- Z
    local cx_, sx_ = cos(rz), sin(rz)
    local xR = x2 * cx_ - y2 * sx_
    local yR = x2 * sx_ + y2 * cx_
    return xR, yR, z2
end

-- Render: project each object's edges and draw lines on the canvas.
-- canvas:line is the only call required from the host renderer.
function M.render(canvas, cam, objects, cx, cy, vw, vh)
    cx = cx or 100; cy = cy or 80
    vw = vw or 160; vh = vh or 120
    local fovRad = (cam.fov or 70) * pi / 180
    local f = (vh / 2) / tan(fovRad / 2)
    for _, obj in ipairs(objects) do
        -- Pre-transform vertices into screen space.
        local sv = {}
        for i, v in ipairs(obj.verts) do
            local x, y, z = v[1], v[2], v[3]
            -- Object rotation
            x, y, z = rotate(x, y, z, obj.rx or 0, obj.ry or 0, obj.rz or 0)
            -- Object translation
            x = x + (obj.x or 0); y = y + (obj.y or 0); z = z + (obj.z or 0)
            -- Camera translation
            x = x - cam.x; y = y - cam.y; z = z - cam.z
            -- Camera rotation (inverse)
            x, y, z = rotate(x, y, z, -(cam.rx or 0), -(cam.ry or 0), -(cam.rz or 0))
            -- Perspective project
            if z > 0.01 then
                sv[i] = { cx + x * f / z, cy - y * f / z, true }
            else
                sv[i] = { 0, 0, false }
            end
        end
        local color = obj.color or 0xFFFFFF
        for _, e in ipairs(obj.edges) do
            local a, b = sv[e[1]], sv[e[2]]
            if a and b and a[3] and b[3] then
                canvas:line(a[1], a[2], b[1], b[2], color)
            end
        end
    end
end

return M
