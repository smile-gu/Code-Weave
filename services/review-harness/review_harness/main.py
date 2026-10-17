import uvicorn

from review_harness.api.app import create_app
from review_harness.config import Settings


def main():
    settings = Settings()
    # Official entry point is always one worker; multi-process ownership is unsupported.
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        workers=1,
        access_log=False,
    )


if __name__ == "__main__":
    main()
