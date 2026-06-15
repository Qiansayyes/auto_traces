"""轻量测试：验证 mail_integration 代理与 Node 子进程启动。"""

import sys
import time
from pathlib import Path

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

SERVER_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVER_DIR))

import mail_integration as mi
from mail_integration import attach_mail_module, _mail_module_ready, _stop_node_process

mi.MAIL_SESSION_RESOLVER = lambda _request: ("test_user", None)

app = FastAPI()
attach_mail_module(app)


def test_mail_module_status_and_proxy():
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(app) as client:
        for _ in range(40):
            if _mail_module_ready():
                break
            time.sleep(0.5)
        assert _mail_module_ready(), "mail module should become ready"

        status = client.get("/api/mail-module/status").json()
        assert status["code"] == 0
        assert status["ready"] is True

        proxied = client.get("/api/server_time", headers=headers).json()
        assert proxied.get("code") == "200"
        assert "nowMs" in proxied.get("data", {})

        accounts = client.post("/api/accounts/list", json={"page": 1, "pageSize": 10}, headers=headers).json()
        assert accounts.get("code") == "200"

        spa = client.get("/mail-admin/")
        assert spa.status_code == 200
        assert "text/html" in spa.headers.get("content-type", "")


if __name__ == "__main__":
    test_mail_module_status_and_proxy()
    print("mail module integration test passed")
    _stop_node_process()
