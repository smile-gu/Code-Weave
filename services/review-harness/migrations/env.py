import asyncio
import os

from alembic import context
from sqlalchemy import event, pool
from sqlalchemy.ext.asyncio import async_engine_from_config

config = context.config
url = os.environ.get("CODE_WEAVE_REVIEW_DATABASE_URL")
if url and not config.attributes.get("explicit_url"):
    config.set_main_option("sqlalchemy.url", url.replace("%", "%%"))


def run(connection):
    context.configure(connection=connection)
    with context.begin_transaction():
        context.run_migrations()


async def online():
    engine = async_engine_from_config(
        config.get_section(config.config_ini_section),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    @event.listens_for(engine.sync_engine, "connect")
    def configure(connection, _):
        cursor = connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=5000")
        cursor.close()

    async with engine.connect() as connection:
        await connection.run_sync(run)
    await engine.dispose()


if context.is_offline_mode():
    context.configure(url=config.get_main_option("sqlalchemy.url"), literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()
else:
    asyncio.run(online())
