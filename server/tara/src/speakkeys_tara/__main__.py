import os

import uvicorn


def main() -> None:
    uvicorn.run(
        "speakkeys_tara.main:app",
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8080")),
        workers=1,
        access_log=False,
        ws_ping_interval=20,
        ws_ping_timeout=20,
    )


if __name__ == "__main__":
    main()
