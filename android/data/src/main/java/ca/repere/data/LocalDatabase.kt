package ca.repere.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "drinks", indices = [Index(value = ["serverId"], unique = true)])
data class DrinkEntity(
    @PrimaryKey val clientId: String,
    val serverId: Long?,
    val name: String,
    val type: String?,
    val volumeMl: Double,
    val abvPercent: Double,
    val quantity: Int,
    val startedAt: String,
    val durationMinutes: Int,
    val notes: String?,
    val active: Boolean,
    val dirty: Boolean,
    val deleted: Boolean,
    val pendingMutationId: String?,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val serverId: Long,
    val name: String,
    val type: String,
    val volumeMl: Double,
    val abvPercent: Double,
    val dirty:Boolean=false,
    val deleted:Boolean=false,
    val mutationId:String?=null,
)

@Entity(tableName = "sync_state")
data class SyncState(@PrimaryKey val account: String = "default", val cursor: Long = 0)

@Entity(tableName="tracked_days")
data class TrackedDayEntity(@PrimaryKey val day:String,val sober:Boolean=true)

@Entity(tableName="health_aggregates")
data class HealthAggregateEntity(@PrimaryKey val id:String,val localDate:String,val recordType:String,
    val originPackage:String,val payload:String,val dirty:Boolean=true)

@Entity(tableName="check_ins")
data class CheckInEntity(@PrimaryKey val id:String,val localDate:String,val payload:String,
    val dirty:Boolean=true,val deleted:Boolean=false,val lastError:String?=null)

@Entity(tableName="goals",indices=[Index(value=["serverId"],unique=true)])
data class GoalEntity(@PrimaryKey val clientId:String,val serverId:Long?,val kind:String,val target:Double,
    val active:Boolean=true,val temporalMode:String="consecutive_weeks",val consecutiveWeeks:Int?=null,
    val dueDate:String?=null,val startedOn:String,val dirty:Boolean=true,val deleted:Boolean=false,
    val achieved:Boolean=false,val lastError:String?=null)

@Entity(tableName="local_settings")
data class LocalSettings(@PrimaryKey val id:String="default",val dayStartHour:Int=8,
    val sessionGapHours:Double=8.0,val trackingStartDate:String?=null,val syncAccount:String?=null,val dirty:Boolean=false,
    val standardDrinkGrams:Double=13.45,val volumeUnit:String="ml")

@Entity(tableName="pending_api_operations")
data class PendingApiOperation(@PrimaryKey val id:String,val path:String,val method:String,val body:String,
    val createdAt:Long=System.currentTimeMillis(),val attempts:Int=0,val lastError:String?=null)

@Dao
interface RepereDao {
    @Query("SELECT * FROM drinks WHERE deleted = 0 ORDER BY startedAt DESC")
    fun observeDrinks(): Flow<List<DrinkEntity>>

    @Query("SELECT * FROM drinks WHERE deleted = 0 ORDER BY startedAt DESC")
    suspend fun drinks():List<DrinkEntity>

