from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    database_url: str = "sqlite:////data/database.sqlite"
    secret_key: str = "change-me"
    secure_cookies: bool = False
    trusted_hosts: str = "*"
    data_dir: str = "/data"
    openai_api_key: str | None = None
    openai_model: str = "gpt-5.6-sol"
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()
