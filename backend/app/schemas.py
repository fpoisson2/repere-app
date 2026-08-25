from datetime import date, datetime
from pydantic import BaseModel, ConfigDict, Field

class DrinkIn(BaseModel):
    drink_type: str | None = None
    drink_name: str
    volume_ml: float = Field(gt=0)
    abv_percent: float = Field(ge=0, le=100)
    quantity: int = Field(default=1, ge=1)
    started_at: datetime
    duration_minutes: int = Field(default=30, ge=0)
    notes: str | None = None
    cost: float | None = None

class DrinkOut(DrinkIn):
    id: int
    ended_at: datetime
    alcohol_grams: float
    canadian_standard_drinks: float
    model_config = ConfigDict(from_attributes=True)

class Login(BaseModel):
    username: str
    password: str = Field(min_length=8)

class SettingsPatch(BaseModel):
    tracking_start_date: date | None = None
    weight_kg: float | None = Field(default=None, gt=20, lt=400)
    distribution_ratio: float | None = Field(default=None, gt=.3, lt=1)
    elimination_rate: float | None = Field(default=None, gt=.005, lt=.04)
    session_gap_hours: float | None = Field(default=None, gt=0, le=24)
    day_start_hour: int | None = Field(default=None, ge=0, le=23)
