-- MortalSDK scene capture for BizHawk 2.11+ with Genesis Plus GX.
-- Press F8. Output goes to MORTALSDK_CAPTURE_DIR or ./captures.
local root = os.getenv("MORTALSDK_CAPTURE_DIR") or "captures"
local key_was_down, capture_number = false, 0

local function mkdir(path) os.execute('mkdir "' .. path .. '" 2>nul') end

local function write_domain(path, domain)
  local size = memory.getmemorydomainsize(domain)
  local values = memory.readbyterange(0, size, domain)
  local file, chunk = assert(io.open(path, "wb")), {}
  for address = 0, size - 1 do
    chunk[#chunk + 1] = string.char(values[address])
    if #chunk == 4096 then file:write(table.concat(chunk)); chunk = {} end
  end
  file:write(table.concat(chunk)); file:close()
end

local function set_layers(a, b, w)
  genesis.setlayer_bga(a); genesis.setlayer_bgb(b); genesis.setlayer_bgw(w)
end

local function render(state, path, a, b, w)
  savestate.load(state); set_layers(a, b, w); emu.frameadvance()
  client.screenshot(path); return emu.framecount()
end

local function capture()
  capture_number = capture_number + 1
  local frame = emu.framecount()
  local name = string.format("capture_%06d_%04d", frame, capture_number)
  local dir = root .. "/" .. name
  mkdir(root); mkdir(dir)
  local state = dir .. "/capture.State"
  savestate.save(state)
  local rendered = render(state, dir .. "/screen.png", true, true, true)
  write_domain(dir .. "/vram.bin", "VRAM")
  write_domain(dir .. "/cram.bin", "CRAM")
  write_domain(dir .. "/vsram.bin", "VSRAM")
  render(state, dir .. "/plane_a.png", true, false, false)
  render(state, dir .. "/plane_b.png", false, true, false)
  render(state, dir .. "/window.png", false, false, true)
  render(state, dir .. "/sprites.png", false, false, false)
  local metadata = assert(io.open(dir .. "/metadata.json", "w"))
  metadata:write(string.format('{\n  "captureFrame": %d,\n  "renderedFrame": %d,\n  "vramBytes": 65536,\n  "cramBytes": 128,\n  "vsramBytes": 128\n}\n', frame, rendered))
  metadata:close()
  savestate.load(state); set_layers(true, true, true)
  console.log("MortalSDK capture written to " .. dir)
  gui.addmessage("MortalSDK: captura guardada en " .. name)
end

console.log("MortalSDK capture ready. Press F8 in the emulator window.")
while true do
  local down = input.get()["F8"] == true
  if down and not key_was_down then capture() end
  key_was_down = down
  emu.frameadvance()
end
