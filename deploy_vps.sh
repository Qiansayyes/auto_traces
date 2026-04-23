#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/opt/auto_traces"
VENV_DIR="$PROJECT_DIR/.venv"
DOMAIN="your-domain.com"

sudo apt update
sudo apt install -y python3 python3-venv python3-pip nginx

cd "$PROJECT_DIR"
python3 -m venv "$VENV_DIR"
source "$VENV_DIR/bin/activate"
pip install --upgrade pip
pip install fastapi uvicorn requests pydantic openpyxl selenium requests python-multipart

sudo tee /etc/systemd/system/auto-traces-backend.service >/dev/null <<EOF
[Unit]
Description=Auto Traces Backend
After=network.target

[Service]
Type=simple
WorkingDirectory=$PROJECT_DIR
ExecStart=$VENV_DIR/bin/uvicorn server.main:app --host 127.0.0.1 --port 8000
Restart=always
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
EOF

sudo tee /etc/nginx/sites-available/auto_traces >/dev/null <<EOF
server {
    listen 80;
    server_name $DOMAIN;

    root $PROJECT_DIR/web-admin;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8000/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/auto_traces /etc/nginx/sites-enabled/auto_traces
sudo nginx -t
sudo systemctl daemon-reload
sudo systemctl enable auto-traces-backend
sudo systemctl restart auto-traces-backend
sudo systemctl restart nginx
