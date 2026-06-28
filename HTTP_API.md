# HTTP APIs

All endpoints have CORS configured to allow requests from any origin. In local mode the browser and the bridge run on the same machine.

When authentication is enabled, use header `Authorization: Bearer <token>` or Basic Auth (password = token).

Each endpoint can also be individually disabled or protected with its own password. See [README](README.md) for details.

## Configuration

### GET /config.json

Get content of `config.json` file.

### PUT /config.json

Update content of `config.json` file.

## Printing

### POST /printer

Submit a print job via HTTP. The request body is the same JSON format used for WebSocket messages.

**Request body:**

```json
{
  "type": "receipt",
  "url": "https://example.com/document.pdf",
  "id": "optional-job-id",
  "qty": 1,
  "file_content": "base64-encoded-file (alternative to url)",
  "raw_content": "base64-encoded-raw-data (for ESC-POS/ZPL/raw printing)",
  "extras": [{"text": "annotation", "x": 10.0, "y": 20.0, "size": 12, "bold": true}]
}
```

- `type` — maps to a printer in your configuration (required)
- `url` — URL of the document to download and print (for PDF/image)
- `file_content` — Base64-encoded file content (alternative to `url`, for PDF/image)
- `raw_content` — Base64-encoded raw binary data (for ESC-POS, ZPL, etc.)
- `qty` — number of copies (default: 1)
- `extras` — text annotations to overlay on the document before printing
- `id` — optional identifier, echoed back in the response

**Response (synchronous):**

```json
{"success": true, "message": "Success", "id": "optional-job-id", "printerName": "HP LaserJet"}
```

On error:

```json
{"success": false, "message": "No matched printer: receipt", "id": "optional-job-id", "printerName": null}
```

**Example — Print PDF from URL:**

```bash
curl -X POST http://127.0.0.1:12212/printer \
  -H "Content-Type: application/json" \
  -d '{"type":"INVOICE","url":"https://example.com/invoice.pdf"}'
```

**Example — Print raw ESC-POS data:**

```bash
curl -X POST http://127.0.0.1:12212/printer \
  -H "Content-Type: application/json" \
  -d '{"type":"RECEIPT","raw_content":"G0AbQBthAEhlbGxvIFdvcmxkCh0hERthAUVTQy9QT1MgUHJpbnRlciBUZXN0Ch0hABthAkdvb2RieWUgV29ybGQKHVZBAw=="}'
```

**Example — Print Base64-encoded PDF:**

```bash
curl -X POST http://127.0.0.1:12212/printer \
  -H "Content-Type: application/json" \
  -d '{"type":"INVOICE","url":"invoice.pdf","file_content":"JVBERi0xLjcK..."}'
```

## Serial

### POST /serial/{type}

Write data to a serial port mapped by `{type}` via HTTP.

**Request body:** plain text string that will be written to the serial port.

**Example:**

```bash
curl -X POST http://127.0.0.1:12212/serial/DISPLAY \
  -H "Content-Type: text/plain" \
  -d 'Hello World'
```

## System

### GET /system/printers.json

Return list of available printers.

**Response:**

```json
[{"name": "HP LaserJet Pro", "description": ""}, {"name": "Microsoft Print to PDF", "description": ""}]
```

### GET /system/serials.json

Return list of available serial ports.

**Response:**

```json
[{"name": "COM3", "description": "USB Serial Device", "manufacturer": "FTDI"}]
```

### GET /system/version.json

Return application version info.

**Response:**

```json
{"appName": "Local Hardware Bridge", "appId": "io.github.augustinlr17.localhardwarebridge", "version": "2.0.0"}
```

### POST /system/restart.json

Restart WebSocket/Web server.
