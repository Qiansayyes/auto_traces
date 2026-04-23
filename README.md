# auto_traces

## 目录结构

- `server/`: 完整可运行的 FastAPI 内存服务
- `android-client/`: Android Studio 可导入的 Kotlin 客户端骨架

## 先启动云端

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Swagger:

- `http://127.0.0.1:8000/docs`

## 再导入安卓端

使用 Android Studio 打开 `android-client/` 即可。

## 说明

已实现：

- 云端所有核心接口与内存数据结构
- 安卓端绑定、配置拉取、状态同步、轮询、日志与结果上报链路

未实现：

- 针对第三方平台的无障碍自动化、反检测、规避类逻辑
