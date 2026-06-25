# E2E test environment for Local Hardware Bridge
FROM eclipse-temurin:21-jdk

# Install Python, CUPS, and CUPS-PDF printer
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y \
    python3 \
    python3-urllib3 \
    python3-venv \
    cups \
    cups-pdf \
    && rm -rf /var/lib/apt/lists/*

# Configure CUPS-PDF output directory
RUN mkdir -p /tmp/cups-pdf && chmod 777 /tmp/cups-pdf
COPY e2e/cups-pdf.conf /etc/cups/cups-pdf.conf

# Add CUPS admin user
RUN useradd lpadmin -G lpadmin || true

# Copy application files
WORKDIR /app
COPY build/libs/local-hardware-bridge-*.jar /app/local-hardware-bridge.jar
COPY e2e/test.py /app/test.py
COPY e2e/print-test.pdf /app/print-test.pdf
COPY e2e/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Expose bridge port
EXPOSE 12212

ENTRYPOINT ["/app/entrypoint.sh"]
