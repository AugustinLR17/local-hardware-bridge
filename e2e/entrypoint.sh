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
    if curl -sf http://127.0.0.1:57212/system/health >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

# Run tests
python3 /app/test.py
TEST_EXIT=$?
TEST_EXT_EXIT=0

if [ $TEST_EXIT -eq 0 ]; then
    # Run extended endpoint tests (same bridge instance, no restart needed)
    python3 /app/test-extended.py
    TEST_EXT_EXIT=$?
fi

# Kill the first bridge so the cross-bridge test can start its own instances
kill $BRIDGE_PID 2>/dev/null || true
wait $BRIDGE_PID 2>/dev/null || true

# Wait for port 57212 to be fully released (TIME_WAIT can hold it)
wait_port_free() {
    local port=$1
    for i in $(seq 1 60); do
        if ! python3 -c "import socket; s=socket.socket(); s.settimeout(1); s.connect(('127.0.0.1', $port))" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_port_free 57212

# Run cross-bridge multi-tenant tests (3 bridge instances on ports 57212-57214)
python3 /app/test-cross-bridge.py
CROSS_EXIT=$?

# Kill any remaining bridge processes before starting WMS tests
pkill -f "local-hardware-bridge.jar" 2>/dev/null || true
# Wait for all ports to be released
wait_port_free 57212
wait_port_free 57213
wait_port_free 57214

# Run WMS multi-zone tests (3 zone bridges on ports 57215-57217)
python3 /app/test-wms-multi-zone.py
WMS_EXIT=$?

# Kill any remaining bridge processes before starting update tests
pkill -f "local-hardware-bridge.jar" 2>/dev/null || true
pkill -f "bridge.jar" 2>/dev/null || true
# Wait for all ports to be released
wait_port_free 57212
wait_port_free 57215
wait_port_free 57216
wait_port_free 57217

# Run auto-update API tests (single bridge on port 57212)
python3 /app/test-auto-update.py
UPDATE_EXIT=$?

# Exit with failure if any test suite failed
if [ $TEST_EXIT -ne 0 ]; then
    exit $TEST_EXIT
fi
if [ $TEST_EXT_EXIT -ne 0 ]; then
    exit $TEST_EXT_EXIT
fi
if [ $CROSS_EXIT -ne 0 ]; then
    exit $CROSS_EXIT
fi
if [ $WMS_EXIT -ne 0 ]; then
    exit $WMS_EXIT
fi
exit $UPDATE_EXIT
