#!/bin/bash
set -e

# Start CUPS in background
/usr/sbin/cupsd

# Wait for CUPS to be ready
sleep 3

# Add CUPS-PDF printer if not already present
if ! lpstat -p 2>/dev/null | grep -q CUPS-PDF; then
    lpadmin -p CUPS-PDF -E -v cups-pdf:/ -P /usr/share/ppd/cups-pdf/CUPS-PDF_opt.ppd || \
    lpadmin -p CUPS-PDF -E -v cups-pdf:/ -m everywhere || true
fi

# Start the bridge in server mode
java -cp /app/local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server &
BRIDGE_PID=$!

# Wait for the bridge to be ready
for i in {1..30}; do
    if curl -sf http://127.0.0.1:12212/system/health >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

# Run tests
python3 /app/test.py
TEST_EXIT=$?

# Cleanup
kill $BRIDGE_PID 2>/dev/null || true
wait $BRIDGE_PID 2>/dev/null || true

exit $TEST_EXIT
