#!/bin/bash
set -e

# Start CUPS in background
mkdir -p /var/run/cups /var/cache/cups /var/spool/cups /tmp/cups-pdf /tmp/cups-pdf-anon
chmod 777 /tmp/cups-pdf /tmp/cups-pdf-anon

/usr/sbin/cupsd -f &

# Wait for CUPS to be ready
for i in {1..60}; do
    if lpstat -p 2>/dev/null | grep -q CUPS-PDF; then
        break
    fi
    if cupsctl --no-debugging 2>/dev/null; then
        break
    fi
    sleep 1
done

# Create CUPS-PDF printer if not present
if ! lpstat -p 2>/dev/null | grep -q CUPS-PDF; then
    lpadmin -p CUPS-PDF -E -v cups-pdf:/ -P /usr/share/ppd/cups-pdf/CUPS-PDF_opt.ppd 2>/dev/null || \
    lpadmin -p CUPS-PDF -E -v cups-pdf:/ -m everywhere 2>/dev/null || \
    lpadmin -p CUPS-PDF -E -v cups-pdf:/ -m lsb/usr/cups-pdf/CUPS-PDF.ppd 2>/dev/null || true
fi

# Ensure CUPS-PDF is accepting jobs
accept CUPS-PDF 2>/dev/null || true
cupsenable CUPS-PDF 2>/dev/null || true

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

# Run cross-bridge multi-tenant tests (3 bridge instances on ports 12212-12214)
python3 /app/test-cross-bridge.py
CROSS_EXIT=$?

# Cleanup
kill $BRIDGE_PID 2>/dev/null || true
wait $BRIDGE_PID 2>/dev/null || true

# Exit with failure if either test suite failed
if [ $TEST_EXIT -ne 0 ]; then
    exit $TEST_EXIT
fi
exit $CROSS_EXIT