    @Query("SELECT * FROM drinks WHERE active = 1 AND deleted = 0 ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeDrink():DrinkEntity?

    @Query("SELECT * FROM presets ORDER BY serverId")
    fun observePresets(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE deleted = 0 ORDER BY serverId")
    fun observeVisiblePresets(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM drinks WHERE dirty = 1 ORDER BY startedAt")
    suspend fun pendingDrinks(): List<DrinkEntity>

    @Query("SELECT * FROM drinks WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: Long): DrinkEntity?

    @Query("SELECT * FROM drinks WHERE clientId = :clientId LIMIT 1")
    suspend fun findByClientId(clientId: String): DrinkEntity?

    @Query("SELECT startedAt FROM drinks WHERE deleted = 0 ORDER BY startedAt DESC LIMIT 400")
    suspend fun recentStartTimes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDrink(drink: DrinkEntity)

    @Query("DELETE FROM drinks WHERE clientId = :clientId")
    suspend fun deleteDrink(clientId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPresets(presets: List<PresetEntity>)

    @Query("DELETE FROM presets")
    suspend fun clearPresets()

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun presetCount():Int

    @Query("SELECT * FROM presets WHERE dirty = 1")
    suspend fun pendingPresets():List<PresetEntity>

    @Query("DELETE FROM presets WHERE serverId=:serverId")
    suspend fun deletePreset(serverId:Long)

    @Query("SELECT * FROM sync_state WHERE account = 'default'")
    suspend fun syncState(): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: SyncState)

    @Query("SELECT * FROM tracked_days ORDER BY day")
    fun observeTrackedDays():Flow<List<TrackedDayEntity>>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putTrackedDays(rows:List<TrackedDayEntity>)

    @Query("DELETE FROM tracked_days")
    suspend fun clearTrackedDays()

    @Query("DELETE FROM tracked_days WHERE day=:day")
    suspend fun deleteTrackedDay(day:String)

    @Query("SELECT * FROM health_aggregates ORDER BY localDate DESC, recordType")
    fun observeHealth():Flow<List<HealthAggregateEntity>>

    @Query("SELECT * FROM health_aggregates WHERE dirty = 1")
    suspend fun pendingHealth():List<HealthAggregateEntity>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putHealth(rows:List<HealthAggregateEntity>)

    @Query("UPDATE health_aggregates SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markHealthSynced(ids:List<String>)

    @Query("SELECT * FROM check_ins WHERE deleted = 0 ORDER BY localDate DESC")
    fun observeCheckIns():Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE localDate=:day AND deleted = 0 ORDER BY id DESC LIMIT 1")
    suspend fun checkInForDay(day:String):CheckInEntity?

    @Query("SELECT * FROM check_ins WHERE dirty = 1")
    suspend fun pendingCheckIns():List<CheckInEntity>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putCheckIn(row:CheckInEntity)

    @Query("SELECT * FROM goals WHERE deleted = 0 ORDER BY startedOn DESC")
    fun observeGoals():Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE dirty = 1")
    suspend fun pendingGoals():List<GoalEntity>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putGoal(row:GoalEntity)

    @Query("SELECT * FROM goals WHERE clientId=:clientId LIMIT 1")
    suspend fun goal(clientId:String):GoalEntity?

    @Query("DELETE FROM goals WHERE clientId=:clientId")
    suspend fun deleteGoal(clientId:String)

    @Query("SELECT * FROM local_settings WHERE id='default'")
    fun observeSettings():Flow<LocalSettings?>

    @Query("SELECT * FROM local_settings WHERE id='default'")
    suspend fun settings():LocalSettings?

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putSettings(settings:LocalSettings)

    @Query("SELECT * FROM pending_api_operations ORDER BY createdAt")
    suspend fun pendingApiOperations():List<PendingApiOperation>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putApiOperation(operation:PendingApiOperation)

    @Query("DELETE FROM pending_api_operations WHERE id=:id")
    suspend fun deleteApiOperation(id:String)

    @Query("DELETE FROM drinks WHERE dirty = 0 AND serverId IS NOT NULL")
    suspend fun removeSyncedDrinks()

    @Query("UPDATE drinks SET serverId = NULL, dirty = 1, pendingMutationId = clientId WHERE dirty = 1")
    suspend fun detachPendingDrinks()

    @Query("DELETE FROM presets WHERE dirty = 0 AND serverId > 0")
    suspend fun removeSyncedPresets()

    @Query("DELETE FROM goals WHERE dirty = 0 AND serverId IS NOT NULL")
    suspend fun removeSyncedGoals()

    @Query("DELETE FROM check_ins WHERE dirty = 0")
    suspend fun removeSyncedCheckIns()

    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()
}

@Database(entities = [DrinkEntity::class, PresetEntity::class, SyncState::class,HealthAggregateEntity::class,TrackedDayEntity::class,
    CheckInEntity::class,GoalEntity::class,LocalSettings::class,PendingApiOperation::class], version = 5, exportSchema = true)
abstract class RepereDatabase : RoomDatabase() {
    abstract fun dao(): RepereDao

    companion object {
        @Volatile private var instance: RepereDatabase? = null
        private val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS health_aggregates (id TEXT NOT NULL PRIMARY KEY, localDate TEXT NOT NULL, recordType TEXT NOT NULL, originPackage TEXT NOT NULL, payload TEXT NOT NULL, dirty INTEGER NOT NULL)")
        }}
        private val MIGRATION_2_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS tracked_days (day TEXT NOT NULL PRIMARY KEY, sober INTEGER NOT NULL)")
        }}
        private val MIGRATION_3_4=object:Migration(3,4){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE presets ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE presets ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE presets ADD COLUMN mutationId TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS check_ins (id TEXT NOT NULL PRIMARY KEY, localDate TEXT NOT NULL, payload TEXT NOT NULL, dirty INTEGER NOT NULL, deleted INTEGER NOT NULL, lastError TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS goals (clientId TEXT NOT NULL PRIMARY KEY, serverId INTEGER, kind TEXT NOT NULL, target REAL NOT NULL, active INTEGER NOT NULL, temporalMode TEXT NOT NULL, consecutiveWeeks INTEGER, dueDate TEXT, startedOn TEXT NOT NULL, dirty INTEGER NOT NULL, deleted INTEGER NOT NULL, achieved INTEGER NOT NULL, lastError TEXT)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_goals_serverId ON goals(serverId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS local_settings (id TEXT NOT NULL PRIMARY KEY, dayStartHour INTEGER NOT NULL, sessionGapHours REAL NOT NULL, trackingStartDate TEXT, syncAccount TEXT, dirty INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_api_operations (id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, method TEXT NOT NULL, body TEXT NOT NULL, createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError TEXT)")
        }}
        private val MIGRATION_4_5=object:Migration(4,5){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE local_settings ADD COLUMN standardDrinkGrams REAL NOT NULL DEFAULT 13.45")
            db.execSQL("ALTER TABLE local_settings ADD COLUMN volumeUnit TEXT NOT NULL DEFAULT 'ml'")
        }}
        fun get(context: Context): RepereDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RepereDatabase::class.java, "repere.db")
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).build().also { instance = it }
        }
    }
}
