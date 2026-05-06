#!/usr/bin/env bash
set -euo pipefail

# One-click deploy for auto_traces on Ubuntu VPS.
# Usage:
#   sudo bash deploy_vps.sh
#   sudo APP_DIR=/opt/auto_traces/auto_traces SERVER_NAME=43.165.168.45 bash deploy_vps.sh

APP_DIR="${APP_DIR:-/opt/auto_traces/auto_traces}"
SERVER_NAME="${SERVER_NAME:-43.165.168.45}"
BACKEND_PORT="${BACKEND_PORT:-8000}"
SERVICE_NAME="${SERVICE_NAME:-auto-traces-backend}"
SYSTEMD_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
NGINX_SITE="/etc/nginx/sites-available/auto_traces"
NGINX_ENABLED="/etc/nginx/sites-enabled/auto_traces"

if [[ "${EUID}" -ne 0 ]]; then
  echo "[ERROR] Please run as root: sudo bash deploy_vps.sh"
  exit 1
fi

if [[ ! -d "${APP_DIR}" ]]; then
  echo "[ERROR] APP_DIR not found: ${APP_DIR}"
  exit 1
fi

if [[ ! -f "${APP_DIR}/server/main.py" ]]; then
  echo "[ERROR] Backend entry not found: ${APP_DIR}/server/main.py"
  exit 1
fi

if [[ ! -f "${APP_DIR}/nginx-auto_traces.conf" ]]; then
  echo "[ERROR] Nginx config not found: ${APP_DIR}/nginx-auto_traces.conf"
  exit 1
fi

echo "[1/8] Installing system dependencies..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y nginx python3 python3-venv python3-pip curl ufw

echo "[2/8] Preparing Python virtual environment..."
if [[ ! -d "${APP_DIR}/.venv" ]]; then
  python3 -m venv "${APP_DIR}/.venv"
fi
"${APP_DIR}/.venv/bin/pip" install --upgrade pip setuptools wheel
if [[ -f "${APP_DIR}/server/requirements.txt" ]]; then
  "${APP_DIR}/.venv/bin/pip" install -r "${APP_DIR}/server/requirements.txt"
elif [[ -f "${APP_DIR}/requirements.txt" ]]; then
  "${APP_DIR}/.venv/bin/pip" install -r "${APP_DIR}/requirements.txt"
else
  echo "[WARN] requirements.txt not found, skip Python dependencies install."
fi

echo "[3/8] Writing systemd service..."
cat > "${SYSTEMD_FILE}" <<EOF
[Unit]
Description=Auto Traces Backend
After=network.target

[Service]
Type=simple
WorkingDirectory=${APP_DIR}/server
Environment=HOST=127.0.0.1
Environment=PORT=${BACKEND_PORT}
ExecStart=${APP_DIR}/.venv/bin/python ${APP_DIR}/server/main.py
Restart=always
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now "${SERVICE_NAME}"

echo "[4/8] Deploying Nginx configuration..."
cp -f "${APP_DIR}/nginx-auto_traces.conf" "${NGINX_SITE}"

# Ensure server_name is aligned with runtime value
sed -i -E "s#^[[:space:]]*server_name[[:space:]]+[^;]+;#    server_name ${SERVER_NAME};#g" "${NGINX_SITE}"
# Ensure backend upstream port is aligned with runtime value
sed -i -E "s#proxy_pass http://127\.0\.0\.1:[0-9]+/api/;#        proxy_pass http://127.0.0.1:${BACKEND_PORT}/api/;#g" "${NGINX_SITE}"

ln -sf "${NGINX_SITE}" "${NGINX_ENABLED}"
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl restart nginx
systemctl enable nginx

echo "[5/8] Opening firewall ports..."
ufw allow 22/tcp || true
ufw allow 80/tcp || true
ufw allow 443/tcp || true
ufw --force enable || true

echo "[6/8] Waiting backend boot..."
sleep 2

echo "[7/8] Health checks..."
set +e
BACKEND_HEALTH="$(curl -sS -m 10 "http://127.0.0.1:${BACKEND_PORT}/api/health" || true)"
NGINX_LOCAL_CODE="$(curl -sS -o /dev/null -m 10 -w "%{http_code}" "http://127.0.0.1/" || true)"
PUBLIC_CODE="$(curl -sS -o /dev/null -m 10 -w "%{http_code}" "http://${SERVER_NAME}/" || true)"
set -e

echo "----------------------------------------"
echo "Backend health response: ${BACKEND_HEALTH:-<empty>}"
echo "Nginx local status code: ${NGINX_LOCAL_CODE:-<empty>}"
echo "Public access status code: ${PUBLIC_CODE:-<empty>}"
echo "----------------------------------------"

echo "[8/8] Current service status:"
systemctl --no-pager --full status "${SERVICE_NAME}" || true
systemctl --no-pager --full status nginx || true

cat <<EOF

Deployment completed.

Visit:
  http://${SERVER_NAME}/

If public access is still blocked, check your cloud security group:
  - Allow inbound TCP 80 from 0.0.0.0/0
  - (Optional) Allow inbound TCP 443 from 0.0.0.0/0
EOF
