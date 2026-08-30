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
    is_active: bool = False
    notes: str | None = None
    cost: float | None = None
    timezone_id: str | None = Field(default=None, max_length=64)
    utc_offset_minutes: int | None = Field(default=None, ge=-840, le=840)
    display_quantity: float | None = Field(default=None, ge=0)
    display_unit: str | None = Field(default=None, max_length=24)

class DrinkOut(DrinkIn):
    id: int
    ended_at: datetime
    alcohol_grams: float
    canadian_standard_drinks: float
    started_at_utc: datetime | None = None
    ended_at_utc: datetime | None = None
    local_date: date | None = None
    model_config = ConfigDict(from_attributes=True)

class Login(BaseModel):
    username: str
    password: str = Field(min_length=8)

class SettingsPatch(BaseModel):
    tracking_start_date: date | None = None
    weight_kg: float | None = Field(default=None, gt=20, lt=400)
    height_cm: float | None = Field(default=None, gt=100, lt=250)
    sex: str | None = Field(default=None, pattern="^(male|female|unspecified)$")
    distribution_ratio: float | None = Field(default=None, gt=.3, lt=1)
    elimination_rate: float | None = Field(default=None, gt=.005, lt=.04)
    session_gap_hours: float | None = Field(default=None, gt=0, le=24)
    day_start_hour: int | None = Field(default=None, ge=0, le=23)
    standard_drink_grams: float | None = Field(default=None, gt=4, lt=30)
    volume_unit: str | None = Field(default=None, pattern="^(ml|oz)$")
